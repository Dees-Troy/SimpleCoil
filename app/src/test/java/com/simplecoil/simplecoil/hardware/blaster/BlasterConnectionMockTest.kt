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

package com.simplecoil.simplecoil.hardware.blaster

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlasterConnectionMockTest {

    @Test
    fun testRecoilStatePreservedOnManager() {
        val manager = BlasterManager(TestScope())
        assertTrue(manager.isRecoilEnabled)

        manager.setRecoil(false)
        assertFalse(manager.isRecoilEnabled)

        manager.setRecoil(true)
        assertTrue(manager.isRecoilEnabled)
    }
}
