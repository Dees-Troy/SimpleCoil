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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * android.os.CountDownTimer on coroutines, with the same tick arithmetic so displayed numbers and
 * tick counts match: the first tick is posted (not run inline), each tick gets the milliseconds
 * left by [clock], the next tick comes one [intervalMillis] after the previous one started (or
 * when time is up, whichever is first), then [onFinish]. Cancel the returned job to stop it.
 */
fun CoroutineScope.countDown(
    clock: () -> Long,
    totalMillis: Long,
    intervalMillis: Long,
    onTick: (millisLeft: Long) -> Unit,
    onFinish: () -> Unit,
): Job {
    val stopAt = clock() + totalMillis
    return launch {
        yield() // CountDownTimer.start() posts its first message
        while (true) {
            val millisLeft = stopAt - clock()
            if (millisLeft <= 0) {
                onFinish()
                return@launch
            }
            val tickStart = clock()
            onTick(millisLeft)
            val tickDuration = clock() - tickStart
            var next: Long
            if (millisLeft < intervalMillis) {
                next = millisLeft - tickDuration
                if (next < 0) next = 0
            } else {
                next = intervalMillis - tickDuration
                while (next < 0) next += intervalMillis
            }
            delay(next)
        }
    }
}
