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

package com.simplecoil.simplecoil.network

import android.util.Log
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process events in place of the NETMSG_* broadcasts (network design §6, N8). Publish from any
 * thread; events from one thread arrive in the order that thread published them. Nothing is
 * replayed: an event nobody collects is dropped, like a broadcast with no receiver.
 */
class EventBus<E : Any>(capacity: Int = 256, private val name: String = "EventBus") {

    private val flow = MutableSharedFlow<E>(extraBufferCapacity = capacity)
    private val droppedCount = AtomicInteger()

    val events: SharedFlow<E> = flow.asSharedFlow()

    /** Events a slow collector made us drop. */
    val dropped: Int get() = droppedCount.get()

    /** Never blocks. When a collector is [capacity] events behind, the event is dropped and logged. */
    fun publish(event: E) {
        if (!flow.tryEmit(event)) {
            droppedCount.incrementAndGet()
            Log.w(TAG, "$name dropped $event")
        }
    }

    /**
     * Delivers the events to [listener] while [owner] is at least [state], for the Java screens.
     * On Dispatchers.Main, not Main.immediate: like a broadcast, an event is always handled on a
     * later turn of the main loop, never inside the call that published it (§6.2).
     */
    fun observe(owner: LifecycleOwner, state: Lifecycle.State, listener: Consumer<E>) {
        owner.lifecycleScope.launch(Dispatchers.Main) {
            owner.repeatOnLifecycle(state) {
                events.collect { listener.accept(it) }
            }
        }
    }

    private companion object {
        const val TAG = "EventBus"
    }
}
