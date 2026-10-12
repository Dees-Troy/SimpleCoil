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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The BluetoothGatt operations BluetoothLeService uses, behind an interface so the GATT plumbing
 * can be tested on the JVM with a fake. Each method maps 1:1 onto the BluetoothGatt call of the
 * same name; nothing is added, queued or reordered.
 */
interface GattIo {
    fun connect(): Boolean
    fun disconnect()
    fun close()
    fun discoverServices(): Boolean
    val services: List<BluetoothGattService>?
    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean): Boolean
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean

    /** Pre-API-33 write: value and write type are set on the characteristic beforehand. */
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean

    /** API 33+ write. Returns a BluetoothStatusCodes value. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int

    fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean

    companion object {
        /**
         * Wraps a real BluetoothGatt. The BLUETOOTH_CONNECT check happens before a connect is
         * started (BlePermissionController, BleConnectionManager), not on each delegated call.
         */
        @SuppressLint("MissingPermission")
        @JvmStatic
        fun of(gatt: BluetoothGatt): GattIo = object : GattIo {
            override fun connect() = gatt.connect()
            override fun disconnect() = gatt.disconnect()
            override fun close() = gatt.close()
            override fun discoverServices() = gatt.discoverServices()
            override val services: List<BluetoothGattService>? get() = gatt.services
            override fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean) =
                gatt.setCharacteristicNotification(characteristic, enable)
            override fun readCharacteristic(characteristic: BluetoothGattCharacteristic) = gatt.readCharacteristic(characteristic)

            @Suppress("DEPRECATION")
            override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic) = gatt.writeCharacteristic(characteristic)

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int) =
                gatt.writeCharacteristic(characteristic, value, writeType)

            @Suppress("DEPRECATION")
            override fun writeDescriptor(descriptor: BluetoothGattDescriptor) = gatt.writeDescriptor(descriptor)
        }
    }
}
