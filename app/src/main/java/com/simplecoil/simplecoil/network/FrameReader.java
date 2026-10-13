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

package com.simplecoil.simplecoil.network;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * Reads {@code writeUTF} frames from a socket with a read timeout ({@code SO_TIMEOUT}). A timeout
 * in the middle of a frame keeps the bytes already read, so a half-received frame no longer
 * blocks the reader until TCP gives up (N3, N4), and silence is measured from the last byte.
 */
public final class FrameReader {
    /** Milliseconds from a monotonic clock. */
    public interface Clock {
        long nowMillis();
    }

    /**
     * {@code System.nanoTime}: monotonic, and it works in JVM tests, where
     * {@code SystemClock.elapsedRealtime} is a stub that returns 0.
     */
    public static final Clock MONOTONIC = new Clock() {
        @Override
        public long nowMillis() {
            return System.nanoTime() / 1_000_000L;
        }
    };

    private final InputStream in;
    private final Clock clock;
    private final byte[] header = new byte[2];
    private int headerRead = 0;
    private byte[] body = null;
    private int bodyRead = 0;
    private volatile long lastByteAt;

    public FrameReader(InputStream in) {
        this(in, MONOTONIC);
    }

    public FrameReader(InputStream in, Clock clock) {
        this.in = in;
        this.clock = clock;
        this.lastByteAt = clock.nowMillis();
    }

    /**
     * A complete frame, or null if the socket timed out first. Partial bytes are kept for the
     * next call.
     *
     * @throws EOFException at the end of the stream
     * @throws IOException on a read error, or a frame that isn't valid modified UTF-8
     */
    public String poll() throws IOException {
        while (headerRead < header.length) {
            int n = read(header, headerRead, header.length - headerRead);
            if (n < 0)
                return null;
            headerRead += n;
        }
        if (body == null) {
            body = new byte[((header[0] & 0xff) << 8) | (header[1] & 0xff)];
            bodyRead = 0;
        }
        while (bodyRead < body.length) {
            int n = read(body, bodyRead, body.length - bodyRead);
            if (n < 0)
                return null;
            bodyRead += n;
        }
        byte[] frame = new byte[header.length + body.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(body, 0, frame, header.length, body.length);
        headerRead = 0;
        body = null;
        bodyRead = 0;
        // The same decoding as reading the socket with DataInputStream.readUTF
        return new DataInputStream(new ByteArrayInputStream(frame)).readUTF();
    }

    /** {@link Clock#nowMillis()} when the last byte arrived (or when this reader was created). */
    public long lastByteAtMillis() {
        return lastByteAt;
    }

    /** Milliseconds since the last byte arrived. */
    public long silenceMillis() {
        return clock.nowMillis() - lastByteAt;
    }

    /** Bytes read, or -1 if the read timed out. */
    private int read(byte[] buffer, int offset, int length) throws IOException {
        int n;
        try {
            n = in.read(buffer, offset, length);
        } catch (SocketTimeoutException e) {
            return -1;
        }
        if (n < 0)
            throw new EOFException();
        if (n > 0)
            lastByteAt = clock.nowMillis();
        return n;
    }
}
