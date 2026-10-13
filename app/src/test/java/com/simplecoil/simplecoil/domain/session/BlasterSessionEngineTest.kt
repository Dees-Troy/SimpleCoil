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
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.domain.session.SessionTestHarness.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session logic moved out of FullscreenActivity, in virtual time: the §1.6 delays it owns,
 * and per state transition the blaster commands and network messages it produces.
 */
class BlasterSessionEngineTest {

    private val h = SessionTestHarness()
    private val single = Globals.SHOT_MODE_SINGLE
    private val burst = Globals.SHOT_MODE_BURST
    private val auto = Globals.SHOT_MODE_FULL_AUTO

    // --------------------------------------------------------------------------------------
    // First packet of a connection (§1.6)
    // --------------------------------------------------------------------------------------

    @Test
    fun firstPacket_shotModeNow_teamAt250_recoilAt500() {
        h.engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        h.telemetry(Packet())
        assertEquals(listOf("shotMode:$single/0"), h.rec.ble)
        assertTrue(h.effects.contains(SessionEffect.CommunicationStarted))
        assertTrue(h.engine.isCommunicating)
        assertTrue(h.engine.hasConnectedToBlaster)

        h.advance(249)
        assertEquals(1, h.rec.ble.size)
        h.advance(1)
        assertEquals(listOf("shotMode:$single/0", "team:5"), h.rec.ble)
        assertTrue(h.effects.contains(SessionEffect.TeamSent))
        h.advance(249)
        assertEquals(2, h.rec.ble.size)
        h.advance(1)
        assertEquals(listOf("shotMode:$single/0", "team:5", "recoil:true"), h.rec.ble)
    }

    @Test
    fun firstPacket_withoutPlayerId_sendsNoTeam() {
        h.config.playerId = 0
        h.engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        h.telemetry(Packet().player(0))
        h.advance(1_000)
        assertEquals(listOf("shotMode:$single/0", "recoil:true"), h.rec.ble)
    }

    @Test
    fun firstPacket_onlyOncePerConnection_andAgainAfterReconnect() {
        h.connected()
        h.telemetry(Packet())
        h.advance(1_000)
        assertTrue(h.rec.ble.isEmpty())
        h.engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        h.telemetry(Packet())
        h.advance(1_000)
        assertEquals(listOf("shotMode:$single/0", "team:5", "recoil:true"), h.rec.ble)
    }

    @Test
    fun firstPacket_rebaselinesCounters() {
        h.connected(Packet().trigger(7).reload(3).thumb(9).power(4))
        h.telemetry(Packet().trigger(7).reload(3).thumb(9).power(4))
        assertTrue(h.rec.ble.isEmpty())
        assertTrue(h.sounds().isEmpty())
    }

    // --------------------------------------------------------------------------------------
    // Buttons
    // --------------------------------------------------------------------------------------

    @Test
    fun thumb_cyclesShotModeAndWritesConfig() {
        h.connected()
        h.telemetry(Packet().thumb(1))
        h.telemetry(Packet().thumb(2))
        h.telemetry(Packet().thumb(3))
        assertEquals(listOf("shotMode:$burst/0", "shotMode:$auto/0", "shotMode:$single/0"), h.rec.ble)
        assertEquals(single, h.state.shotMode)
        assertEquals(listOf("shotMode:$burst", "shotMode:$auto", "shotMode:$single"), h.rec.saved)
    }

    @Test
    fun power_togglesRecoil_unlessServerForcesIt() {
        h.connected()
        h.telemetry(Packet().power(1))
        assertEquals(listOf("recoil:false"), h.rec.ble)
        assertFalse(h.state.recoilEnabled)
        h.config.serverRecoilSetting = Globals.RECOIL_SETTING_DISABLED
        h.telemetry(Packet().power(2))
        assertEquals(listOf("recoil:false", "recoil:false"), h.rec.ble)
        assertEquals(listOf(SessionToast.RECOIL_FORCED_DISABLED), h.toasts())
    }

