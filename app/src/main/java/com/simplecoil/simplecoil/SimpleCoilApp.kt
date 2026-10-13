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
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped container (manual DI). Owns the single BlasterManager for the process.
 */
class SimpleCoilApp : Application() {

    /** Main-thread scope that lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val blasterManager: BlasterManager by lazy { BlasterManager(appScope) }

    val bleDeviceRepository: BleDeviceRepository by lazy { BleDeviceRepository(blasterManager) }

    /** The player's saved settings ("SimpleCoil" preferences). */
    val userPreferences: UserPreferencesRepository by lazy { UserPreferencesRepository(this) }

    val bleConnectionManager: BleConnectionManager by lazy {
        BleConnectionManager(this, blasterManager, appScope, saveDeviceAddress = { userPreferences.saveDeviceAddress(it) })
            .also { it.registerAdapterStateReceiver() }
    }

    /** The blaster session (D4: app-scoped). Its timers post to the main thread like Handlers. */
    val sessionEngine: BlasterSessionEngine by lazy {
        BlasterSessionEngine(
            blaster = bleDeviceRepository,
            config = RepositoryGameConfig,
            preferences = userPreferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            clock = SystemClock::elapsedRealtime,
        ).also {
            it.network = networkGateway
            it.attach(bleDeviceRepository.events)
            SessionFeedback(soundEffects, haptics).attach(it.effects, appScope)
        }
    }

    private val audioHaptics: AudioHapticsPlayer by lazy {
        AudioHapticsPlayer(AudioHapticsManager.getInstance().also { it.initialize(this) })
    }

    /** Game sounds (preloaded SoundPool). */
    val soundEffects: SoundEffectPlayer get() = audioHaptics

    val haptics: HapticsPlayer get() = audioHaptics

    /** Network services, NETMSG_* events and the network session state (R13/R14). */
    val networkGateway: NetworkSessionGateway by lazy {
        NetworkSessionGateway(this, appScope).also { it.registerReceiver() }
    }
}
