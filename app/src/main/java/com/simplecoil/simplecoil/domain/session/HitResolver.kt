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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository

/**
 * Turns the two hit slots of a telemetry packet into the damage to apply, the shooter to credit
 * and the HIT/OUT/SHIELDHIT network message to send. Owns the cross-packet state: the (player, shot id)
 * de-dup memory for both slots and the 400 ms hit-message rate limit.
 *
 * Extracted verbatim from FullscreenActivity.processTelemetryData(). Grenade pairing side effects
 * stay with the caller ([GrenadeDecoder.pairingAction]).
 */
class HitResolver @JvmOverloads constructor(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Values read from the session for one packet. */
    class Context(
        /** Game state is not NONE. */
        val gameActive: Boolean,
        val shielded: Boolean,
        val useNetwork: Boolean,
        val freeForAll: Boolean,
        val myNetworkTeam: Int,
        val currentHealth: Int,
    )

    interface Lookups {
        fun networkTeam(playerId: Byte): Int

        /** Network player paired with a grenade id, or [PlayerRepository.INVALID_PLAYER_ID]. */
        fun grenadePairing(grenadeId: Int): Int

        /** Configured damage of a network player, or null when that player has no settings. */
        fun damage(playerId: Byte): Int?
    }

    enum class Kind {
        HIT,

        /** The hit takes health to 0 or below. */
        OUT,

        /** The respawn shield blocked the hit. */
        SHIELDED,
    }

    /** A HIT, OUT or SHIELDHIT message crediting [playerId]. */
    data class Message(val kind: Kind, val playerId: Byte)

    class Result(
        /** Slot 1 registered a (non grenade-control) hit; counts toward hits taken. */
        val countedHit: Boolean,
        /** Health delta to apply. Damage values are negative. */
        val healthRemoved: Int,
        /** Shooter to credit, 0 when unknown (local game). */
        val hitById: Byte,
        val messages: List<Message>,
    )

    private var lastPlayerId1 = 0
    private var lastShotId1: Byte = 0
    private var lastPlayerId2 = 0
    private var lastShotId2: Byte = 0
    private var lastHitMessage = 0L

    fun resolve(hitBy1: Int, shotIdByte1: Byte, hitBy2: Int, shotIdByte2: Byte, context: Context, lookups: Lookups): Result {
        if (hitBy1 == 0) {
            lastPlayerId1 = PlayerRepository.INVALID_PLAYER_ID
            lastPlayerId2 = PlayerRepository.INVALID_PLAYER_ID
            return NO_HIT
        }
        // In a network game a shielded hit still goes through, so the shooter hears it was shielded
        if (!context.gameActive || (context.shielded && !context.useNetwork)) return NO_HIT

        var healthRemoved = 0
        var hitById: Byte = 0
        var countedHit = false
        val messages = ArrayList<Message>(1)
        // Only the right-most 3 bits make up the shot ID
        val shotId1 = (shotIdByte1.toInt() and 0x07).toByte()
        val shotId2 = (shotIdByte2.toInt() and 0x07).toByte()

        var filter = (lastPlayerId1 == hitBy1 && lastShotId1 == shotId1) || (lastPlayerId2 == hitBy1 && lastShotId2 == shotId1)
        if (!filter) {
            // Ignore non-damage events from grenades
            if (!GrenadeDecoder.isGrenadeControlEvent(hitBy1, shotIdByte1)) {
                countedHit = true
                healthRemoved = GameSettingsRepository.DAMAGE_PER_HIT
                if (context.useNetwork) {
                    hitById = (hitBy1 shr 2).toByte()
                    if (hitBy1 != PlayerRepository.GRENADE_PLAYER_ID && !context.freeForAll && lookups.networkTeam(hitById) == context.myNetworkTeam) {
                        // friendly fire ignored
                        healthRemoved = 0
                    } else {
                        if (hitBy1 == PlayerRepository.GRENADE_PLAYER_ID) {
                            hitById = grenadeShooter(shotIdByte1, hitById, lookups)
                        }
                        lookups.damage(hitById)?.let { healthRemoved = it }
                        rateLimitedMessage(context.currentHealth + healthRemoved, hitById, context.shielded, messages)
                    }
                }
            }
        }
        filter = (lastPlayerId1 == hitBy2 && lastShotId1 == shotId2) || (lastPlayerId2 == hitBy2 && lastShotId2 == shotId2) ||
            // Slot 2 repeating slot 1's shot in the same packet. Grenades are exempt, as in the cross-packet de-dup.
            (hitBy2 == hitBy1 && shotId2 == shotId1 && hitBy1 != PlayerRepository.GRENADE_PLAYER_ID)
        if (!filter && hitBy2 != 0 && context.currentHealth + healthRemoved > 0) {
            // Ignore non-damage events from grenades
            if (!GrenadeDecoder.isGrenadeControlEvent(hitBy2, shotIdByte2)) {
                healthRemoved += GameSettingsRepository.DAMAGE_PER_HIT
                if (context.useNetwork) {
                    hitById = (hitBy2 shr 2).toByte()
                    if (hitBy2 != PlayerRepository.GRENADE_PLAYER_ID && !context.freeForAll && lookups.networkTeam(hitById) == context.myNetworkTeam) {
                        // friendly fire ignored
                        healthRemoved -= GameSettingsRepository.DAMAGE_PER_HIT
                    } else {
                        if (hitBy2 == PlayerRepository.GRENADE_PLAYER_ID) {
                            hitById = grenadeShooter(shotIdByte2, hitById, lookups)
                        }
                        // The shooter's configured damage replaces the default, as for slot 1
                        lookups.damage(hitById)?.let { healthRemoved += it - GameSettingsRepository.DAMAGE_PER_HIT }
                        rateLimitedMessage(context.currentHealth + healthRemoved, hitById, context.shielded, messages)
                    }
                }
            }
        }
        if (hitBy1 != PlayerRepository.GRENADE_PLAYER_ID) {
            lastPlayerId1 = hitBy1
            lastShotId1 = shotId1
        } else {
            lastPlayerId1 = PlayerRepository.INVALID_PLAYER_ID
        }
        if (hitBy2 > 0 && hitBy2 != PlayerRepository.GRENADE_PLAYER_ID) {
            lastPlayerId2 = hitBy2
            lastShotId2 = shotId2
        } else {
            lastPlayerId2 = PlayerRepository.INVALID_PLAYER_ID
        }
        // Shielded: the shooter is told, but the hit costs nothing and isn't counted
        if (context.shielded) return Result(false, 0, hitById, messages)
        return Result(countedHit, healthRemoved, hitById, messages)
    }

    private fun grenadeShooter(shotIdByte: Byte, hitById: Byte, lookups: Lookups): Byte {
        val grenadeId = GrenadeDecoder.grenadeId(shotIdByte)
        if (grenadeId != 0) {
            val pairedPlayer = lookups.grenadePairing(grenadeId)
            if (pairedPlayer != PlayerRepository.INVALID_PLAYER_ID) return pairedPlayer.toByte()
        }
        return hitById
    }

    private fun rateLimitedMessage(healthAfter: Int, hitById: Byte, shielded: Boolean, messages: MutableList<Message>) {
        if (lastHitMessage < clock()) {
            lastHitMessage = clock() + HIT_MESSAGE_INTERVAL_MILLISECONDS
            val kind = when {
                shielded -> Kind.SHIELDED
                healthAfter <= 0 -> Kind.OUT
                else -> Kind.HIT
            }
            messages.add(Message(kind, hitById))
        }
    }

    companion object {
        /** Matches the activity's HIT_ANIMATION_DURATION_MILLISECONDS, which it reused as the rate limit. */
        const val HIT_MESSAGE_INTERVAL_MILLISECONDS = 400L

        private val NO_HIT = Result(false, 0, 0, emptyList())
    }
}
