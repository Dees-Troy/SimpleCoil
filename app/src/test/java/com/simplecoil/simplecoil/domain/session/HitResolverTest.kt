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

import com.simplecoil.simplecoil.Globals
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY1
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY2
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_GRENADE_DAMAGE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_GRENADE_PAIR
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_HIT_BY_PLAYER_2
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of the hit handling extracted from FullscreenActivity.processTelemetryData().
 * These pin today's behavior, including known bugs, so the later engine extraction can't drift.
 */
class HitResolverTest {

    private var now = 10_000L
    private val resolver = HitResolver { now }

    /** Each network player is on its own team unless listed in [teams]; no grenade pairings or damage settings. */
    private class FakeLookups(
        val teams: Map<Byte, Int> = emptyMap(),
        val pairings: Map<Int, Int> = emptyMap(),
        val damage: Map<Byte, Int> = emptyMap(),
    ) : HitResolver.Lookups {
        override fun networkTeam(playerId: Byte) = teams[playerId] ?: (100 + playerId)
        override fun grenadePairing(grenadeId: Int) = pairings[grenadeId] ?: Globals.INVALID_PLAYER_ID
        override fun damage(playerId: Byte): Int? = damage[playerId]
    }

    private fun context(
        gameActive: Boolean = true,
        shielded: Boolean = false,
        useNetwork: Boolean = false,
        freeForAll: Boolean = false,
        myNetworkTeam: Int = 1,
        currentHealth: Int = 10,
    ) = HitResolver.Context(gameActive, shielded, useNetwork, freeForAll, myNetworkTeam, currentHealth)

    private fun resolve(packet: ByteArray, context: HitResolver.Context = context(), lookups: HitResolver.Lookups = FakeLookups()) =
        resolver.resolve(
            packet[RECOIL_OFFSET_HIT_BY1].toInt() and 0xFF, packet[RECOIL_OFFSET_HIT_BY1_SHOTID],
            packet[RECOIL_OFFSET_HIT_BY2].toInt() and 0xFF, packet[RECOIL_OFFSET_HIT_BY2_SHOTID],
            context, lookups,
        )

    private fun assertNoHit(result: HitResolver.Result) {
        assertFalse(result.countedHit)
        assertEquals(0, result.healthRemoved)
        assertEquals(0.toByte(), result.hitById)
        assertTrue(result.messages.isEmpty())
    }

    // --- local game -------------------------------------------------------------------------

