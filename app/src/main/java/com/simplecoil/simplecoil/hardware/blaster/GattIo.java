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
package com.simplecoil.simplecoil.hardware.blaster;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.List;

/**
 * The BluetoothGatt operations BluetoothLeService uses, behind an interface so the GATT plumbing
 * can be tested on the JVM with a fake. Each method maps 1:1 onto the BluetoothGatt call of the
 * same name; nothing is added, queued or reordered.
 */
public interface GattIo {
    boolean connect();
    void disconnect();
    void close();
    boolean discoverServices();
    List<BluetoothGattService> getServices();
    boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enable);
    boolean readCharacteristic(BluetoothGattCharacteristic characteristic);
    /** Pre-API-33 write: value and write type are set on the characteristic beforehand. */
    boolean writeCharacteristic(BluetoothGattCharacteristic characteristic);
    /** API 33+ write. Returns a BluetoothStatusCodes value. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int writeType);
    boolean writeDescriptor(BluetoothGattDescriptor descriptor);

    /** Wraps a real BluetoothGatt. */
    static GattIo of(BluetoothGatt gatt) {
        return new GattIo() {
            @Override public boolean connect() { return gatt.connect(); }
            @Override public void disconnect() { gatt.disconnect(); }
            @Override public void close() { gatt.close(); }
            @Override public boolean discoverServices() { return gatt.discoverServices(); }
            @Override public List<BluetoothGattService> getServices() { return gatt.getServices(); }
            @Override public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enable) {
                return gatt.setCharacteristicNotification(characteristic, enable);
            }
            @Override public boolean readCharacteristic(BluetoothGattCharacteristic characteristic) {
                return gatt.readCharacteristic(characteristic);
            }
            @SuppressWarnings("deprecation")
            @Override public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
                return gatt.writeCharacteristic(characteristic);
            }
            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            @Override public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int writeType) {
                return gatt.writeCharacteristic(characteristic, value, writeType);
            }
            @SuppressWarnings("deprecation")
            @Override public boolean writeDescriptor(BluetoothGattDescriptor descriptor) {
                return gatt.writeDescriptor(descriptor);
            }
        };
    }
}
