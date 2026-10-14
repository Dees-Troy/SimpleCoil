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

package com.simplecoil.simplecoil.network.server

import android.util.Log
import com.simplecoil.simplecoil.network.FrameReader
import com.simplecoil.simplecoil.network.NetTrace
import com.simplecoil.simplecoil.network.WireProtocol
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue

/**
 * One per accepted socket. Has no game state. Keyed in [GameRoster] by its connection id,
 * which comes from the accept counter and never changes.
 *
 * Every frame goes out through one writer thread, in the order it was sent (N1). Sending never
 * blocks the caller (N2): a peer that stops reading fills a bounded queue, and the connection is
 * closed once the queue is full, which the reader then handles as a drop.
 *
 * [socket] may be null (tests): the connection then has no streams and sends nothing.
 */
class Connection(val connectionId: Int, private val socket: Socket?) {
    /** Used by the connection's reader thread only. */
    private var reader: FrameReader? = null
    private val queue = ArrayBlockingQueue<String>(MAX_QUEUED_FRAMES)

    @Volatile
    var isOpen = false
        private set
    private var closed = false
    private var writer: Thread? = null

    /** The player this connection speaks for; null until its hello is processed. */
    @Volatile
    var player: PlayerRecord? = null
        internal set

    init {
        if (socket != null) {
            try {
                reader = FrameReader(socket.getInputStream())
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                isOpen = true
                writer = Thread({ writeLoop(out) }, "sc-write-$connectionId").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not open connection $connectionId", e)
            }
        }
    }

    val inetAddress: InetAddress? get() = socket?.inetAddress

    /**
     * Queues [message] for the writer and returns at once. False if the connection is closed, or
     * its queue was full: the connection is then closed.
     */
    fun send(message: String): Boolean {
        if (!isOpen) return false
        if (queue.offer(message)) return true
        Log.w(TAG, "Connection $connectionId is not reading; closing it")
        close()
        return false
    }

    /** Writes the queued frames, in order, until the connection closes. */
    private fun writeLoop(out: DataOutputStream) {
        try {
            while (isOpen) {
                val message = queue.take()
                if (message === CLOSE) break
                out.writeUTF(message)
                if (queue.isEmpty()) out.flush()
                NetTrace.sent("c$connectionId", message)
            }
            out.flush()
        } catch (e: InterruptedException) {
            // closed
        } catch (e: IOException) {
            if (isOpen) Log.w(TAG, "Write to connection $connectionId failed", e)
        } finally {
            close()
        }
    }

    /**
     * The next complete frame, or null if the socket's read timeout came first. For the
     * connection's reader thread only.
     *
     * @throws IOException when the connection is closed or broken
     */
    @Throws(IOException::class)
    fun poll(): String? {
        val input = reader
        if (input == null || !isOpen) throw IOException("closed")
        return input.poll()
    }

    /** Milliseconds since the peer last sent a byte. */
    fun silenceMillis(): Long = reader?.silenceMillis() ?: 0

    /** Closes the connection once the frames queued so far have been written. */
    fun closeAfterPending() {
        if (!isOpen) return
        if (!queue.offer(CLOSE)) close()
    }

    /** Closes the socket now. Frames still queued are dropped. */
    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            isOpen = false
        }
        if (socket == null) return
        try {
            socket.close()
        } catch (e: IOException) {
            // do nothing
        }
        val thread = writer
        if (thread != null && thread !== Thread.currentThread()) thread.interrupt()
        queue.clear()
    }

    companion object {
        private const val TAG = "TCPServer"

        /** Frames waiting to be written; more than two minutes of game and GPS traffic (Q1). */
        const val MAX_QUEUED_FRAMES = 256

        /** Queued after the last frame by [closeAfterPending]. Compared by identity. */
        @Suppress("StringOperationCanBeSimplified")
        private val CLOSE = String("close".toCharArray())
    }
}
