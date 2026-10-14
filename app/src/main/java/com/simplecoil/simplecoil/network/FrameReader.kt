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

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * Reads `writeUTF` frames from a socket with a read timeout (`SO_TIMEOUT`). A timeout in the
 * middle of a frame keeps the bytes already read, so a half-received frame no longer blocks the
 * reader until TCP gives up (N3, N4), and silence is measured from the last byte.
 */
class FrameReader @JvmOverloads constructor(
    private val input: InputStream,
    private val clock: Clock = MONOTONIC,
) {
    /** Milliseconds from a monotonic clock. */
    fun interface Clock {
        fun nowMillis(): Long
    }

    private val header = ByteArray(2)
    private var headerRead = 0
    private var body: ByteArray? = null
    private var bodyRead = 0

    @Volatile
    private var lastByteAt: Long = clock.nowMillis()

    /**
     * A complete frame, or null if the socket timed out first. Partial bytes are kept for the
     * next call.
     *
     * @throws EOFException at the end of the stream
     * @throws IOException on a read error, or a frame that isn't valid modified UTF-8
     */
    @Throws(IOException::class)
    fun poll(): String? {
        while (headerRead < header.size) {
            val n = read(header, headerRead, header.size - headerRead)
            if (n < 0) return null
            headerRead += n
        }
        val frameBody = body ?: ByteArray(((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)).also {
            body = it
            bodyRead = 0
        }
        while (bodyRead < frameBody.size) {
            val n = read(frameBody, bodyRead, frameBody.size - bodyRead)
            if (n < 0) return null
            bodyRead += n
        }
        val frame = header + frameBody
        headerRead = 0
        body = null
        bodyRead = 0
        // The same decoding as reading the socket with DataInputStream.readUTF
        return DataInputStream(ByteArrayInputStream(frame)).readUTF()
    }

    /** [Clock.nowMillis] when the last byte arrived (or when this reader was created). */
    fun lastByteAtMillis(): Long = lastByteAt

    /** Milliseconds since the last byte arrived. */
    fun silenceMillis(): Long = clock.nowMillis() - lastByteAt

    /** Bytes read, or -1 if the read timed out. */
    private fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = try {
            input.read(buffer, offset, length)
        } catch (e: SocketTimeoutException) {
            return -1
        }
        if (n < 0) throw EOFException()
        if (n > 0) lastByteAt = clock.nowMillis()
        return n
    }

    companion object {
        /**
         * `System.nanoTime`: monotonic, and it works in JVM tests, where
         * `SystemClock.elapsedRealtime` is a stub that returns 0.
         */
        @JvmField
        val MONOTONIC = Clock { System.nanoTime() / 1_000_000L }
    }
}
