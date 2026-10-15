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

package com.simplecoil.simplecoil.hardware.audio

import com.simplecoil.simplecoil.domain.session.SessionEffect
import com.simplecoil.simplecoil.domain.session.SessionSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Plays game sounds. */
interface SoundEffectPlayer {
    fun play(sound: SessionSound)
}

/** Haptic feedback. */
interface HapticsPlayer {
    /** The one-shot hit/elimination buzz (250 ms, as before). */
    fun vibrate()
}

/**
 * Sounds and haptics through AudioHapticsManager: the same raw resources, preloaded in a
 * SoundPool instead of a new MediaPlayer per sound (P6: MediaPlayer.create() at full-auto rates,
 * and a null return crashed).
 */
class AudioHapticsPlayer(private val manager: AudioHapticsManager) : SoundEffectPlayer, HapticsPlayer {
    override fun play(sound: SessionSound) = manager.playSound(effect(sound))

    override fun vibrate() = manager.vibrate(HIT_VIBRATE_DURATION_MILLIS)

    companion object {
        const val HIT_VIBRATE_DURATION_MILLIS = 250L

        @JvmStatic
        fun effect(sound: SessionSound): AudioHapticsManager.SoundEffect = when (sound) {
            SessionSound.BEEP -> AudioHapticsManager.SoundEffect.BEEP
            SessionSound.ELIMINATED -> AudioHapticsManager.SoundEffect.ELIMINATED
            SessionSound.EMPTY -> AudioHapticsManager.SoundEffect.EMPTY
            SessionSound.HIT -> AudioHapticsManager.SoundEffect.HIT
            SessionSound.RELOAD -> AudioHapticsManager.SoundEffect.RELOAD
            SessionSound.SCORE -> AudioHapticsManager.SoundEffect.SCORE
            SessionSound.SHOT -> AudioHapticsManager.SoundEffect.SHOT
            SessionSound.SPAWN -> AudioHapticsManager.SoundEffect.SPAWN
            SessionSound.HIT_CONFIRM -> AudioHapticsManager.SoundEffect.HIT_CONFIRM
            SessionSound.HIT_ELIMINATED -> AudioHapticsManager.SoundEffect.HIT_ELIMINATED
            SessionSound.HIT_SHIELDED -> AudioHapticsManager.SoundEffect.HIT_SHIELDED
        }
    }
}

/**
 * Plays the session's sound and vibration effects app-side, in order, whether or not a screen is
 * showing (D4). The UI handles the remaining effects.
 */
class SessionFeedback(private val sounds: SoundEffectPlayer, private val haptics: HapticsPlayer) {
    fun attach(effects: Flow<SessionEffect>, scope: CoroutineScope) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            effects.collect { onEffect(it) }
        }
    }

    fun onEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.Sound -> sounds.play(effect.sound)
            SessionEffect.Vibrate -> haptics.vibrate()
            else -> Unit
        }
    }
}
