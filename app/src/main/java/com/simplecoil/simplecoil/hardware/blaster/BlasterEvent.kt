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
 * Everything BlasterManager reports, in one ordered stream. A single stream keeps the relative
 * order of ServicesDiscovered → Telemetry → WriteFinished that the reload sequence depends on.
 */
sealed interface BlasterEvent {
    data class Connected(val address: String?) : BlasterEvent
    data object Disconnected : BlasterEvent
    data object ServicesDiscovered : BlasterEvent

    /** From the ID characteristic; pistol when the blaster didn't report a type. */
    data class TypeDiscovered(val blasterType: Byte) : BlasterEvent
    data object WriteFinished : BlasterEvent

    /** Every parsed telemetry packet, before the edge events derived from it. */
    data class Telemetry(val data: GattProtocolHandler.TelemetryData) : BlasterEvent
    data class PlayerIdChanged(val playerId: Byte) : BlasterEvent
    data class TriggerPulled(val shotsRemaining: Byte) : BlasterEvent
    data object ReloadPressed : BlasterEvent
    data object ThumbPressed : BlasterEvent
    data class RecoilToggled(val enabled: Boolean) : BlasterEvent
    data class BatteryLevel(val average: Long, val blasterType: Byte) : BlasterEvent

    /** A non-empty hit slot. [secondary] is slot 2. Not de-duplicated. */
    data class Hit(val shooter: Int, val shotId: Byte, val secondary: Boolean) : BlasterEvent
}