    @Test
    fun localSingleHit_costsOneHitAndCreditsNobody() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2)
        assertTrue(r.countedHit)
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
        assertEquals(0.toByte(), r.hitById)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun noHit_whenSlot1Empty() {
        assertNoHit(resolve(GOLDEN_IDLE))
    }

    @Test
    fun slot2_ignoredWhenSlot1Empty() {
        assertNoHit(resolve(telemetry(RECOIL_OFFSET_HIT_BY2, 0x0C, RECOIL_OFFSET_HIT_BY2_SHOTID, 0x3E)))
    }

    @Test
    fun localDoubleHit_costsTwoHits() {
        val r = resolve(GOLDEN_DOUBLE_HIT)
        assertTrue(r.countedHit)
        assertEquals(2 * Globals.DAMAGE_PER_HIT, r.healthRemoved)
    }

    @Test
    fun slot2_skippedWhenSlot1AlreadyEliminates() {
        val r = resolve(GOLDEN_DOUBLE_HIT, context(currentHealth = 1))
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
    }

    @Test
    fun sameShotInBothSlotsOfOnePacket_countsTwice() {
        // De-dup only compares against the previous packet's slots.
        val r = resolve(telemetry(RECOIL_OFFSET_HIT_BY1, 0x04, RECOIL_OFFSET_HIT_BY1_SHOTID, 0x3B,
            RECOIL_OFFSET_HIT_BY2, 0x04, RECOIL_OFFSET_HIT_BY2_SHOTID, 0x3B))
        assertEquals(2 * Globals.DAMAGE_PER_HIT, r.healthRemoved)
    }

    // --- game state gating ------------------------------------------------------------------

    @Test
    fun inactiveOrShielded_ignoresHitWithoutRememberingIt() {
        assertNoHit(resolve(GOLDEN_HIT_BY_PLAYER_2, context(gameActive = false)))
        assertNoHit(resolve(GOLDEN_HIT_BY_PLAYER_2, context(shielded = true)))
        // Nothing was recorded for de-dup, so the same shot counts once the game is live.
        assertEquals(Globals.DAMAGE_PER_HIT, resolve(GOLDEN_HIT_BY_PLAYER_2).healthRemoved)
    }

    // --- de-dup -----------------------------------------------------------------------------

    @Test
    fun repeatedShot_isFilteredUntilAnIdlePacket() {
        assertEquals(Globals.DAMAGE_PER_HIT, resolve(GOLDEN_HIT_BY_PLAYER_2).healthRemoved)
        assertNoHit(resolve(GOLDEN_HIT_BY_PLAYER_2))
        assertNoHit(resolve(GOLDEN_IDLE))
        assertEquals(Globals.DAMAGE_PER_HIT, resolve(GOLDEN_HIT_BY_PLAYER_2).healthRemoved)
    }

    @Test
    fun dedup_comparesOnlyLowThreeBitsOfShotId() {
        resolve(GOLDEN_HIT_BY_PLAYER_2) // shot id byte 0x3D → shot id 5
        assertNoHit(resolve(telemetry(RECOIL_OFFSET_HIT_BY1, 0x08, RECOIL_OFFSET_HIT_BY1_SHOTID, 0x75)))
        assertEquals(Globals.DAMAGE_PER_HIT,
            resolve(telemetry(RECOIL_OFFSET_HIT_BY1, 0x08, RECOIL_OFFSET_HIT_BY1_SHOTID, 0x76)).healthRemoved)
    }

    @Test
    fun dedup_matchesAcrossSlots() {
        resolve(GOLDEN_DOUBLE_HIT) // slot 2: player 0x0C, shot id byte 0x3E → 6
        val r = resolve(telemetry(RECOIL_OFFSET_HIT_BY1, 0x0C, RECOIL_OFFSET_HIT_BY1_SHOTID, 0x3E))
        assertNoHit(r)
    }

    // --- grenades ---------------------------------------------------------------------------

    @Test
    fun grenadeDamage_costsOneHitAndIsNeverDeduplicated() {
        assertEquals(Globals.DAMAGE_PER_HIT, resolve(GOLDEN_GRENADE_DAMAGE).healthRemoved)
        assertEquals(Globals.DAMAGE_PER_HIT, resolve(GOLDEN_GRENADE_DAMAGE).healthRemoved)
    }

    @Test
    fun grenadeControlEvent_costsNothing() {
        assertNoHit(resolve(GOLDEN_GRENADE_PAIR))
    }

    // --- network game -----------------------------------------------------------------------

    @Test
    fun networkHit_creditsShooterAndSendsHit() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true))
        assertTrue(r.countedHit)
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
        assertEquals(2.toByte(), r.hitById)
        assertEquals(listOf(HitResolver.Message(out = false, playerId = 2)), r.messages)
    }

    @Test
    fun networkHit_takingHealthToZero_sendsOut() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true, currentHealth = 1))
        assertEquals(listOf(HitResolver.Message(out = true, playerId = 2)), r.messages)
    }

    @Test
    fun networkHit_usesShootersConfiguredDamage() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true), FakeLookups(damage = mapOf(2.toByte() to -3)))
        assertEquals(-3, r.healthRemoved)
    }

    @Test
    fun friendlyFire_isCountedButCostsNothingAndSendsNothing() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true, myNetworkTeam = 7), FakeLookups(teams = mapOf(2.toByte() to 7)))
        assertTrue(r.countedHit)
        assertEquals(0, r.healthRemoved)
        assertEquals(2.toByte(), r.hitById)
        assertTrue(r.messages.isEmpty())
    }

    @Test
    fun freeForAll_hasNoFriendlyFire() {
        val r = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true, freeForAll = true, myNetworkTeam = 7),
            FakeLookups(teams = mapOf(2.toByte() to 7)))
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
    }

    @Test
    fun networkGrenade_creditsPairedPlayerAndIgnoresFriendlyFire() {
        val lookups = FakeLookups(teams = mapOf(5.toByte() to 1), pairings = mapOf(3 to 5))
        val r = resolve(GOLDEN_GRENADE_DAMAGE, context(useNetwork = true, myNetworkTeam = 1), lookups)
        assertEquals(5.toByte(), r.hitById)
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
        assertEquals(listOf(HitResolver.Message(out = false, playerId = 5)), r.messages)
    }

    @Test
    fun networkGrenade_withoutPairing_creditsRawIrIdShiftedRight() {
        val r = resolve(GOLDEN_GRENADE_DAMAGE, context(useNetwork = true))
        assertEquals((167 shr 2).toByte(), r.hitById)
    }

    @Test
    fun hitMessages_areRateLimitedTo400Milliseconds() {
        assertEquals(1, resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true)).messages.size)
        resolve(GOLDEN_IDLE, context(useNetwork = true))
        now += 400
        val limited = resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true))
        assertEquals(Globals.DAMAGE_PER_HIT, limited.healthRemoved)
        assertTrue(limited.messages.isEmpty())
        resolve(GOLDEN_IDLE, context(useNetwork = true))
        now += 1
        assertEquals(1, resolve(GOLDEN_HIT_BY_PLAYER_2, context(useNetwork = true)).messages.size)
    }

    @Test
    fun networkDoubleHit_slot2CostsDefaultPlusConfiguredDamage() {
        // Characterization: slot 2 adds DAMAGE_PER_HIT and then the shooter's configured damage
        // on top, where slot 1 replaces the default with the configured value.
        val lookups = FakeLookups(damage = mapOf(1.toByte() to -1, 3.toByte() to -1))
        val r = resolve(GOLDEN_DOUBLE_HIT, context(useNetwork = true), lookups)
        assertEquals(-3, r.healthRemoved)
        // Only one HIT message per 400 ms window.
        assertEquals(1, r.messages.size)
    }

    /**
     * B1 (REFACTOR_DESIGN.md §1.7): slot 2's shooter comes from slot 2's hit-by byte. Slot 1 is
     * player 1 (0x04, a teammate), slot 2 is player 3 (0x0C, an enemy). The teammate's hit is
     * ignored and the enemy's costs one hit and is credited to player 3. Before the fix the slot-2
     * friendly-fire check ran against player 1, so this cost nothing and credited player 1.
     */
    @Test
    fun b1_slot2ShooterIsTakenFromSlot2() {
        val lookups = FakeLookups(teams = mapOf(1.toByte() to 7, 3.toByte() to 8))
        val r = resolve(GOLDEN_DOUBLE_HIT, context(useNetwork = true, myNetworkTeam = 7), lookups)
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
        assertEquals(3.toByte(), r.hitById)
        assertEquals(listOf(HitResolver.Message(out = false, playerId = 3)), r.messages)
    }

    @Test
    fun b1_teammateInSlot2IsIgnoredEvenWhenSlot1IsAnEnemy() {
        val lookups = FakeLookups(teams = mapOf(1.toByte() to 8, 3.toByte() to 7))
        val r = resolve(GOLDEN_DOUBLE_HIT, context(useNetwork = true, myNetworkTeam = 7), lookups)
        assertEquals(Globals.DAMAGE_PER_HIT, r.healthRemoved)
        assertEquals(3.toByte(), r.hitById)
    }
}
