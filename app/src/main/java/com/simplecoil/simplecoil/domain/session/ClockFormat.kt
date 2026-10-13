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

import java.util.Locale

/**
 * Game clock text, "MM:SS". Always ASCII digits ([Locale.US]), like the blaster's display,
 * whatever the phone's locale. Shared by the play screen and the dedicated server.
 */
object ClockFormat {
    /** "MM:00" for a time limit of [minutes], shown before a timed game. */
    @JvmStatic
    fun minutes(minutes: Long): String = String.format(Locale.US, "%02d:00", minutes)

    @JvmStatic
    fun minutesSeconds(minutes: Long, seconds: Long): String = String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
