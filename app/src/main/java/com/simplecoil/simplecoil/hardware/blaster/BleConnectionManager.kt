/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.hardware.blaster

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.simplecoil.simplecoil.BluetoothLeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the connection is, for observers. */
sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class Connecting(val address: String) : BleConnectionState
    data class ServicesDiscovered(val address: String) : BleConnectionState
    data class Communicating(val address: String, val blasterType: Byte) : BleConnectionState
    data class Disconnected(val reason: Reason) : BleConnectionState {
        enum class Reason { ScanTimeout, LinkLost, WatchdogTimeout, User }
    }
}

/** One-shot outcomes the UI reacts to. */
sealed interface BleConnectionEvent {
    /** A scanned device matched; the service is being bound for [address]. */
    data class ScanMatched(val address: String) : BleConnectionEvent

    /** No matching blaster within [BleConnectionManager.SCAN_TIMEOUT_MILLIS]. */
    data object ScanTimedOut : BleConnectionEvent

    /** BluetoothLeService.initialize() failed after binding. */
    data object InitializeFailed : BleConnectionEvent

    /** The Bluetooth adapter was switched on. */
    data object AdapterTurnedOn : BleConnectionEvent
}

/**
 * Scans for the blaster, binds BluetoothLeService on the application context and hands it to
 * [BlasterManager]; also watches the Bluetooth adapter state. Moved from FullscreenActivity with
 * the same matching rules (unfiltered scan + [ScanMatcher]) and the same 30 s give-up.
 *
 * Main thread only. [scope] runs the scan timeout; tests pass a virtual-time scope.
 */
