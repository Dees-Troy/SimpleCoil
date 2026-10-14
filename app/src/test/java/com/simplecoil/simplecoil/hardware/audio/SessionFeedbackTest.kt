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

import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.session.SessionEffect
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionToast
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFeedbackTest {

    @Test
    fun soundsAndVibration_arePlayedInOrder_otherEffectsIgnored() {
        val played = mutableListOf<String>()
        val feedback = SessionFeedback(
            object : SoundEffectPlayer { override fun play(sound: SessionSound) { played += sound.name } },
            object : HapticsPlayer { override fun vibrate() { played += "vibrate" } },
        )
        listOf(SessionEffect.Vibrate, SessionEffect.Sound(SessionSound.HIT), SessionEffect.Toast(SessionToast.OUT_OF_LIVES),
            SessionEffect.CommunicationStarted, SessionEffect.Sound(SessionSound.BEEP)).forEach(feedback::onEffect)
        assertEquals(listOf("vibrate", "HIT", "BEEP"), played)
    }

    @Test
    fun everySound_mapsToTheSameRawResourceAsBefore() {
        val expected = mapOf(
            SessionSound.BEEP to R.raw.beep, SessionSound.ELIMINATED to R.raw.eliminated, SessionSound.EMPTY to R.raw.empty,
            SessionSound.HIT to R.raw.hit, SessionSound.RELOAD to R.raw.reload, SessionSound.SCORE to R.raw.score,
            SessionSound.SHOT to R.raw.shootingshort, SessionSound.SPAWN to R.raw.spawn)
        for (sound in SessionSound.values()) {
            assertEquals(sound.name, expected[sound], AudioHapticsPlayer.effect(sound).rawResId)
        }
        assertEquals(250L, AudioHapticsPlayer.HIT_VIBRATE_DURATION_MILLIS)
    }
}
