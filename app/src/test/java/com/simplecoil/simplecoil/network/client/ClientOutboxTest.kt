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

package com.simplecoil.simplecoil.network.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientOutboxTest {

    private val outbox = ClientOutbox()

    @Test
    fun seq_startsAtOneForEachGame() {
        assertEquals(1, outbox.add("ELIMINATED3", 100).seq)
        assertEquals(2, outbox.add("ELIMINATED3", 100).seq)
        assertEquals(100L, outbox.gameId)
        // A new game voids the old entries and starts over.
        assertEquals(1, outbox.add("ELIMINATED9", 200).seq)
        assertEquals(listOf(ClientOutbox.Entry(1, "ELIMINATED9")), outbox.pending())
    }

    @Test
    fun ack_isCumulative_andAnEntryStaysUntilAcked() {
        repeat(4) { outbox.add("ELIMINATED$it", 7) }
        outbox.ack(0)
        assertEquals(4, outbox.pending().size)
        outbox.ack(2)
        assertEquals(listOf(3, 4), outbox.pending().map { it.seq })
        outbox.ack(2) // a late duplicate ack changes nothing
        assertEquals(listOf(3, 4), outbox.pending().map { it.seq })
        outbox.ack(10)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(5, outbox.add("ELIMINATED1", 7).seq) // numbering continues within the game
    }

    @Test
    fun aDifferentGameIdFromTheServer_clearsTheOutbox() {
        outbox.add("ELIMINATED3", 100)
        outbox.onGameId(100)
        assertEquals(1, outbox.pending().size)
        outbox.onGameId(0) // the server was reset
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0L, outbox.gameId)
    }

    @Test
    fun capacity_dropsTheOldest() {
        val small = ClientOutbox(capacity = 3)
        repeat(5) { small.add("ELIMINATED$it", 1) }
        assertEquals(listOf(3, 4, 5), small.pending().map { it.seq })
    }

    @Test
    fun clear_forgetsEverything() {
        outbox.add("ELIMINATED3", 100)
        outbox.clear()
        assertTrue(outbox.pending().isEmpty())
        assertEquals(0L, outbox.gameId)
        assertEquals(1, outbox.add("ELIMINATED3", 100).seq)
    }

    @Test
    fun reconcile_addsTheEliminationsTheServerHasNotApplied() {
        // §3.5: eliminations = update.eliminated + entries with seq > ackseq.
        repeat(3) { outbox.add("ELIMINATED7", 5) } // seq 1..3
        // The snapshot covers 1 and 2; 3 was recorded between the hello and the snapshot.
        assertEquals(4 + 1, outbox.reconcile(4, 2))
        assertEquals(listOf(3), outbox.pending().map { it.seq })
        // Everything applied: just the server's number.
        assertEquals(5, outbox.reconcile(5, 3))
        assertTrue(outbox.pending().isEmpty())
    }
}
