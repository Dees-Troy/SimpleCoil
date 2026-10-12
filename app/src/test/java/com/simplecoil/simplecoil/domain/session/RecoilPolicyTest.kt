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
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoilPolicyTest {

    @Test
    fun effective_serverSettingOverridesLocal() {
        for (local in listOf(true, false)) {
            assertEquals(true, RecoilPolicy.effective(Globals.RECOIL_SETTING_ENABLED, local))
            assertEquals(false, RecoilPolicy.effective(Globals.RECOIL_SETTING_DISABLED, local))
            assertEquals(local, RecoilPolicy.effective(Globals.RECOIL_SETTING_DEFAULT, local))
            // Unknown server values behave like the default.
            assertEquals(local, RecoilPolicy.effective(99, local))
        }
    }

    @Test
    fun powerButton_togglesUnlessForced() {
        for (local in listOf(true, false)) {
            assertEquals(RecoilPolicy.PowerButton(enabled = true, forced = true),
                RecoilPolicy.onPowerButton(Globals.RECOIL_SETTING_ENABLED, local))
            assertEquals(RecoilPolicy.PowerButton(enabled = false, forced = true),
                RecoilPolicy.onPowerButton(Globals.RECOIL_SETTING_DISABLED, local))
            assertEquals(RecoilPolicy.PowerButton(enabled = !local, forced = false),
                RecoilPolicy.onPowerButton(Globals.RECOIL_SETTING_DEFAULT, local))
        }
    }
}
