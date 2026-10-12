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

import androidx.annotation.Nullable;

/**
 * Where BluetoothLeService reports GATT events. The app delivers them as package-scoped
 * broadcasts; tests record them.
 */
public interface GattEventSink {
    /**
     * A payload-less event: one of BluetoothLeService's ACTION_GATT_CONNECTED,
     * ACTION_GATT_DISCONNECTED, ACTION_GATT_SERVICES_DISCOVERED, CHARACTERISTIC_WRITE_FINISHED or
     * DESCRIPTOR_WRITE_FINISHED.
     */
    void onEvent(String action);

    /** TELEMETRY_DATA_AVAILABLE with the full notification payload. */
    void onTelemetry(@Nullable byte[] data);

    /** ID_DATA_AVAILABLE with byte 10 of the ID characteristic, or null when it is 10 bytes or shorter. */
    void onBlasterId(@Nullable Byte blasterType);
}