    @Test
    fun emptyTrigger_whenNotRunning_playsEmptyAndNeverAutoReloads() {
        h.connected()
        repeat(5) { h.telemetry(Packet().trigger(it + 1)) }
        assertEquals(List(5) { SessionSound.EMPTY }, h.sounds())
        assertTrue(h.rec.ble.isEmpty())
    }

    @Test
    fun emptyTrigger_threeTimesWhileRunning_autoReloads() {
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(0))
        h.rec.ble.clear()
        h.telemetry(Packet().shots(0).trigger(1))
        h.telemetry(Packet().shots(0).trigger(2))
        assertTrue(h.rec.ble.isEmpty())
        h.telemetry(Packet().shots(0).trigger(3))
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(true, h.state.reloadBarShown)
    }

    // --------------------------------------------------------------------------------------
    // Reload (§1.6: stage 1 → write finished → reload time → stage 2 → write finished)
    // --------------------------------------------------------------------------------------

    @Test
    fun reloadButton_twoStageReloadWithReloadTimeBetweenStages() {
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(12))
        h.rec.ble.clear()
        h.effects.clear()

        h.telemetry(Packet().shots(12).reload(1))
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(true, h.state.reloadBarShown)

        h.writeFinished()
        h.advance(1_499)
        assertEquals(1, h.rec.ble.size)
        h.advance(1)
        assertEquals(listOf("startReload:5", "finishReload:5/30"), h.rec.ble)
        assertEquals(30.toByte(), h.state.shotsRemaining)

        h.writeFinished()
        assertEquals(false, h.state.reloadBarShown)
        assertEquals(listOf(SessionSound.RELOAD), h.sounds())
    }

    @Test
    fun reloadButton_withFullMagazine_doesNothing() {
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(30).reload(1))
        assertTrue(h.rec.ble.isEmpty())
    }

    @Test
    fun reloadOnEmpty_reloadsWhenLastShotIsFired_withoutReloadSound() {
        h.config.reloadOnEmpty = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(1))
        h.rec.ble.clear()
        h.telemetry(Packet().shots(0))
        assertEquals(listOf("startReload:5"), h.rec.ble)
        h.writeFinished()
        h.advance(1_500)
        h.writeFinished()
        assertFalse(h.sounds().contains(SessionSound.RELOAD))
    }

    @Test
    fun shotsFired_playShotSoundAndRateLimitShotFiredTo400ms() {
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(29))
        h.telemetry(Packet().shots(28))
        h.advance(400)
        h.telemetry(Packet().shots(27))
        h.advance(1)
        h.telemetry(Packet().shots(26))
        assertEquals(List(4) { SessionSound.SHOT }, h.sounds())
        assertEquals(listOf("SHOTFIRED", "SHOTFIRED"), h.rec.net)
    }

    // --------------------------------------------------------------------------------------
    // Game lifecycle
    // --------------------------------------------------------------------------------------

    @Test
    fun startGame_spawnCountdownTicksLikeCountDownTimer() {
        h.connected()
        h.engine.startGame()
        assertEquals(Globals.GAME_STATE_ELIMINATED, h.config.gameState)
        assertEquals(listOf("shotMode:$single/0"), h.rec.ble) // reload is already locked: no stage-1 write
        assertEquals(SpawnPhase.Spawning(5, "", true), h.state.spawn)

        h.runCurrent()
        val seconds = mutableListOf<Long>()
        repeat(5_000) {
            (h.state.spawn as? SpawnPhase.Spawning)?.let { if (seconds.lastOrNull() != it.seconds) seconds += it.seconds }
            h.advance(1)
        }
        // CountDownTimer(5000, 999): ticks at 0, 999, ... 4995 with millisLeft/1000.
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L, 0L), seconds)
        assertEquals(6, h.sounds().count { it == SessionSound.BEEP })
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(Globals.GAME_STATE_RUNNING, h.config.gameState)
        assertEquals(SessionSound.SPAWN, h.sounds().last())
        assertEquals(listOf("shotMode:$single/0", "finishReload:5/30"), h.rec.ble)
        assertEquals(GameClock.Chronometer(5_000 + 1_000), h.state.clock)
    }

    @Test
    fun startGame_usesStartCountdownSetting_andRespawnUsesRespawnSetting() {
        h.config.startCountdownSeconds = 8
        h.config.respawnTimeSeconds = 12
        h.config.fullHealth = 1
        h.config.names[2] = "Bob"
        h.rec.useNetwork = true
        h.connected()

        h.engine.startGame()
        assertEquals(SpawnPhase.Spawning(8, "", true), h.state.spawn)
        h.advance(8_000)
        h.writeFinished()
        assertEquals(SpawnPhase.None, h.state.spawn)

        // Eliminate player to trigger respawn
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(SpawnPhase.Spawning(12, "Bob", false), h.state.spawn)
    }

    @Test
    fun startGame_withTimeLimit_countsDownAndRequestsEnd() {
        h.config.gameLimit = Globals.GAME_LIMIT_TIME
        h.config.timeLimitMinutes = 1
        h.connected()
        h.startedAndSpawned()
        assertEquals(GameClock.Countdown("01:00"), h.state.clock)
        h.advance(1_000)
        assertEquals(GameClock.Countdown("00:59"), h.state.clock)
        h.advance(58_999)
        assertTrue(h.effects.none { it == SessionEffect.EndGameRequested })
        h.advance(1)
        assertTrue(h.toasts().contains(SessionToast.GAME_TIME_EXPIRED))
        assertEquals(SessionEffect.EndGameRequested, h.effects.last())
    }

    @Test
    fun networkTimeLimit_serverEndsGame_andLocalFallbackAfter3s() {
        h.rec.useNetwork = true
        h.config.gameLimit = Globals.GAME_LIMIT_TIME
        h.config.timeLimitMinutes = 1
        h.connected()
        h.startedAndSpawned()
        h.advance(60_000)
        assertTrue(h.rec.net.isEmpty()) // the server ends the game; the client sends nothing
        h.advance(2_999)
        assertTrue(h.effects.none { it == SessionEffect.EndGameRequested })
        h.advance(1)
        assertEquals(SessionEffect.EndGameRequested, h.effects.last())
    }

    @Test
    fun networkTimeLimit_noFallbackWhenServerAlreadyEnded() {
        h.rec.useNetwork = true
        h.config.gameLimit = Globals.GAME_LIMIT_TIME
        h.config.timeLimitMinutes = 1
        h.connected()
        h.startedAndSpawned()
        h.advance(60_000)
        h.engine.endGame()
        h.advance(5_000)
        assertTrue(h.effects.none { it == SessionEffect.EndGameRequested })
    }

    @Test
    fun endGame_stopsTimersAndLocksTheBlaster() {
        h.config.gameLimit = Globals.GAME_LIMIT_TIME
        h.config.timeLimitMinutes = 5
        h.connected()
        h.startedAndSpawned()
        h.advance(3_000)
        h.engine.endGame()
        assertEquals(Globals.GAME_STATE_NONE, h.config.gameState)
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(GameClock.Stopped("05:00"), h.state.clock)
        assertEquals(SpawnPhase.None, h.state.spawn)
        h.advance(600_000)
        assertTrue(h.effects.isEmpty())
    }

    @Test
    fun endGameDuringSpawn_cancelsTheRespawn() {
        h.connected()
        h.engine.startGame()
        h.advance(3_000)
        h.engine.endGame()
        h.advance(20_000)
        assertEquals(Globals.GAME_STATE_NONE, h.config.gameState)
        assertFalse(h.sounds().contains(SessionSound.SPAWN))
    }

    @Test
    fun syncFromServer_restartsCountdownAndSetsEliminations() {
        h.connected()
        h.startedAndSpawned()
        h.engine.syncFromServer(eliminations = 3, timeRemaining = 90)
        h.runCurrent()
        assertEquals(3, h.state.eliminationDisplay)
        assertEquals(GameClock.Countdown("01:30"), h.state.clock)
    }

    // --------------------------------------------------------------------------------------
    // Hits, elimination, shield, lives
    // --------------------------------------------------------------------------------------

    @Test
    fun hit_whileRunning_costsHealthAndPlaysOneOverlayPer400ms() {
        h.config.names[2] = "Bob"
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(Globals.MAX_HEALTH - 1, h.state.health)
        assertEquals("Bob", h.state.hitOverlay)
        assertEquals(listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.HIT)), h.effects)
        assertEquals(listOf("HIT2"), h.rec.net)

        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3E))
        assertEquals(Globals.MAX_HEALTH - 2, h.state.health)
        assertEquals(2, h.effects.size) // overlay still playing: no new sound/vibration
        h.advance(400)
        assertEquals(null, h.state.hitOverlay)
        assertEquals(2, h.state.hitsTaken)
    }

    @Test
    fun hit_whileNotRunning_isCountedButCostsNothing() {
        h.connected()
        h.engine.startGame() // eliminated while spawning
        h.runCurrent()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(Globals.MAX_HEALTH, h.state.health)
        assertEquals(1, h.state.hitsTaken)
    }

    @Test
    fun lastHit_eliminates_respawnsWithShield() {
        h.config.fullHealth = 1
        h.config.names[2] = "Bob"
        h.config.teams[5] = 1
        h.config.teams[2] = 2
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()

        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.ELIMINATED)), h.effects.take(2))
        assertEquals(1, h.state.eliminationDisplay)
        assertEquals(Globals.GAME_STATE_ELIMINATED, h.config.gameState)
        assertEquals(SpawnPhase.Spawning(10, "Bob", false), h.state.spawn)
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(listOf("OUT2", "ELIMINATED2"), h.rec.net)

        h.advance(10_000)
        assertEquals(Globals.GAME_STATE_RUNNING, h.config.gameState)
        assertTrue(h.state.shieldSeconds != null)
        // Shielded: hits are ignored
        h.telemetry(Packet().hit1(0x08, 0x3B))
        assertEquals(1, h.state.health)
        h.advance(3_000)
        assertEquals(null, h.state.shieldSeconds)
        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3C))
        assertEquals(Globals.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun shieldCountdown_showsWholeSecondsRoundedUp() {
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        h.advance(10_000)
        val seen = mutableListOf<Long?>()
        repeat(3_100) {
            if (seen.lastOrNull() != h.state.shieldSeconds) seen += h.state.shieldSeconds
            h.advance(1)
        }
        // CountDownTimer(3000, 999) arithmetic, millisLeft / 1000 + 1 per tick. Virtual time has no
        // dispatch latency, so the first tick sees exactly 3000 ms (4); on a device it sees a few
        // ms less (3), as with the CountDownTimer this replaces.
        assertEquals(listOf<Long?>(4L, 3L, 2L, 1L, null), seen)
    }

    @Test
    fun teammateElimination_isReportedToo_theServerDecidesTheScore() {
        h.config.fullHealth = 1
        h.config.teams[5] = 1
        h.config.teams[2] = 1
        h.config.gameMode = Globals.GAME_MODE_FFA // no friendly fire, so the teammate's hit lands
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertTrue(h.rec.net.contains("ELIMINATED2"))
    }

    @Test
    fun livesLimit_countsDownAndStopsRespawningAtZero() {
        h.config.fullHealth = 1
        h.config.gameLimit = Globals.GAME_LIMIT_LIVES
        h.config.livesLimit = 1
        h.engine.applyGameLimit()
        h.connected()
        h.startedAndSpawned()
        assertEquals(1, h.state.eliminationDisplay)
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(0, h.state.eliminationDisplay)
        assertEquals(SpawnPhase.OutOfLives(""), h.state.spawn)
        assertTrue(h.toasts().contains(SessionToast.OUT_OF_LIVES))
        h.advance(60_000)
        assertEquals(Globals.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun serverLivesOverride_winsOverTheLocalLimit() {
        h.config.gameLimit = Globals.GAME_LIMIT_LIVES
        h.config.livesLimit = 5
        h.config.overrideLives = true
        h.config.overrideLivesValue = 2
        h.engine.applyGameLimit()
        assertTrue(h.engine.hasLivesLimitSet)
        assertEquals(2, h.state.eliminationDisplay)
        h.config.overrideLivesValue = 0
        h.engine.applyGameLimit()
        assertFalse(h.engine.hasLivesLimitSet)
        assertEquals(0, h.state.eliminationDisplay)
    }

    // --------------------------------------------------------------------------------------
    // Player id, grenades, battery, misc
    // --------------------------------------------------------------------------------------

    @Test
    fun blasterReportsNewPlayerId_isAdopted() {
        h.connected()
        h.telemetry(Packet().player(7))
        assertEquals(7.toByte(), h.config.playerId)
        assertEquals(listOf<SessionEffect>(SessionEffect.PlayerIdReported), h.effects)
        assertTrue(h.rec.ble.isEmpty())
    }

    @Test
    fun blasterLosesPlayerId_isSentAgain() {
        h.connected()
        h.telemetry(Packet().player(0))
        assertEquals(listOf("team:5"), h.rec.ble)
        assertEquals(listOf<SessionEffect>(SessionEffect.TeamSent), h.effects)
    }

    @Test
    fun grenadePairing_storesIdAndTellsTheServer() {
        h.rec.useNetwork = true
        h.connected()
        h.telemetry(Packet().hit1(167, 0x9F))
        assertEquals(9.toByte(), h.config.pairedGrenadeId)
        assertEquals(listOf("GRENADE"), h.rec.net)
        assertEquals(listOf(SessionToast.GRENADE_PAIRED), h.toasts())
        h.telemetry(Packet().hit1(167, 0x9D)) // disarm
        assertEquals(0.toByte(), h.config.pairedGrenadeId)
    }

    @Test
    fun battery_isAveragedAndClassifiedByBlasterType() {
        h.connected(Packet().battery(0x10))
        assertEquals(BatteryLevelPolicy.Level.FULL, h.state.battery)
        h.engine.onBlasterEvent(BlasterEvent.TypeDiscovered(1)) // rifle: 16 is only MEDIUM
        h.telemetry(Packet().battery(0x10))
        assertEquals(BatteryLevelPolicy.Level.MEDIUM, h.state.battery)
    }

    @Test
    fun servicesDiscovered_spawnSoundIdle_reloadWhileRunning() {
        h.engine.onBlasterEvent(BlasterEvent.ServicesDiscovered)
        assertEquals(listOf(SessionSound.SPAWN), h.sounds())
        h.connected()
        h.startedAndSpawned()
        h.engine.onBlasterEvent(BlasterEvent.ServicesDiscovered)
        assertEquals(listOf("startReload:5"), h.rec.ble)
    }

    @Test
    fun disconnectDialogDismissed_resendsRecoilAt600ms() {
        h.engine.onDisconnectDialogDismissed()
        h.advance(599)
        assertTrue(h.rec.ble.isEmpty())
        h.advance(1)
        assertEquals(listOf("recoil:true"), h.rec.ble)
    }

    @Test
    fun playerSettings_coerceShotModeAndApplyServerRecoil() {
        h.engine.restore(burst, true)
        h.config.allowBurstShotMode = false
        h.config.serverRecoilSetting = Globals.RECOIL_SETTING_DISABLED
        h.engine.applyPlayerSettings()
        assertEquals(listOf("recoil:false", "shotMode:$auto/0"), h.rec.ble)
    }

    @Test
    fun noNetwork_sendsNothing() {
        h.engine.network = null
        h.config.fullHealth = 1
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(29).hit1(0x08, 0x3D))
        assertEquals(1, h.state.eliminationDisplay)
    }
}
