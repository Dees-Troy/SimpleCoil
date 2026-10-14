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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException

/** FrameReader keeps partial frames across read timeouts (network design §5.3). */
class FrameReaderTest {

    private fun utf(vararg messages: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        messages.forEach { out.writeUTF(it) }
        return bytes.toByteArray()
    }

    /** Hands out [chunks] one read at a time, timing out between them like a socket with SO_TIMEOUT. */
    private class ChunkedStream(chunks: List<ByteArray>) : InputStream() {
        private val pending = ArrayDeque(chunks)
        private var current: ByteArray? = null
        private var offset = 0
        private var timeoutNext = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (current == null) {
                if (timeoutNext) {
                    timeoutNext = false
                    throw SocketTimeoutException()
                }
                current = pending.removeFirstOrNull() ?: return -1
                offset = 0
            }
            val chunk = current!!
            val n = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, n)
            offset += n
            if (offset == chunk.size) {
                current = null
                timeoutNext = true
            }
            return n
        }
    }

    private class FakeClock(var now: Long = 1_000) : FrameReader.Clock {
        override fun nowMillis() = now
    }

    @Test
    fun wholeFrames_areReadOneAtATime() {
        val reader = FrameReader(ChunkedStream(listOf(utf("one", "two", ""))))
        assertEquals("one", reader.poll())
        assertEquals("two", reader.poll())
        assertEquals("", reader.poll())
    }

    @Test
    fun aFrameSplitAcrossTimeouts_isKeptAndCompleted() {
        val bytes = utf("SimpleCoil:07JSON{\"players\":[]}")
        val chunks = listOf(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, 5), bytes.copyOfRange(5, bytes.size))
        val reader = FrameReader(ChunkedStream(chunks))
        assertNull(reader.poll())
        assertNull(reader.poll())
        assertEquals("SimpleCoil:07JSON{\"players\":[]}", reader.poll())
    }

    @Test
    fun nonAscii_decodesLikeReadUtf() {
        val reader = FrameReader(ChunkedStream(listOf(utf("Zoë ✓ \u0000"))))
        assertEquals("Zoë ✓ \u0000", reader.poll())
    }

    @Test(expected = EOFException::class)
    fun endOfStream_isAnIOException() {
        FrameReader(ChunkedStream(emptyList())).poll()
    }

    @Test(expected = EOFException::class)
    fun endOfStreamMidFrame_isAnIOException() {
        val bytes = utf("hello")
        val reader = FrameReader(ChunkedStream(listOf(bytes.copyOfRange(0, 3))))
        assertNull(reader.poll())
        reader.poll()
    }

    @Test
    fun silence_isCountedFromTheLastByte_evenMidFrame() {
        val clock = FakeClock()
        val bytes = utf("hello")
        val reader = FrameReader(ChunkedStream(listOf(bytes.copyOfRange(0, 3), bytes.copyOfRange(3, bytes.size))), clock)
        clock.now = 1_500
        assertEquals(500, reader.silenceMillis())
        assertNull(reader.poll()) // half a frame arrives at 1500
        clock.now = 5_000
        assertEquals(3_500, reader.silenceMillis())
        assertEquals("hello", reader.poll())
        assertEquals(5_000, reader.lastByteAtMillis())
    }
}
