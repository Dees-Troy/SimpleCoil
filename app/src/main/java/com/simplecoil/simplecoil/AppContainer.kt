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

package com.simplecoil.simplecoil

import android.app.Application
import android.os.SystemClock
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.GameConfig
import com.simplecoil.simplecoil.domain.session.RepositoryGameConfig
import com.simplecoil.simplecoil.hardware.audio.AudioHapticsManager
import com.simplecoil.simplecoil.hardware.audio.AudioHapticsPlayer
import com.simplecoil.simplecoil.hardware.audio.HapticsPlayer
import com.simplecoil.simplecoil.hardware.audio.SessionFeedback
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * The application's object graph (manual DI, APP_CONTAINER_DESIGN.md). Built once in
 * [SimpleCoilApp.onCreate]. Plain classes get their dependencies through their constructors from
 * here; Android entry points read it from the application.
 */
class AppContainer(private val app: Application) {

    val dispatchers = AppDispatchers()

    /** Monotonic milliseconds, counting deep sleep. */
    val clock: () -> Long = SystemClock::elapsedRealtime

    /** Main-thread scope that lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.mainImmediate)

    // State

    val settings = GameSettingsRepository()
    val gameState = GameStateRepository()
    val players = PlayerRepository(settings, gameState)
    val gameConfig: GameConfig = RepositoryGameConfig(settings, players, gameState)

    /** The player's saved settings ("SimpleCoil" preferences). */
    val userPreferences: UserPreferencesRepository by lazy { UserPreferencesRepository(app) }

    // Hardware and session

    val blasterManager: BlasterManager by lazy { BlasterManager(appScope) }

    val bleDeviceRepository: BleDeviceRepository by lazy { BleDeviceRepository(blasterManager) }

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(app, blasterManager, appScope, saveDeviceAddress = { userPreferences.saveDeviceAddress(it) })
            .also { it.registerAdapterStateReceiver() }
    }

    /** The blaster session (D4: app-scoped). Its timers post to the main thread like Handlers. */
    val sessionEngine: BlasterSessionEngine by lazy {
        BlasterSessionEngine(
            blaster = bleDeviceRepository,
            config = gameConfig,
            preferences = userPreferences,
            scope = CoroutineScope(SupervisorJob() + dispatchers.main),
            clock = clock,
        ).also {
            it.network = networkGateway
            it.attach(bleDeviceRepository.events)
            SessionFeedback(soundEffects, haptics).attach(it.effects, appScope)
        }
    }

    private val audioHaptics: AudioHapticsPlayer by lazy {
        AudioHapticsPlayer(AudioHapticsManager.getInstance().also { it.initialize(app) })
    }

    /** Game sounds (preloaded SoundPool). */
    val soundEffects: SoundEffectPlayer get() = audioHaptics

    val haptics: HapticsPlayer get() = audioHaptics

    /**
     * Makes this container's repositories the ones getInstance() returns, for the code that
     * doesn't take them through its constructor yet (APP_CONTAINER_DESIGN.md §3).
     */
    fun installLegacySingletons() {
        GameSettingsRepository.install(settings)
        GameStateRepository.install(gameState)
        PlayerRepository.install(players)
    }

    /** Network services, NETMSG_* events and the network session state (R13/R14). */
    val networkGateway: NetworkSessionGateway by lazy {
        NetworkSessionGateway(app, appScope).also { it.registerReceiver() }
    }
}
