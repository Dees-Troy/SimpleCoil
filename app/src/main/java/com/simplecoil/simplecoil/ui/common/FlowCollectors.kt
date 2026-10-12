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
package com.simplecoil.simplecoil.ui.common

import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Flow collection for Java callers. */
object FlowCollectors {

    /**
     * Collects [flow] on the main thread whenever [owner] is at least [state], stopping below it.
     * With [Lifecycle.State.RESUMED] this matches a receiver registered in onResume and
     * unregistered in onPause.
     */
    @JvmStatic
    fun <T> collectWhileAtLeast(owner: LifecycleOwner, state: Lifecycle.State, flow: Flow<T>, consumer: Consumer<T>) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(state) {
                flow.collect { consumer.accept(it) }
            }
        }
    }
}
