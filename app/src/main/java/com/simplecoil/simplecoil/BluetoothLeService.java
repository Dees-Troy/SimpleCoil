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

package com.simplecoil.simplecoil;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;

import com.simplecoil.simplecoil.hardware.blaster.DelayScheduler;
import com.simplecoil.simplecoil.hardware.blaster.GattEventSink;
import com.simplecoil.simplecoil.hardware.blaster.GattIo;
import com.simplecoil.simplecoil.hardware.blaster.ProtocolTrace;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();
    private static final int NOTIFICATION_ID = 1337;
    private static final String CHANNEL_ID = "BluetoothLeServiceChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Bluetooth LE Connection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification createPersistentNotification(String contentText) {
        Intent notificationIntent = new Intent(this, FullscreenActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SimpleCoil Connected")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    public void startAsForeground(String statusText) {
        Notification notification = createPersistentNotification(statusText);
        startForeground(NOTIFICATION_ID, notification);
    }

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    // Non-null only after connect() succeeded, which requires an initialized adapter.
    private GattIo mGatt;
    private GattEventSink mEventSink = new BroadcastEventSink();
    private DelayScheduler mScheduler = DelayScheduler.mainLooper();
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String TELEMETRY_DATA_AVAILABLE =
            "com.example.bluetooth.le.TELEMETRY_DATA_AVAILABLE";
    public final static String ID_DATA_AVAILABLE =
            "com.example.bluetooth.le.ID_DATA_AVAILABLE";
    public final static String CHARACTERISTIC_WRITE_FINISHED =
            "com.example.bluetooth.le.CHARACTERISTIC_WRITE_FINISHED";
    public final static String DESCRIPTOR_WRITE_FINISHED =
            "com.example.bluetooth.le.DESCRIPTOR_WRITE_FINISHED";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    public final static UUID UUID_RECOIL_TELEMETRY =
            UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID);
    public final static UUID UUID_RECOIL_ID =
            UUID.fromString(GattAttributes.RECOIL_ID_UUID);

    // Implements callback methods for GATT events that the app cares about.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange status=" + status + ", newState=" + newState);
            ProtocolTrace.event("connectionState status=" + status + " newState=" + newState);
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                startAsForeground("Connected to Blaster");
                // Attempts to discover services after successful connection with delay.
                mScheduler.postDelayed(() -> {
                    if (mGatt != null) {
                        ProtocolTrace.event("discoverServices");
                        Log.i(TAG, "Attempting to start service discovery after delay:" +
                                mGatt.discoverServices());
                    }
                }, 500);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server: " + status);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered status=" + status);
            ProtocolTrace.event("servicesDiscovered status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "read success!");
                broadcastUpdate(characteristic, characteristic.getValue());
            } else {
                Log.d(TAG, "read failed");
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         byte[] value,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "read success!");
                broadcastUpdate(characteristic, value);
            } else {
                Log.d(TAG, "read failed");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "write success!");
            } else {
                Log.d(TAG, "write failed");
            }
            ProtocolTrace.event("writeFinished status=" + status);
            broadcastUpdate(CHARACTERISTIC_WRITE_FINISHED);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            broadcastUpdate(characteristic, characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            broadcastUpdate(characteristic, value);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                          BluetoothGattDescriptor descriptor,
                                          int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "descriptor write success!");
            } else {
                Log.d(TAG, "descriptor write failed");
            }
            ProtocolTrace.event("descriptorWriteFinished status=" + status);
            broadcastUpdate(DESCRIPTOR_WRITE_FINISHED);
        }
    };

    private void broadcastUpdate(final String action) {
        mEventSink.onEvent(action);
    }

    private void broadcastUpdate(final BluetoothGattCharacteristic characteristic, final byte[] value) {
        ProtocolTrace.receive(characteristic.getUuid(), value);
        if (UUID_RECOIL_TELEMETRY.equals((characteristic.getUuid()))) {
            mEventSink.onTelemetry(value);
        } else if (UUID_RECOIL_ID.equals((characteristic.getUuid()))) {
            int firmwareVer = (characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) << 8) + characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1);
            Log.d(TAG, "Firmware version: " + firmwareVer);
            final byte[] data = value != null ? value : characteristic.getValue();
            mEventSink.onBlasterId(data != null && data.length > 10 ? data[10] : null);
        } else {
            Log.e(TAG, "unexpected characteristic data from " + characteristic.getUuid().toString());
        }
    }

    /** Delivers GATT events to the app as package-scoped broadcasts. */
    private class BroadcastEventSink implements GattEventSink {
        @Override
        public void onEvent(String action) {
            final Intent intent = new Intent(action);
            sendBroadcast(intent);
        }

        @Override
        public void onTelemetry(@Nullable byte[] data) {
            final Intent intent = new Intent(TELEMETRY_DATA_AVAILABLE);
            intent.putExtra(EXTRA_DATA, data);
            sendBroadcast(intent);
        }

        @Override
        public void onBlasterId(@Nullable Byte blasterType) {
            final Intent intent = new Intent(ID_DATA_AVAILABLE);
            if (blasterType != null) {
                intent.putExtra(EXTRA_DATA, (byte) blasterType);
            }
            sendBroadcast(intent);
        }
    }

    @VisibleForTesting
    BluetoothGattCallback getGattCallback() {
        return mGattCallback;
    }

    /** Test seam: replaces the connected GATT, the event transport and the delay scheduler. */
    @VisibleForTesting
    void attachForTest(@Nullable GattIo gatt, GattEventSink eventSink, DelayScheduler scheduler) {
        mGatt = gatt;
        mEventSink = eventSink;
        mScheduler = scheduler;
    }

    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public void sendBroadcast(Intent intent) {
        if (intent != null && intent.getPackage() == null) {
            intent.setPackage(getPackageName());
        }
        super.sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public boolean initialize() {
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        try {
            if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                    && mGatt != null) {
                Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
                if (mGatt.connect()) {
                    mConnectionState = STATE_CONNECTING;
                    return true;
                } else {
                    return false;
                }
            }

            final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
            if (device == null) {
                Log.w(TAG, "Device not found.  Unable to connect.");
                return false;
            }
            BluetoothGatt gatt = device.connectGatt(this, false, mGattCallback);
            mGatt = gatt != null ? GattIo.of(gatt) : null;
            Log.d(TAG, "Trying to create a new connection.");
            mBluetoothDeviceAddress = address;
            mConnectionState = STATE_CONNECTING;
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in connect: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        if (mGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        try {
            mGatt.disconnect();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in disconnect: " + e.getMessage());
        }
    }

    public void close() {
        if (mGatt == null) {
            return;
        }
        try {
            mGatt.close();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in close: " + e.getMessage());
        }
        mGatt = null;
    }

    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized");
            return;
        }
        ProtocolTrace.read(characteristic.getUuid());
        try {
            if (mGatt.readCharacteristic(characteristic)) {
                Log.d(TAG, "readCharacteristic success");
            } else {
                Log.d(TAG, "readCharacteristic failed");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in readCharacteristic: " + e.getMessage());
        }
    }

    public void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized");
            return;
        }
        ProtocolTrace.write(characteristic.getUuid(), characteristic.getValue(), characteristic.getWriteType());
        try {
            if (mGatt.writeCharacteristic(characteristic)) {
                Log.d(TAG, "writeCharacteristic success");
            } else {
                Log.w(TAG, "writeCharacteristic failed for UUID: " + characteristic.getUuid());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in writeCharacteristic: " + e.getMessage());
        }
    }

    public void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value, int writeType) {
        if (mGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized");
            return;
        }
        ProtocolTrace.write(characteristic.getUuid(), value, writeType);
        try {
            boolean success;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                success = mGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setValue(value);
                characteristic.setWriteType(writeType);
                success = mGatt.writeCharacteristic(characteristic);
            }
            if (success) {
                Log.d(TAG, "writeCharacteristic success");
            } else {
                Log.w(TAG, "writeCharacteristic failed for UUID: " + characteristic.getUuid());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in writeCharacteristic: " + e.getMessage());
        }
    }

    public void writeDescriptor(BluetoothGattDescriptor descriptor) {
        if (mGatt == null || descriptor == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Descriptor not initialized");
            return;
        }
        ProtocolTrace.descriptorWrite(descriptor.getUuid(), descriptor.getValue());
        try {
            if (mGatt.writeDescriptor(descriptor)) {
                Log.d(TAG, "writeDescriptor success");
            } else {
                Log.d(TAG, "writeDescriptor failed");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in writeDescriptor: " + e.getMessage());
        }
    }

    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter/Gatt/Characteristic not initialized");
            return;
        }
        try {
            mGatt.setCharacteristicNotification(characteristic, false);
            mGatt.setCharacteristicNotification(characteristic, enabled);

            if (UUID_RECOIL_TELEMETRY.equals(characteristic.getUuid())) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                        UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
                if (descriptor == null) {
                    mScheduler.postDelayed(() -> {
                        BluetoothGattDescriptor retryDesc = characteristic.getDescriptor(
                                UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
                        if (retryDesc != null) {
                            retryDesc.setValue(enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                            writeDescriptor(retryDesc);
                        } else {
                            Log.e(TAG, "Failed to find CCCD descriptor for telemetry characteristic after delay!");
                        }
                    }, 300);
                } else {
                    descriptor.setValue(enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                    writeDescriptor(descriptor);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in setCharacteristicNotification: " + e.getMessage());
        }
    }

    public List<BluetoothGattService> getSupportedGattServices() {
        if (mGatt == null) return null;
        return mGatt.getServices();
    }
}
