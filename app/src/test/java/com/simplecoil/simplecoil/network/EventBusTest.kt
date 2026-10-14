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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** The in-process event bus (network design §6.5). */
class EventBusTest {

    private fun ordering(bus: EventBus<Pair<Int, Int>>) = runBlocking {
        val received = mutableListOf<Pair<Int, Int>>()
        // Undispatched: subscribed before the publishers start, so nothing is missed
        val collector = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            bus.events.take(2 * PER_THREAD).toList(received)
        }
        val go = CountDownLatch(1)
        val publishers = (0..1).map { t ->
            thread {
                go.await()
                for (i in 0 until PER_THREAD) bus.publish(t to i)
            }
        }
        go.countDown()
        publishers.forEach { it.join() }
        withTimeout(5000) { collector.join() }
        received
    }

    @Test
    fun eventsFromTwoThreads_arriveInEachThreadsOrder() {
        val bus = EventBus<Pair<Int, Int>>(capacity = 2 * PER_THREAD)
        val received = ordering(bus)
        assertEquals(2 * PER_THREAD, received.size)
        for (t in 0..1) assertEquals((0 until PER_THREAD).toList(), received.filter { it.first == t }.map { it.second })
        assertEquals(0, bus.dropped)
    }

    @Test
    fun withoutACollector_eventsAreDiscardedNotCountedAsDropped() {
        val bus = EventBus<Int>(capacity = 2)
        repeat(10) { bus.publish(it) }
        assertEquals(0, bus.dropped)
    }

    @Test
    fun aCollectorThatFallsBehind_dropsTheOverflowInsteadOfBlocking() = runBlocking {
        val bus = EventBus<Int>(capacity = 2)
        val received = mutableListOf<Int>()
        // Subscribed, but it can't run until this coroutine suspends
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.events.take(2).toList(received) }
        repeat(5) { bus.publish(it) }
        assertEquals(3, bus.dropped)
        collector.join()
        assertEquals(listOf(0, 1), received)
    }

    private companion object {
        const val PER_THREAD = 1000
    }
}
