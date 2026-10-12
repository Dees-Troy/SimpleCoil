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

/**
 * Which shot mode to apply when the thumb button is pressed or the server changes the allowed
 * modes. Extracted verbatim from FullscreenActivity's thumb-counter handling and
 * updatePlayerSettings().
 *
 * A non-null result means the caller applies that mode, which writes the config packet even when
 * the mode is unchanged. Null means nothing is applied.
 */
object ShotModePolicy {

    /** Thumb button: advance single → burst → auto → single, skipping modes that aren't allowed. */
    @JvmStatic
    fun nextOnThumb(current: Int, allowSingle: Boolean, allowBurst: Boolean, allowAuto: Boolean): Int? = when (current) {
        Globals.SHOT_MODE_SINGLE -> when {
            allowBurst -> Globals.SHOT_MODE_BURST
            allowAuto -> Globals.SHOT_MODE_FULL_AUTO
            else -> Globals.SHOT_MODE_SINGLE
        }
        Globals.SHOT_MODE_BURST -> when {
            allowAuto -> Globals.SHOT_MODE_FULL_AUTO
            allowSingle -> Globals.SHOT_MODE_SINGLE
            else -> Globals.SHOT_MODE_BURST
        }
        Globals.SHOT_MODE_FULL_AUTO -> when {
            allowSingle -> Globals.SHOT_MODE_SINGLE
            allowBurst -> Globals.SHOT_MODE_BURST
            else -> Globals.SHOT_MODE_FULL_AUTO
        }
        else -> null
    }

    /** Player settings changed: move off the current mode only if it is no longer allowed. */
    @JvmStatic
    fun coerceToAllowed(current: Int, allowSingle: Boolean, allowBurst: Boolean, allowAuto: Boolean): Int? = when (current) {
        Globals.SHOT_MODE_SINGLE ->
            if (allowSingle) null
            else if (allowBurst) Globals.SHOT_MODE_BURST
            else Globals.SHOT_MODE_FULL_AUTO
        Globals.SHOT_MODE_BURST ->
            if (allowBurst) null
            else if (allowAuto) Globals.SHOT_MODE_FULL_AUTO
            else Globals.SHOT_MODE_SINGLE
        Globals.SHOT_MODE_FULL_AUTO ->
            if (allowAuto) null
            else if (allowSingle) Globals.SHOT_MODE_SINGLE
            else Globals.SHOT_MODE_BURST
        else ->
            if (allowSingle) Globals.SHOT_MODE_SINGLE
            else if (allowBurst) Globals.SHOT_MODE_BURST
            else Globals.SHOT_MODE_FULL_AUTO
    }
}
