/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.simplecoil.simplecoil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.simplecoil.simplecoil.hardware.blaster.GattEvent
import com.simplecoil.simplecoil.hardware.blaster.GattEventSink
import com.simplecoil.simplecoil.hardware.blaster.GattIo
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.hardware.blaster.ProtocolTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
open class BluetoothLeService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sessionState = (application as SimpleCoilApp).container.sessionEngine.state
    }

    /**
     * The app starts this service with startForegroundService() right before binding and
     * connecting, so enter the foreground here rather than when GATT connects: a slow or failed
     * connectGatt would otherwise miss the deadline (ForegroundServiceDidNotStartInTimeException).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Connecting to Blaster")
        // Only the app's own connect can bring the link back; a restart after the process died
        // would just show "Connecting to Blaster" with nothing connecting.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth LE Connection Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun notificationBuilder(): NotificationCompat.Builder {
        val notificationIntent = FullscreenActivity.resumeIntent(this)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SimpleCoil Connected")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun createPersistentNotification(contentText: String): Notification =
        notificationBuilder().setContentText(contentText).build()

    /** The in-game notification: a health bar, and the shots left or a spinner while reloading. */
    private fun createGameNotification(hud: GameHud): Notification {
        val text = if (hud.reloading) {
            getString(R.string.blaster_notification_game_text_reloading, hud.health, hud.maxHealth)
        } else {
            resources.getQuantityString(R.plurals.blaster_notification_game_text, hud.shots, hud.health, hud.maxHealth, hud.shots)
        }
        val views = RemoteViews(packageName, R.layout.notification_blaster_game).apply {
            setTextViewText(R.id.notification_health_tv, getString(R.string.blaster_notification_health, hud.health, hud.maxHealth))
            setProgressBar(R.id.notification_health_pb, hud.maxHealth, hud.health, false)
            setTextViewText(R.id.notification_shots_tv, hud.shots.toString())
            setViewVisibility(R.id.notification_shots_tv, if (hud.reloading) View.INVISIBLE else View.VISIBLE)
            setViewVisibility(R.id.notification_reload_pb, if (hud.reloading) View.VISIBLE else View.GONE)
        }
        return notificationBuilder()
            .setContentText(text) // where the custom view isn't shown
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .build()
    }

    open fun startAsForeground(statusText: String) {
        startForeground(NOTIFICATION_ID, createPersistentNotification(statusText))
    }

    /** What the in-game notification shows; null when no game is in progress. */
    private data class GameHud(val health: Int, val maxHealth: Int, val shots: Int, val reloading: Boolean)

    private fun SessionState.toHud(): GameHud? =
        if (gameInProgress) GameHud(health.coerceAtLeast(0), maxHealth, shotsRemaining.toInt(), reloadBarShown == true) else null

    /** The session the notification follows; null until onCreate (JVM tests never set it). */
    private var sessionState: StateFlow<SessionState>? = null
    private var sessionUpdates: Job? = null

    /** Guards [showingConnected] so an update can't repost the notification after a disconnect removed it. */
    private val notificationLock = Any()
    private var showingConnected = false

    /**
     * While connected, keeps the notification in step with the game. Updates are throttled, since
     * the system drops a burst of them (full auto) and could drop the last one.
     */
    private fun followSession() {
        val state = sessionState ?: return
        synchronized(notificationLock) { showingConnected = true }
        sessionUpdates?.cancel()
        sessionUpdates = scope.launch {
            state.map { it.toHud() }.distinctUntilChanged().conflate().collect { hud ->
                val notification = if (hud == null) createPersistentNotification(CONNECTED_TEXT) else createGameNotification(hud)
                synchronized(notificationLock) {
                    if (showingConnected) getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                }
                delay(NOTIFICATION_UPDATE_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopFollowingSession() {
        synchronized(notificationLock) { showingConnected = false }
        sessionUpdates?.cancel()
        sessionUpdates = null
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothDeviceAddress: String? = null

    // Non-null only after connect() succeeded, which requires an initialized adapter.
    private var gatt: GattIo? = null
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * GATT events in callback order (D8: in-process, replacing the package broadcasts). Emitted
     * from Binder threads; collectors choose their own dispatcher. Not replayed.
     */
    val events: SharedFlow<GattEvent> = _events.asSharedFlow()

    private var eventSink: GattEventSink = FlowEventSink()

    /**
     * Runs the protocol delays (§1.6) on the main thread, as the Handlers this replaced did.
     * Created on first use so constructing the service (as JVM tests do) doesn't resolve
     * Dispatchers.Main.
     */
    private val defaultScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var scopeOverride: CoroutineScope? = null
    private val scope: CoroutineScope get() = scopeOverride ?: defaultScope

    @Suppress("unused") // kept for debugging, as before
    private var connectionState = STATE_DISCONNECTED

    // Implements callback methods for GATT events that the app cares about.
    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status, newState=$newState")
            ProtocolTrace.event("connectionState status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionState = STATE_CONNECTED
                broadcastUpdate(ACTION_GATT_CONNECTED)
                Log.i(TAG, "Connected to GATT server.")
                startAsForeground(CONNECTED_TEXT)
                followSession()
                // Attempts to discover services after successful connection with delay.
                scope.launch {
                    delay(DISCOVER_SERVICES_DELAY_MILLIS)
                    this@BluetoothLeService.gatt?.let {
                        ProtocolTrace.event("discoverServices")
                        Log.i(TAG, "Attempting to start service discovery after delay:" + it.discoverServices())
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED
                Log.i(TAG, "Disconnected from GATT server: $status")
                stopFollowingSession()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            ProtocolTrace.event("servicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "read success!")
                @Suppress("DEPRECATION")
                broadcastUpdate(characteristic, characteristic.value)
            } else {
                Log.d(TAG, "read failed")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "read success!")
                broadcastUpdate(characteristic, value)
            } else {
                Log.d(TAG, "read failed")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "write success!")
            } else {
                Log.d(TAG, "write failed")
            }
            ProtocolTrace.event("writeFinished status=$status")
            broadcastUpdate(CHARACTERISTIC_WRITE_FINISHED)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            broadcastUpdate(characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            broadcastUpdate(characteristic, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "descriptor write success!")
            } else {
                Log.d(TAG, "descriptor write failed")
            }
            ProtocolTrace.event("descriptorWriteFinished status=$status")
            broadcastUpdate(DESCRIPTOR_WRITE_FINISHED)
        }
    }

    private fun broadcastUpdate(action: String) {
        eventSink.onEvent(action)
    }

    private fun broadcastUpdate(characteristic: BluetoothGattCharacteristic, value: ByteArray?) {
        ProtocolTrace.receive(characteristic.uuid, value)
        if (UUID_RECOIL_TELEMETRY == characteristic.uuid) {
            eventSink.onTelemetry(value)
        } else if (UUID_RECOIL_ID == characteristic.uuid) {
            val firmwareVer = (characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) shl 8) +
                characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
            Log.d(TAG, "Firmware version: $firmwareVer")
            @Suppress("DEPRECATION")
            val data = value ?: characteristic.value
            eventSink.onBlasterId(if (data != null && data.size > 10) data[10] else null)
        } else {
            Log.e(TAG, "unexpected characteristic data from " + characteristic.uuid.toString())
        }
    }

    /** Delivers GATT events to [events]. */
    private inner class FlowEventSink : GattEventSink {
        override fun onEvent(action: String) = emit(GattEvent.Action(action))

        override fun onTelemetry(data: ByteArray?) = emit(GattEvent.Telemetry(data))

        override fun onBlasterId(blasterType: Byte?) = emit(GattEvent.BlasterId(blasterType))

        private fun emit(event: GattEvent) {
            if (!_events.tryEmit(event)) {
                Log.w(TAG, "Event buffer full, dropped $event")
            }
        }
    }

    @VisibleForTesting
    internal fun getGattCallback(): BluetoothGattCallback = gattCallback

    /**
     * Test seam: replaces the connected GATT and the delay scope, and the event transport unless
     * [eventSink] is null (then events go to [events] as in the app).
     */
    @VisibleForTesting
    internal fun attachForTest(gatt: GattIo?, eventSink: GattEventSink?, scope: CoroutineScope) {
        this.gatt = gatt
        if (eventSink != null) this.eventSink = eventSink
        scopeOverride = scope
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    private val binder: IBinder = LocalBinder()

    fun initialize(): Boolean {
        if (bluetoothManager == null) {
            bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            if (bluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.")
                return false
            }
        }

        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }

        return true
    }

    fun connect(address: String?): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.")
            return false
        }

        try {
            val existing = gatt
            if (bluetoothDeviceAddress != null && address == bluetoothDeviceAddress && existing != null) {
                Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.")
                return if (existing.connect()) {
                    connectionState = STATE_CONNECTING
                    true
                } else {
                    false
                }
            }

            val device = adapter.getRemoteDevice(address)
            if (device == null) {
                Log.w(TAG, "Device not found.  Unable to connect.")
                return false
            }
            gatt = device.connectGatt(this, false, gattCallback)?.let { GattIo.of(it) }
            Log.d(TAG, "Trying to create a new connection.")
            bluetoothDeviceAddress = address
            connectionState = STATE_CONNECTING
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in connect: " + e.message)
            return false
        }
    }

    fun disconnect() {
        val g = gatt
        if (g == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in disconnect: " + e.message)
        }
    }

    fun close() {
        val g = gatt ?: return
        try {
            g.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in close: " + e.message)
        }
        gatt = null
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        val g = gatt
        if (g == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized")
            return
        }
        ProtocolTrace.read(characteristic.uuid)
        try {
            if (g.readCharacteristic(characteristic)) {
                Log.d(TAG, "readCharacteristic success")
            } else {
                Log.d(TAG, "readCharacteristic failed")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in readCharacteristic: " + e.message)
        }
    }

    @Suppress("DEPRECATION")
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?) {
        val g = gatt
        if (g == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized")
            return
        }
        ProtocolTrace.write(characteristic.uuid, characteristic.value, characteristic.writeType)
        try {
            if (g.writeCharacteristic(characteristic)) {
                Log.d(TAG, "writeCharacteristic success")
            } else {
                Log.w(TAG, "writeCharacteristic failed for UUID: " + characteristic.uuid)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in writeCharacteristic: " + e.message)
        }
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic?, value: ByteArray, writeType: Int) {
        val g = gatt
        if (g == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized")
            return
        }
        ProtocolTrace.write(characteristic.uuid, value, writeType)
        try {
            val success: Boolean
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                success = g.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.setValue(value)
                characteristic.writeType = writeType
                success = g.writeCharacteristic(characteristic)
            }
            if (success) {
                Log.d(TAG, "writeCharacteristic success")
            } else {
                Log.w(TAG, "writeCharacteristic failed for UUID: " + characteristic.uuid)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in writeCharacteristic: " + e.message)
        }
    }

    fun writeDescriptor(descriptor: BluetoothGattDescriptor?) {
        val g = gatt
        if (g == null || descriptor == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Descriptor not initialized")
            return
        }
        @Suppress("DEPRECATION")
        ProtocolTrace.descriptorWrite(descriptor.uuid, descriptor.value)
        try {
            if (g.writeDescriptor(descriptor)) {
                Log.d(TAG, "writeDescriptor success")
            } else {
                Log.d(TAG, "writeDescriptor failed")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in writeDescriptor: " + e.message)
        }
    }

    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic?, enabled: Boolean) {
        val g = gatt
        if (g == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized")
            return
        }
        try {
            g.setCharacteristicNotification(characteristic, false)
            g.setCharacteristicNotification(characteristic, enabled)

            if (UUID_RECOIL_TELEMETRY == characteristic.uuid) {
                val descriptor = characteristic.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))
                if (descriptor == null) {
                    scope.launch {
                        delay(CCCD_RETRY_DELAY_MILLIS)
                        val retryDesc = characteristic.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))
                        if (retryDesc != null) {
                            @Suppress("DEPRECATION")
                            retryDesc.setValue(notificationValue(enabled))
                            writeDescriptor(retryDesc)
                        } else {
                            Log.e(TAG, "Failed to find CCCD descriptor for telemetry characteristic after delay!")
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.setValue(notificationValue(enabled))
                    writeDescriptor(descriptor)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in setCharacteristicNotification: " + e.message)
        }
    }

    // Nullable like the Java original: no implicit null check on the framework constants.
    private fun notificationValue(enabled: Boolean): ByteArray? =
        if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

    open val supportedGattServices: List<BluetoothGattService>?
        get() = gatt?.services

    companion object {
        private val TAG: String = BluetoothLeService::class.java.simpleName
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "BluetoothLeServiceChannel"
        private const val CONNECTED_TEXT = "Connected to Blaster"

        /** At most this often; the system allows about 5 updates a second per app. */
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 250L

        /** About 25 s of telemetry; a full buffer means a stalled collector. */
        private const val EVENT_BUFFER = 256

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2

        /** §1.6: GATT connected → discoverServices() after this delay. */
        const val DISCOVER_SERVICES_DELAY_MILLIS = 500L

        /** §1.6: CCCD descriptor missing → retried once after this delay. */
        const val CCCD_RETRY_DELAY_MILLIS = 300L

        const val ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val TELEMETRY_DATA_AVAILABLE = "com.example.bluetooth.le.TELEMETRY_DATA_AVAILABLE"
        const val ID_DATA_AVAILABLE = "com.example.bluetooth.le.ID_DATA_AVAILABLE"
        const val CHARACTERISTIC_WRITE_FINISHED = "com.example.bluetooth.le.CHARACTERISTIC_WRITE_FINISHED"
        const val DESCRIPTOR_WRITE_FINISHED = "com.example.bluetooth.le.DESCRIPTOR_WRITE_FINISHED"

        @JvmField
        val UUID_RECOIL_TELEMETRY: UUID = UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID)

        @JvmField
        val UUID_RECOIL_ID: UUID = UUID.fromString(GattAttributes.RECOIL_ID_UUID)
    }
}
