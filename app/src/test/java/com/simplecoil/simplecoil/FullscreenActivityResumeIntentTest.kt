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

import android.content.Intent
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * The service notifications must bring back the existing play screen, not stack a fresh one on
 * the connect layout over the screen holding the session. Android only resumes the existing task
 * when the intent matches the launcher's (action MAIN, category LAUNCHER), so pin those down.
 */
class FullscreenActivityResumeIntentTest {

    @Test
    fun resumeIntentMatchesTheLauncherIntent() {
        val intent = mock(Intent::class.java, RETURNS_SELF)

        assertSame(intent, FullscreenActivity.asResumeIntent(intent))

        verify(intent).setAction(Intent.ACTION_MAIN)
        verify(intent).addCategory(Intent.CATEGORY_LAUNCHER)
        verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