class BleConnectionManager(
    private val context: Context,
    private val blasterManager: BlasterManager,
    private val scope: CoroutineScope,
    private val serviceBinder: ServiceBinder = AndroidServiceBinder(context),
    private val hasConnectPermission: () -> Boolean = { defaultHasConnectPermission(context) },
    private val saveDeviceAddress: (String) -> Unit = { defaultSaveDeviceAddress(context, it) },
) {
    /** Starts/binds/unbinds BluetoothLeService. */
    interface ServiceBinder {
        fun bind(connection: ServiceConnection)
        fun unbind(connection: ServiceConnection)
    }

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BleConnectionEvent>(extraBufferCapacity = 16)

    /** Scan / bind outcomes. Emitted on the main thread; not replayed. */
    val events: SharedFlow<BleConnectionEvent> = _events.asSharedFlow()

    /** A scan is in progress (the activity's former mScanning). */
    var isScanning = false
        private set

    /** The service is bound or GATT reported a connection (the activity's former mConnected). */
    var isConnected = false
        private set

    private var scanner: BluetoothLeScanner? = null
    private var savedAddress: String = ""
    private var address: String = ""
    private var serviceConnection: ServiceConnection? = null
    private var service: BluetoothLeService? = null
    private var scanTimeout: Job? = null
    private var adapterReceiverRegistered = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            blasterManager.events.collect { onBlasterEvent(it) }
        }
    }

    private fun onBlasterEvent(event: BlasterEvent) {
        when (event) {
            is BlasterEvent.Connected -> isConnected = true
            BlasterEvent.ServicesDiscovered -> _state.value = BleConnectionState.ServicesDiscovered(address)
            is BlasterEvent.Telemetry -> if (_state.value !is BleConnectionState.Communicating) {
                _state.value = BleConnectionState.Communicating(address, blasterManager.blasterType)
            }
            BlasterEvent.Disconnected -> _state.value = BleConnectionState.Disconnected(BleConnectionState.Disconnected.Reason.LinkLost)
            else -> Unit
        }
    }

    /** Outcome of [startScan]. */
    enum class ScanStart { STARTED, PERMISSION_DENIED }

    /**
     * Starts an unfiltered scan on [adapter] for the blaster at [savedAddress] (or, when empty,
     * the first `SRG1` device), and the 30 s give-up. When the adapter has no LE scanner nothing
     * is scanned but the give-up still runs, as before.
     */
    fun startScan(adapter: BluetoothAdapter, savedAddress: String): ScanStart {
        this.savedAddress = savedAddress
        try {
            scanner = adapter.bluetoothLeScanner
            scanner?.let {
                Log.d(TAG, "starting to scan")
                it.startScan(scanCallback)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan: " + e.message)
            return ScanStart.PERMISSION_DENIED
        }
        isScanning = true
        _state.value = BleConnectionState.Scanning
        startScanTimeout()
        return ScanStart.STARTED
    }

    /** Stops the scan, if any. */
    fun stopScan() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping BLE scan: " + e.message)
        }
        isScanning = false
    }

    /** Forgets that a scan is running without stopping the scanner (what handleDisconnect did). */
    fun clearScanning() {
        isScanning = false
    }

    fun cancelScanTimeout() {
        scanTimeout?.cancel()
        scanTimeout = null
    }

    /** Asks the bound service, if any, to reconnect to [address]. */
    fun reconnect(address: String?) {
        val s = service ?: return
        if (address.isNullOrEmpty()) return
        val result = s.connect(address)
        Log.d(TAG, "Connect request result=$result")
    }

    /** Disconnects, closes and unbinds the service (the activity's resetBluetoothServices). */
    fun reset() {
        isConnected = false
        service?.let {
            it.disconnect()
            it.close()
        }
        serviceConnection?.let { serviceBinder.unbind(it) }
        serviceConnection = null
        service = null
        _state.value = BleConnectionState.Idle
    }

    /** Emits [BleConnectionEvent.AdapterTurnedOn] whenever Bluetooth is switched on. Idempotent. */
    fun registerAdapterStateReceiver() {
        if (adapterReceiverRegistered) return
        ContextCompat.registerReceiver(context, adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        adapterReceiverRegistered = true
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                emit(BleConnectionEvent.AdapterTurnedOn)
            }
        }
    }

    private fun startScanTimeout() {
        // Auto disconnect and quit searching if we fail to find a blaster
        isConnected = false
        scanTimeout?.cancel()
        scanTimeout = scope.launch {
            delay(SCAN_TIMEOUT_MILLIS)
            if (!isConnected) {
                Log.d(TAG, "Failed to find a weapon before timeout")
                stopScan()
                _state.value = BleConnectionState.Disconnected(BleConnectionState.Disconnected.Reason.ScanTimeout)
                emit(BleConnectionEvent.ScanTimedOut)
            }
        }
    }

    internal val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            checkDevice(result.device)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            for (result in results) {
                if (checkDevice(result.device)) return
            }
        }
    }

    internal fun checkDevice(device: BluetoothDevice): Boolean {
        if (!hasConnectPermission()) return false
        try {
            val name = device.name
            if (name != null && name.isNotEmpty() && ScanMatcher.matches(name, device.address, savedAddress)) {
                Log.d(TAG, "Connecting to " + name + " '" + device.address + "'")
                address = device.address
                _state.value = BleConnectionState.Connecting(address)
                emit(BleConnectionEvent.ScanMatched(address))
                stopScan()
                bindService()
                return true
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in checkDevice: " + e.message)
        }
        return false
    }

    private fun bindService() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                val bound = (binder as BluetoothLeService.LocalBinder).getService()
                service = bound
                if (!bound.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth")
                    emit(BleConnectionEvent.InitializeFailed)
                }
                // Attach before connecting so BlasterManager sees every GATT event.
                blasterManager.setBluetoothLeService(bound)
                // Automatically connects to the device upon successful start-up initialization.
                bound.connect(address)
                saveDeviceAddress(address)
                isConnected = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                blasterManager.setBluetoothLeService(null)
            }
        }
        serviceConnection = connection
        serviceBinder.bind(connection)
    }

    private fun emit(event: BleConnectionEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "Dropped $event")
    }

    /** Binds on the application context, starting the service in the foreground first. */
    class AndroidServiceBinder(private val context: Context) : ServiceBinder {
        override fun bind(connection: ServiceConnection) {
            val intent = Intent(context, BluetoothLeService::class.java)
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        override fun unbind(connection: ServiceConnection) {
            context.unbindService(connection)
        }
    }

    companion object {
        private const val TAG = "BleConnectionManager"

        /** §1.6: give up scanning after this long without finding a blaster. */
        const val SCAN_TIMEOUT_MILLIS = 30_000L

        private const val PREF_NAME = "SimpleCoil"
        private const val PREF_DEVICE_ADDRESS = "DeviceAddress"

        private fun defaultHasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        private fun defaultSaveDeviceAddress(context: Context, address: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_DEVICE_ADDRESS, address)
                .apply()
        }
    }
}
