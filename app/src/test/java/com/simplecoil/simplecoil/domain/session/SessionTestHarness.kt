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
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.team.TeamLayout
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Mutable GameConfig with the app's defaults. */
class FakeGameConfig : GameConfig {
    override var gameState = GameStateRepository.GAME_STATE_NONE
    override var playerId: Byte = 5
    override var pairedGrenadeId: Byte = 0
    override var fullHealth = GameSettingsRepository.MAX_HEALTH
    override var fullReload = GameSettingsRepository.RELOAD_COUNT
    override var reloadTimeMillis = GameSettingsRepository.RELOAD_TIME_MILLISECONDS
    override var manualRespawn = false
    override var respawnTimeSeconds = GameSettingsRepository.RESPAWN_TIME_SECONDS
    override var respawnShieldTimeSeconds = GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS
    override var startCountdownSeconds = GameSettingsRepository.START_TIME_SECONDS
    override var gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
    override var timeLimitMinutes = 0
    override var livesLimit = 0
    override var overrideLives = false
    override var overrideLivesValue = 0
    override var reloadOnEmpty = false
    override var allowSingleShotMode = true
    override var allowBurstShotMode = true
    override var allowAutoShotMode = true
    override var serverRecoilSetting = GameSettingsRepository.RECOIL_SETTING_DEFAULT
    override var firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE
    override var teamLayout = TeamLayout.DEFAULT
    var names = mutableMapOf<Byte, String>()
    var damage = mutableMapOf<Byte, Int>()

    override fun playerName(playerId: Byte) = names[playerId]
    override fun playerDamage(playerId: Byte) = damage[playerId]
    override fun grenadePairing(grenadeId: Int) = PlayerRepository.INVALID_PLAYER_ID
}

/** Records blaster commands, network sends and persisted settings as compact strings. */
class Recorder : BlasterCommands, SessionNetwork, SessionPreferences {
    val ble = mutableListOf<String>()
    val net = mutableListOf<String>()
    val saved = mutableListOf<String>()
    override var useNetwork = false

    override fun setTeam(playerId: Byte) { ble += "team:$playerId" }
    override fun startReload(playerId: Byte) { ble += "startReload:$playerId" }
    override fun finishReload(playerId: Byte, fullReloadCount: Byte) { ble += "finishReload:$playerId/$fullReloadCount" }
    override fun setShotMode(shotMode: Int, firingMode: Int) { ble += "shotMode:$shotMode/$firingMode" }
    override fun setRecoil(enabled: Boolean) { ble += "recoil:$enabled" }

    override fun sendHit(playerId: Byte) { net += "HIT$playerId" }
    override fun sendOut(playerId: Byte) { net += "OUT$playerId" }
    override fun sendShotFired() { net += "SHOTFIRED" }
    override fun sendEliminated(playerId: Byte) { net += "ELIMINATED$playerId" }
    override fun sendPlayerGrenade() { net += "GRENADE" }

    override fun saveShotMode(shotMode: Int) { saved += "shotMode:$shotMode" }
    override fun saveRecoilEnabled(enabled: Boolean) { saved += "recoil:$enabled" }
}

/** An engine on virtual time, with its effects collected. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTestHarness {
    val scope = TestScope()
    val config = FakeGameConfig()
    val rec = Recorder()
    val effects = mutableListOf<SessionEffect>()
    val engine = BlasterSessionEngine(rec, config, rec, scope, clock = { scope.testScheduler.currentTime },
        wallClock = { 1_000_000 + scope.testScheduler.currentTime })

    init {
        engine.network = rec
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            engine.effects.collect { effects += it }
        }
    }

    val state get() = engine.state.value
    val now get() = scope.testScheduler.currentTime

    fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    fun runCurrent() = scope.testScheduler.runCurrent()

    fun sounds() = effects.filterIsInstance<SessionEffect.Sound>().map { it.sound }
    fun toasts() = effects.filterIsInstance<SessionEffect.Toast>().map { it.toast }

    /** Packet builder starting from an idle pistol: 30 shots, battery 0x10, player 5. */
    class Packet {
        val bytes: ByteArray = RecoilProtocolFixtures.GOLDEN_IDLE.clone().also {
            it[GattProtocolHandler.RECOIL_OFFSET_TEAM] = 5
        }
        fun set(offset: Int, value: Int) = apply { bytes[offset] = value.toByte() }
        fun shots(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_SHOTS_REMAINING, n)
        fun trigger(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER,
            (bytes[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER].toInt() and 0xF0) or (n and 0x0F))
        fun reload(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER,
            (bytes[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER].toInt() and 0x0F) or ((n and 0x0F) shl 4))
        fun thumb(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER, n)
        fun power(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER, n)
        fun thumbHeld() = set(GattProtocolHandler.RECOIL_OFFSET_BUTTONS, GattProtocolHandler.RECOIL_BUTTON_THUMB)
        fun reloadHeld() = set(GattProtocolHandler.RECOIL_OFFSET_BUTTONS, GattProtocolHandler.RECOIL_BUTTON_RELOAD)
        fun player(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_TEAM, n)
        fun battery(n: Int) = set(GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL, n)
        fun hit1(hitBy: Int, shotIdByte: Int) = set(GattProtocolHandler.RECOIL_OFFSET_HIT_BY1, hitBy)
            .set(GattProtocolHandler.RECOIL_OFFSET_HIT_BY1_SHOTID, shotIdByte)
        fun noHit() = hit1(0, 0)
    }

    fun telemetry(packet: Packet) {
        engine.onBlasterEvent(BlasterEvent.Telemetry(GattProtocolHandler.parseTelemetry(packet.bytes)!!))
        runCurrent()
    }

    fun writeFinished() {
        engine.onBlasterEvent(BlasterEvent.WriteFinished)
        runCurrent()
    }

    /** Connects and delivers the first packet, then runs the first-packet setup and clears the records. */
    fun connected(packet: Packet = Packet()) {
        engine.onBlasterEvent(BlasterEvent.Connected("AA:BB:CC:DD:EE:FF"))
        telemetry(packet)
        advance(1_000)
        rec.ble.clear()
        rec.net.clear()
        rec.saved.clear()
        effects.clear()
    }

    /** Starts a game and waits through the first spawn, completing the reload handshake. */
    fun startedAndSpawned() {
        engine.startGame()
        runCurrent()
        advance(config.startCountdownSeconds * 1000)
        writeFinished() // finishReload acknowledged
        rec.ble.clear()
        rec.net.clear()
        effects.clear()
    }
}
