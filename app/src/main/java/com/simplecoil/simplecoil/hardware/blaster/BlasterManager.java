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

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.simplecoil.simplecoil.BluetoothLeService;
import com.simplecoil.simplecoil.GattAttributes;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * Manages Bluetooth LE blaster connections, GATT characteristic writes,
 * battery level tracking, telemetry parsing, and hardware event dispatching.
 */
public class BlasterManager {
    private static final String TAG = "BlasterManager";

    private static volatile BlasterManager instance;

    private Context context;
    private BluetoothLeService bluetoothLeService;
    private String deviceAddress;

    private BluetoothGattCharacteristic telemetryCharacteristic;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic configCharacteristic;

    private byte commandId = 0x00;
    private byte weaponProfile = 0x00;
    private byte blasterType = GattProtocolHandler.BLASTER_TYPE_PISTOL;
    private boolean recoilEnabled = true;

    private byte lastTriggerCount = 0;
    private byte lastReloadButtonCount = 0;
    private byte lastThumbButtonCount = 0;
    private byte lastPowerButtonCount = 0;
    private byte lastTeam = 0;

    private final Queue<Byte> batteryQueue = new LinkedList<>();
    private long batteryTotal = 0;
    private int batteryCount = 0;

    private BlasterEventListener listener;
    private boolean isBound = false;
    private boolean isConnected = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothLeService.LocalBinder binder = (BluetoothLeService.LocalBinder) service;
            bluetoothLeService = binder.getService();
            if (bluetoothLeService.initialize()) {
                if (deviceAddress != null && !deviceAddress.isEmpty()) {
                    bluetoothLeService.connect(deviceAddress);
                }
            } else {
                Log.e(TAG, "Unable to initialize Bluetooth LE Service");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bluetoothLeService = null;
            isConnected = false;
            if (listener != null) {
                listener.onBlasterDisconnected();
            }
        }
    };

    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleGattIntent(intent);
        }
    };

    private BlasterManager() {}

    public static BlasterManager getInstance() {
        if (instance == null) {
            synchronized (BlasterManager.class) {
                if (instance == null) {
                    instance = new BlasterManager();
                }
            }
        }
        return instance;
    }

    public void initialize(Context context, BlasterEventListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setListener(BlasterEventListener listener) {
        this.listener = listener;
    }

    public void connect(String address) {
        this.deviceAddress = address;
        if (context == null) return;

        if (!isBound) {
            Intent gattServiceIntent = new Intent(context, BluetoothLeService.class);
            ContextCompat.startForegroundService(context, gattServiceIntent);
            context.bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
            isBound = true;
        } else if (bluetoothLeService != null) {
            bluetoothLeService.connect(address);
        }

        IntentFilter filter = makeGattUpdateIntentFilter();
        ContextCompat.registerReceiver(context, gattUpdateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    public void disconnect() {
        if (bluetoothLeService != null) {
            bluetoothLeService.disconnect();
        }
        isConnected = false;
    }

    public void close() {
        disconnect();
        if (context != null) {
            if (isBound) {
                try {
                    context.unbindService(serviceConnection);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Service not registered: " + e.getMessage());
                }
                isBound = false;
            }
            try {
                context.unregisterReceiver(gattUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Receiver not registered: " + e.getMessage());
            }
        }
        if (bluetoothLeService != null) {
            bluetoothLeService.close();
            bluetoothLeService = null;
        }
    }

    public boolean isConnected() { return isConnected; }
    public byte getBlasterType() { return blasterType; }

    /**
     * Blaster type reported by the ID characteristic. Used by FullscreenActivity, which consumes
     * the GATT broadcasts itself, so that rifle burst mode gets its reduced recoil (0x78).
     */
    public void setBlasterType(byte blasterType) { this.blasterType = blasterType; }
    public boolean isRecoilEnabled() { return recoilEnabled; }

    public void setBluetoothLeService(BluetoothLeService service) {
        this.bluetoothLeService = service;
        if (service != null) {
            discoverGattCharacteristics();
        }
    }

    public void setTeam(byte playerId) {
        if (bluetoothLeService == null || commandCharacteristic == null || playerId < 1) return;
        byte[] command = GattProtocolHandler.buildSetTeamCommand(commandId, playerId);
        commandId += GattProtocolHandler.COMMAND_ID_INCREMENT;
        bluetoothLeService.writeCharacteristic(commandCharacteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    public void startReload(byte playerId) {
        if (bluetoothLeService == null || commandCharacteristic == null) return;
        byte[] command = GattProtocolHandler.buildStartReloadCommand(commandId, playerId);
        commandId += GattProtocolHandler.COMMAND_ID_INCREMENT;
        bluetoothLeService.writeCharacteristic(commandCharacteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    public void finishReload(byte playerId, byte fullReloadCount) {
        if (bluetoothLeService == null || commandCharacteristic == null) return;
        byte[] command = GattProtocolHandler.buildFinishReloadCommand(commandId, playerId, weaponProfile, fullReloadCount);
        commandId += GattProtocolHandler.COMMAND_ID_INCREMENT;
        bluetoothLeService.writeCharacteristic(commandCharacteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    public void setShotMode(int shotMode, int firingMode) {
        if (bluetoothLeService == null || configCharacteristic == null) return;
        byte[] config = GattProtocolHandler.buildShotModeConfig(weaponProfile, shotMode, blasterType, firingMode);
        bluetoothLeService.writeCharacteristic(configCharacteristic, config, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    public void setShotMode(int shotMode) {
        setShotMode(shotMode, GameSettingsRepository.getInstance().getCurrentFiringMode());
    }

    public void setRecoil(boolean enabled) {
        this.recoilEnabled = enabled;
        if (bluetoothLeService == null || configCharacteristic == null) return;
        byte[] config = GattProtocolHandler.buildRecoilConfig(enabled);
        bluetoothLeService.writeCharacteristic(configCharacteristic, config, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
    }

    private void handleGattIntent(Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (action == null) return;

        if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
            isConnected = true;
            if (listener != null) listener.onBlasterConnected(deviceAddress);
        } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
            isConnected = false;
            if (listener != null) listener.onBlasterDisconnected();
        } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
            discoverGattCharacteristics();
            if (listener != null) listener.onServicesDiscovered();
        } else if (BluetoothLeService.TELEMETRY_DATA_AVAILABLE.equals(action)) {
            byte[] data = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA);
            byte[] telemetryData = (data != null) ? data : (telemetryCharacteristic != null ? telemetryCharacteristic.getValue() : null);
            if (telemetryData != null) {
                processTelemetryData(telemetryData);
            }
        } else if (BluetoothLeService.ID_DATA_AVAILABLE.equals(action)) {
            blasterType = intent.getByteExtra(BluetoothLeService.EXTRA_DATA, GattProtocolHandler.BLASTER_TYPE_PISTOL);
            if (listener != null) listener.onBlasterTypeDiscovered(blasterType);
        } else if (BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED.equals(action)) {
            if (listener != null) listener.onCharacteristicWriteFinished();
        }
    }

    public void discoverGattCharacteristics() {
        if (bluetoothLeService == null || bluetoothLeService.getSupportedGattServices() == null) return;

        for (BluetoothGattService gattService : bluetoothLeService.getSupportedGattServices()) {
            if (GattAttributes.RECOIL_MAIN_SERVICE.equalsIgnoreCase(gattService.getUuid().toString())) {
                telemetryCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID));
                if (telemetryCharacteristic != null) {
                    bluetoothLeService.setCharacteristicNotification(telemetryCharacteristic, true);
                }
                commandCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID));
                configCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID));

                BluetoothGattCharacteristic idCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_ID_UUID));
                if (idCharacteristic != null) {
                    bluetoothLeService.readCharacteristic(idCharacteristic);
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    setRecoil(recoilEnabled);
                }, 500);
            }
        }
    }

    private void processTelemetryData(byte[] data) {
        GattProtocolHandler.TelemetryData telemetry = GattProtocolHandler.parseTelemetry(data);
        if (telemetry == null) return;

        if (listener != null) listener.onTelemetryReceived(telemetry);

        if (telemetry.playerId != lastTeam) {
            lastTeam = telemetry.playerId;
            if (listener != null) listener.onPlayerIdChanged(telemetry.playerId);
        }

        if (telemetry.triggerCounter != lastTriggerCount) {
            lastTriggerCount = telemetry.triggerCounter;
            if (listener != null) listener.onTriggerPulled(telemetry.shotsRemaining);
        }

        if (telemetry.reloadCounter != lastReloadButtonCount) {
            lastReloadButtonCount = telemetry.reloadCounter;
            if (listener != null) listener.onReloadRequested();
        }

        if (telemetry.thumbCounter != lastThumbButtonCount) {
            lastThumbButtonCount = telemetry.thumbCounter;
            if (listener != null) listener.onShotModeChanged(0); // Cycle shot mode
        }

        if (telemetry.powerCounter != lastPowerButtonCount) {
            lastPowerButtonCount = telemetry.powerCounter;
            recoilEnabled = !recoilEnabled;
            if (listener != null) listener.onRecoilToggled(recoilEnabled);
        }

        // Battery calculation
        batteryQueue.add(telemetry.batteryLevel);
        batteryTotal += telemetry.batteryLevel;
        if (batteryCount >= 100) {
            Byte polled = batteryQueue.poll();
            if (polled != null) {
                batteryTotal -= polled;
            }
        } else {
            batteryCount++;
        }
        long averageBattery = batteryTotal / batteryCount;
        if (listener != null) listener.onBatteryLevelUpdated(averageBattery, blasterType);

        if (telemetry.hitByPlayer1 != 0) {
            if (listener != null) {
                listener.onHitReceived(telemetry.hitByPlayer1, telemetry.shotId1, false);
            }
        }
        if (telemetry.hitByPlayer2 != 0) {
            if (listener != null) {
                listener.onHitReceived(telemetry.hitByPlayer2, telemetry.shotId2, true);
            }
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        filter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        filter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        filter.addAction(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
        filter.addAction(BluetoothLeService.ID_DATA_AVAILABLE);
        filter.addAction(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED);
        return filter;
    }
}
