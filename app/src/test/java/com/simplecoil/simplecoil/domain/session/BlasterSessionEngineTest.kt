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
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.domain.session.SessionTestHarness.Packet
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session logic moved out of FullscreenActivity, in virtual time: the §1.6 delays it owns,
 * and per state transition the blaster commands and network messages it produces.
 */
class BlasterSessionEngineTest {

    private val h = SessionTestHarness()
    private val single = GameSettingsRepository.SHOT_MODE_SINGLE
    private val burst = GameSettingsRepository.SHOT_MODE_BURST
    private val auto = GameSettingsRepository.SHOT_MODE_FULL_AUTO

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
    fun thumbHeld_shortPressCyclesShotModeOnRelease() {
        h.connected()
        h.telemetry(Packet().thumb(1).thumbHeld())
        assertEquals(emptyList<String>(), h.rec.ble)
        h.advance(500)
        h.telemetry(Packet().thumb(1))
        assertEquals(listOf("shotMode:$burst/0"), h.rec.ble)
        assertTrue(h.effects.none { it == SessionEffect.ThumbLongPressed })
    }

    @Test
    fun thumbHeld_countedOnRelease_cyclesShotModeOnce() {
        h.connected()
        h.telemetry(Packet().thumbHeld())
        h.advance(300)
        h.telemetry(Packet().thumb(1))
        h.telemetry(Packet().thumb(1))
        assertEquals(listOf("shotMode:$burst/0"), h.rec.ble)
    }

    @Test
    fun thumbHeld_longPressEmitsOnceAndKeepsShotMode() {
        h.connected()
        h.telemetry(Packet().thumb(1).thumbHeld())
        for (i in 1..30) {
            h.advance(100)
            h.telemetry(Packet().thumb(1).thumbHeld())
        }
        assertEquals(1, h.effects.count { it == SessionEffect.ThumbLongPressed })
        // Released; a blaster that counts on release reports it in the release packet or the next.
        h.telemetry(Packet().thumb(1))
        h.telemetry(Packet().thumb(2))
        assertEquals(emptyList<String>(), h.rec.ble)
        assertEquals(single, h.state.shotMode)
        // A later tap is a short press again.
        h.telemetry(Packet().thumb(3))
        assertEquals(listOf("shotMode:$burst/0"), h.rec.ble)
    }

    @Test
    fun thumbHeld_justUnderLongPressIsAShortPress() {
        h.connected()
        h.telemetry(Packet().thumb(1).thumbHeld())
        h.advance(BlasterSessionEngine.THUMB_LONG_PRESS_MILLIS - 1)
        h.telemetry(Packet().thumb(1).thumbHeld())
        h.telemetry(Packet().thumb(1))
        assertTrue(h.effects.none { it == SessionEffect.ThumbLongPressed })
        assertEquals(listOf("shotMode:$burst/0"), h.rec.ble)
    }

    @Test
    fun power_togglesRecoil_unlessServerForcesIt() {
        h.connected()
        h.telemetry(Packet().power(1))
        assertEquals(listOf("recoil:false"), h.rec.ble)
        assertFalse(h.state.recoilEnabled)
        h.config.serverRecoilSetting = GameSettingsRepository.RECOIL_SETTING_DISABLED
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
    fun reloadOnEmpty_withShortReloadTime_reloadsWhenLastShotIsFired_withoutReloadSound() {
        h.config.reloadOnEmpty = true
        h.config.reloadTimeMillis = BlasterSessionEngine.RELOAD_ON_EMPTY_SILENT_MAX_MILLIS
        reloadOnEmpty()
        assertFalse(h.sounds().contains(SessionSound.RELOAD))
    }

    @Test
    fun reloadOnEmpty_withLongReloadTime_playsReloadSound() {
        h.config.reloadOnEmpty = true
        h.config.reloadTimeMillis = BlasterSessionEngine.RELOAD_ON_EMPTY_SILENT_MAX_MILLIS + 1
        reloadOnEmpty()
        assertTrue(h.sounds().contains(SessionSound.RELOAD))
    }

    private fun reloadOnEmpty() {
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().shots(1))
        h.rec.ble.clear()
        h.telemetry(Packet().shots(0))
        assertEquals(listOf("startReload:5"), h.rec.ble)
        h.writeFinished()
        h.advance(h.config.reloadTimeMillis)
        h.writeFinished()
        assertEquals(listOf("startReload:5", "finishReload:5/30"), h.rec.ble)
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
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
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
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
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
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
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
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
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
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
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
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_TIME
        h.config.timeLimitMinutes = 5
        h.connected()
        h.startedAndSpawned()
        h.advance(3_000)
        h.engine.endGame()
        assertEquals(GameStateRepository.GAME_STATE_NONE, h.config.gameState)
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(GameClock.Stopped("05:00"), h.state.clock)
        assertEquals(SpawnPhase.None, h.state.spawn)
        h.advance(600_000)
        assertTrue(h.effects.isEmpty())
    }

