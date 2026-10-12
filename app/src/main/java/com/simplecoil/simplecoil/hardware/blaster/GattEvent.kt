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

/**
 * What BluetoothLeService reports, delivered in-process through its [events][com.simplecoil.simplecoil.BluetoothLeService.events]
 * flow (D8). Same events and payloads the broadcasts carried.
 */
sealed interface GattEvent {
    /** A payload-less event, identified by one of BluetoothLeService's ACTION_* / *_FINISHED constants. */
    data class Action(val action: String) : GattEvent

    /** Full telemetry notification payload. */
    class Telemetry(val data: ByteArray?) : GattEvent

    /** Byte 10 of the ID characteristic, or null when the read was 10 bytes or shorter. */
    data class BlasterId(val blasterType: Byte?) : GattEvent
}
