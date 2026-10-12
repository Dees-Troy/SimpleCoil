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

/** GATT UUIDs of the Recoil blaster. Burned into the blaster firmware; never change them. */
object GattAttributes {
    // Generic
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"

    // RECOIL
    const val RECOIL_MAIN_SERVICE = "e6f59d10-8230-4a5c-b22f-c062b1d329e3"
    const val RECOIL_ID_UUID = "e6f59d11-8230-4a5c-b22f-c062b1d329e3"
    const val RECOIL_TELEMETRY_UUID = "e6f59d12-8230-4a5c-b22f-c062b1d329e3"
    const val RECOIL_COMMAND_UUID = "e6f59d13-8230-4a5c-b22f-c062b1d329e3"
    const val RECOIL_CONFIG_UUID = "e6f59d14-8230-4a5c-b22f-c062b1d329e3"
}
