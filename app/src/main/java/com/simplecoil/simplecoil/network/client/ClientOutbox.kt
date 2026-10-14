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

import android.util.Log
import com.simplecoil.simplecoil.network.WireProtocol

/**
 * The client's reliable messages that the server has not acknowledged yet (reconnect design
 * §3.4). Each gets a sequence number, from 1 for each game, and stays until a cumulative ACK
 * covers it, however the connection fails in between. Unacknowledged entries are replayed in the
 * next hello. Thread-safe.
 */
class ClientOutbox(private val capacity: Int = CAPACITY) {

    data class Entry(val seq: Int, val rmsg: String)

    private val entries = ArrayDeque<Entry>()
    private var nextSeq = 1

    /** The game the entries belong to; 0 before the first game. */
    @get:Synchronized
    var gameId: Long = 0
        private set

    /** Adds [rmsg] for game [currentGameId] (a new game empties the outbox first). */
    @Synchronized
    fun add(rmsg: String, currentGameId: Long): Entry {
        onGameId(currentGameId)
        val entry = Entry(nextSeq++, rmsg)
        entries.addLast(entry)
        while (entries.size > capacity) {
            Log.w(TAG, "Outbox full, dropping " + entries.first())
            entries.removeFirst()
        }
        return entry
    }

    /** The server applied everything up to [seq]. */
    @Synchronized
    fun ack(seq: Int) {
        while (entries.isNotEmpty() && entries.first().seq <= seq) entries.removeFirst()
    }

    /**
     * The server reported game [id]. A different game (a new one, or 0 after a reset) makes the
     * entries meaningless, so they are dropped and numbering starts over.
     */
    @Synchronized
    fun onGameId(id: Long) {
        if (id == gameId) return
        entries.clear()
        nextSeq = 1
        gameId = id
    }

    /** The player left the server. */
    @Synchronized
    fun clear() {
        entries.clear()
        nextSeq = 1
        gameId = 0
    }

    @Synchronized
    fun pending(): List<Entry> = entries.toList()

    /**
     * The player's eliminations given the server's count, which includes everything up to
     * [ackSeq]: the server's number plus the eliminations it has not applied yet (§3.5). The
     * player never regains a life it lost, and never loses one twice.
     */
    @Synchronized
    fun reconcile(serverEliminated: Int, ackSeq: Int): Int {
        ack(ackSeq)
        return serverEliminated + entries.count { it.seq > ackSeq && it.rmsg.startsWith(WireProtocol.NETMSG_ELIMINATED) }
    }

    companion object {
        private const val TAG = "ClientOutbox"
        const val CAPACITY = 256
    }
}
