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
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.simplecoil.simplecoil.hardware.blaster.GattEventSink
import com.simplecoil.simplecoil.hardware.blaster.GattIo
import com.simplecoil.simplecoil.hardware.blaster.ProtocolTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    }

    /**
     * The app starts this service with startForegroundService() right before binding and
     * connecting, so enter the foreground here rather than when GATT connects: a slow or failed
     * connectGatt would otherwise miss the deadline (ForegroundServiceDidNotStartInTimeException).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Connecting to Blaster")
        return super.onStartCommand(intent, flags, startId)
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

    private fun createPersistentNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, FullscreenActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SimpleCoil Connected")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    open fun startAsForeground(statusText: String) {
        startForeground(NOTIFICATION_ID, createPersistentNotification(statusText))
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothDeviceAddress: String? = null

    // Non-null only after connect() succeeded, which requires an initialized adapter.
    private var gatt: GattIo? = null
    private var eventSink: GattEventSink = BroadcastEventSink()

    /** Runs the protocol delays (§1.6) on the main thread, as the Handlers this replaced did. */
    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
                startAsForeground("Connected to Blaster")
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

    /** Delivers GATT events to the app as package-scoped broadcasts. */
    private inner class BroadcastEventSink : GattEventSink {
        override fun onEvent(action: String) {
            sendBroadcast(Intent(action))
        }

        override fun onTelemetry(data: ByteArray?) {
            val intent = Intent(TELEMETRY_DATA_AVAILABLE)
            intent.putExtra(EXTRA_DATA, data)
            sendBroadcast(intent)
        }

        override fun onBlasterId(blasterType: Byte?) {
            val intent = Intent(ID_DATA_AVAILABLE)
            if (blasterType != null) {
                intent.putExtra(EXTRA_DATA, blasterType)
            }
            sendBroadcast(intent)
        }
    }

    @VisibleForTesting
    internal fun getGattCallback(): BluetoothGattCallback = gattCallback

    /** Test seam: replaces the connected GATT, the event transport and the delay scope. */
    @VisibleForTesting
    internal fun attachForTest(gatt: GattIo?, eventSink: GattEventSink, scope: CoroutineScope) {
        this.gatt = gatt
        this.eventSink = eventSink
        this.scope = scope
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService = this@BluetoothLeService
    }

    override fun sendBroadcast(intent: Intent?) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(packageName)
        }
        super.sendBroadcast(intent)
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

    private fun notificationValue(enabled: Boolean): ByteArray =
        if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

    open val supportedGattServices: List<BluetoothGattService>?
        get() = gatt?.services

    companion object {
        private val TAG: String = BluetoothLeService::class.java.simpleName
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "BluetoothLeServiceChannel"

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
        const val EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA"

        @JvmField
        val UUID_RECOIL_TELEMETRY: UUID = UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID)

        @JvmField
        val UUID_RECOIL_ID: UUID = UUID.fromString(GattAttributes.RECOIL_ID_UUID)
    }
}