    @Test
    fun gameInProgress_fromStartGameUntilEndGame() {
        h.connected()
        assertFalse(h.state.gameInProgress)
        h.engine.startGame()
        assertTrue(h.state.gameInProgress) // spawning counts as in progress
        h.advance(5_000)
        assertTrue(h.state.gameInProgress)
        h.engine.endGame()
        assertFalse(h.state.gameInProgress)
    }

    @Test
    fun endGameDuringSpawn_cancelsTheRespawn() {
        h.connected()
        h.engine.startGame()
        h.advance(3_000)
        h.engine.endGame()
        h.advance(20_000)
        assertEquals(GameStateRepository.GAME_STATE_NONE, h.config.gameState)
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
        assertEquals(GameSettingsRepository.MAX_HEALTH - 1, h.state.health)
        assertEquals("Bob", h.state.hitOverlay)
        assertEquals(listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.HIT)), h.effects)
        assertEquals(listOf("HIT2"), h.rec.net)

        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3E))
        assertEquals(GameSettingsRepository.MAX_HEALTH - 2, h.state.health)
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
        assertEquals(GameSettingsRepository.MAX_HEALTH, h.state.health)
        assertEquals(1, h.state.hitsTaken)
    }

    @Test
    fun lastHit_eliminates_respawnsWithShield() {
        h.config.fullHealth = 1
        h.config.names[2] = "Bob"
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()

        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.ELIMINATED)), h.effects.take(2))
        assertEquals(1, h.state.eliminationDisplay)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
        assertEquals(SpawnPhase.Spawning(10, "Bob", false), h.state.spawn)
        assertEquals(listOf("startReload:5"), h.rec.ble)
        assertEquals(listOf("OUT2", "ELIMINATED2"), h.rec.net)

        h.advance(10_000)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        assertTrue(h.state.shieldSeconds != null)
        // Shielded: hits cost nothing, but the shooter is told
        h.telemetry(Packet().hit1(0x08, 0x3B))
        assertEquals(1, h.state.health)
        assertEquals("SHIELDHIT2", h.rec.net.last())
        h.advance(3_000)
        assertEquals(null, h.state.shieldSeconds)
        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3C))
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun manualRespawn_waitsForThePlayer_thenCountsDownAndRespawns() {
        h.config.fullHealth = 1
        h.config.manualRespawn = true
        h.config.names[2] = "Bob"
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned() // the game start countdown is never manual

        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertEquals(SpawnPhase.AwaitingRespawn("Bob"), h.state.spawn)
        h.advance(60_000)
        assertEquals(SpawnPhase.AwaitingRespawn("Bob"), h.state.spawn)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)

        h.engine.startRespawn()
        assertEquals(SpawnPhase.Spawning(10, "Bob", false), h.state.spawn)
        h.engine.startRespawn() // already counting down: ignored
        h.advance(10_000)
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
        assertTrue(h.state.shieldSeconds != null)
    }

    @Test
    fun manualRespawn_holdingReloadTwoSecondsStartsTheRespawn() {
        h.config.fullHealth = 1
        h.config.manualRespawn = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))

        // Just under a long press, then released: still waiting.
        h.telemetry(Packet().reload(1).reloadHeld())
        h.advance(BlasterSessionEngine.RELOAD_LONG_PRESS_MILLIS - 1)
        h.telemetry(Packet().reload(1).reloadHeld())
        h.telemetry(Packet().reload(1))
        assertEquals(SpawnPhase.AwaitingRespawn(""), h.state.spawn)

        h.telemetry(Packet().reload(2).reloadHeld())
        for (i in 1..20) {
            h.advance(100)
            h.telemetry(Packet().reload(2).reloadHeld())
        }
        assertEquals(SpawnPhase.Spawning(10, "", false), h.state.spawn)
    }

    @Test
    fun manualRespawn_holdingReloadWhileAliveDoesNothing() {
        h.config.manualRespawn = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().reloadHeld())
        h.advance(3_000)
        h.telemetry(Packet().reloadHeld())
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, h.config.gameState)
    }

    @Test
    fun manualRespawn_endGameWhileWaitingClearsIt() {
        h.config.fullHealth = 1
        h.config.manualRespawn = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        h.engine.endGame()
        assertEquals(SpawnPhase.None, h.state.spawn)
        h.engine.startRespawn()
        assertEquals(SpawnPhase.None, h.state.spawn)
        assertEquals(GameStateRepository.GAME_STATE_NONE, h.config.gameState)
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
    fun fourTeams_friendlyFireFollowsTheFourTeamLayout() {
        h.rec.useNetwork = true
        h.engine.networkTeam = 1 // players 1-8 with four teams
        h.config.teamLayout = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS)
        h.connected()
        h.startedAndSpawned()
        // Player 9 is on team 2 with four teams (with two teams of 16 it would be a teammate).
        h.telemetry(Packet().hit1(0x24, 0x3D))
        assertEquals(GameSettingsRepository.MAX_HEALTH - 1, h.state.health)
        // Player 2 is a teammate: the hit is counted but costs nothing.
        h.telemetry(Packet().noHit())
        h.telemetry(Packet().hit1(0x08, 0x3E))
        assertEquals(GameSettingsRepository.MAX_HEALTH - 1, h.state.health)
        assertEquals(2, h.state.hitsTaken)
    }

    @Test
    fun teammateElimination_isReportedToo_theServerDecidesTheScore() {
        h.config.fullHealth = 1
        h.engine.networkTeam = 1 // players 2 and 5 are both on team 1 in the default layout
        h.config.teamLayout = TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL) // no friendly fire, so the teammate's hit lands
        h.rec.useNetwork = true
        h.connected()
        h.startedAndSpawned()
        h.telemetry(Packet().hit1(0x08, 0x3D))
        assertTrue(h.rec.net.contains("ELIMINATED2"))
    }

    @Test
    fun livesLimit_countsDownAndStopsRespawningAtZero() {
        h.config.fullHealth = 1
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_LIVES
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
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, h.config.gameState)
    }

    @Test
    fun serverLivesOverride_winsOverTheLocalLimit() {
        h.config.gameLimit = GameSettingsRepository.GAME_LIMIT_LIVES
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
        assertTrue(h.state.grenadePaired)
        h.telemetry(Packet().hit1(167, 0x9D)) // disarm
        assertEquals(0.toByte(), h.config.pairedGrenadeId)
        assertFalse(h.state.grenadePaired)
    }

    @Test
    fun forgetGrenade_unpairsIt() {
        h.connected()
        h.telemetry(Packet().hit1(167, 0x9F))
        assertTrue(h.state.grenadePaired)
        h.engine.forgetGrenade()
        assertEquals(0.toByte(), h.config.pairedGrenadeId)
        assertFalse(h.state.grenadePaired)
    }

    @Test
    fun battery_isAveragedAndClassifiedByBlasterType() {
        h.connected(Packet().battery(0x10))
        assertEquals(BatteryLevelPolicy.Level.FULL, h.state.battery)
        assertNull(h.state.blasterType)
        h.engine.onBlasterEvent(BlasterEvent.TypeDiscovered(1)) // rifle: 16 is only MEDIUM
        assertEquals(1.toByte(), h.state.blasterType)
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
        h.config.serverRecoilSetting = GameSettingsRepository.RECOIL_SETTING_DISABLED
        h.engine.applyPlayerSettings()
        assertEquals(listOf("recoil:false", "shotMode:$auto/0"), h.rec.ble)
    }

    @Test
    fun playerSettings_shotModeAndRecoilChangeableUnlessRestricted() {
        h.engine.applyPlayerSettings()
        assertTrue(h.state.shotModeChangeable)
        assertTrue(h.state.recoilChangeable)
        h.config.allowBurstShotMode = false
        h.config.allowAutoShotMode = false
        h.config.serverRecoilSetting = GameSettingsRepository.RECOIL_SETTING_ENABLED
        h.engine.applyPlayerSettings()
        assertFalse(h.state.shotModeChangeable)
        assertFalse(h.state.recoilChangeable)
    }

    @Test
    fun tiles_cycleShotModeAndToggleRecoilLikeTheButtons() {
        h.engine.cycleShotMode()
        assertEquals(burst, h.state.shotMode)
        h.engine.toggleRecoil()
        assertFalse(h.state.recoilEnabled)
        assertEquals(listOf("shotMode:$burst/0", "recoil:false"), h.rec.ble)
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
