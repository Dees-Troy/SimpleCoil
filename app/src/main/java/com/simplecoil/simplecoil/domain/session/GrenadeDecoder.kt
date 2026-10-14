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

package com.simplecoil.simplecoil.domain.session

import com.simplecoil.simplecoil.data.repository.PlayerRepository

/**
 * Decodes the grenade fields packed into a telemetry hit slot. When the hit-by byte is
 * [PlayerRepository.GRENADE_PLAYER_ID] (167), the shot-id byte carries the grenade id in its high nibble
 * and the event type in its low nibble.
 *
 * Extracted verbatim from FullscreenActivity.processTelemetryData().
 */
object GrenadeDecoder {
    const val GRENADE_DAMAGE: Byte = 0x01
    const val GRENADE_NEW_PAIR: Byte = 0x0E
    const val GRENADE_PAIR_ID: Byte = 0x0F
    const val GRENADE_DISARM: Byte = 0x0D

    /** What a grenade control event asks the player's pairing state to do. */
    enum class PairingAction { NONE, PAIR, UNPAIR }

    /** Event type: low nibble of the shot-id byte. */
    @JvmStatic
    fun event(shotIdByte: Byte): Int = shotIdByte.toInt() and 0x0F

    /** Grenade id: high nibble of the shot-id byte. */
    @JvmStatic
    fun grenadeId(shotIdByte: Byte): Int = (shotIdByte.toInt() and 0xF0) shr 4

    /** A grenade slot that is not a damage event (pair, new-pair, disarm, ...). It must not cost health. */
    @JvmStatic
    fun isGrenadeControlEvent(hitBy: Int, shotIdByte: Byte): Boolean =
        hitBy == PlayerRepository.GRENADE_PLAYER_ID && event(shotIdByte) != GRENADE_DAMAGE.toInt()

    @JvmStatic
    fun pairingAction(hitBy: Int, shotIdByte: Byte): PairingAction {
        if (!isGrenadeControlEvent(hitBy, shotIdByte)) return PairingAction.NONE
        return when (event(shotIdByte)) {
            GRENADE_PAIR_ID.toInt() -> PairingAction.PAIR
            GRENADE_NEW_PAIR.toInt(), GRENADE_DISARM.toInt() -> PairingAction.UNPAIR
            else -> PairingAction.NONE
        }
    }
}
