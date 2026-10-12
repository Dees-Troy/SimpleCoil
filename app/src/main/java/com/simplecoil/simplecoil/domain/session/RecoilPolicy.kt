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
 * Recoil on/off decisions: the server setting forces a value, otherwise the player's own toggle
 * applies. Extracted verbatim from FullscreenActivity.applyCurrentRecoilSetting() and the power
 * button handling in processTelemetryData().
 */
object RecoilPolicy {

    /** Result of a power-button press. [forced] means the server overrode the toggle. */
    data class PowerButton(val enabled: Boolean, val forced: Boolean)

    @JvmStatic
    fun effective(serverSetting: Int, localEnabled: Boolean): Boolean = when (serverSetting) {
        Globals.RECOIL_SETTING_ENABLED -> true
        Globals.RECOIL_SETTING_DISABLED -> false
        else -> localEnabled
    }

    @JvmStatic
    fun onPowerButton(serverSetting: Int, localEnabled: Boolean): PowerButton = when (serverSetting) {
        Globals.RECOIL_SETTING_ENABLED -> PowerButton(enabled = true, forced = true)
        Globals.RECOIL_SETTING_DISABLED -> PowerButton(enabled = false, forced = true)
        else -> PowerButton(enabled = !localEnabled, forced = false)
    }
}
