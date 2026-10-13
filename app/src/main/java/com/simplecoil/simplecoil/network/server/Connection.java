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

package com.simplecoil.simplecoil.network.server;

import android.util.Log;

import com.simplecoil.simplecoil.network.FrameReader;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * One per accepted socket. Has no game state. Keyed in {@link GameRoster} by its connection id,
 * which comes from the accept counter and never changes.
 *
 * <p>Every frame goes out through one writer thread, in the order it was sent (N1). Sending never
 * blocks the caller (N2): a peer that stops reading fills a bounded queue, and the connection is
 * closed once the queue is full, which the reader then handles as a drop.
 */
public final class Connection {
    private static final String TAG = "TCPServer";
    private static final String PING = "ping";
    /** Frames waiting to be written; more than two minutes of game and GPS traffic (Q1). */
    public static final int MAX_QUEUED_FRAMES = 256;
    /** Queued after the last frame by {@link #closeAfterPending()}. Compared by identity. */
    private static final String CLOSE = new String("close");

    private final int connectionId;
    private final Socket socket;
    /** Used by the connection's reader thread only. */
    private FrameReader reader;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUED_FRAMES);
    private volatile boolean open;
    private boolean closed;
    private Thread writer;
    /** The player this connection speaks for; null until its hello is processed. */
    volatile PlayerRecord player;

    /** [socket] may be null (tests): the connection then has no streams and sends nothing. */
    public Connection(int connectionId, Socket socket) {
        this.connectionId = connectionId;
        this.socket = socket;
        if (socket == null) return;
        final DataOutputStream out;
        try {
            reader = new FrameReader(socket.getInputStream());
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        } catch (IOException e) {
            Log.w(TAG, "Could not open connection " + connectionId, e);
            return;
        }
        open = true;
        writer = new Thread(new Runnable() {
            public void run() {
                writeLoop(out);
            }
        }, "sc-write-" + connectionId);
        writer.setDaemon(true);
        writer.start();
    }

    public int getConnectionId() {
        return connectionId;
    }

    public PlayerRecord getPlayer() {
        return player;
    }

    public InetAddress getInetAddress() {
        return socket != null ? socket.getInetAddress() : null;
    }

    /**
     * Queues [message] for the writer and returns at once. False if the connection is closed, or
     * its queue was full: the connection is then closed.
     */
    public boolean send(String message) {
        if (!open) return false;
        if (queue.offer(message)) return true;
        Log.w(TAG, "Connection " + connectionId + " is not reading; closing it");
        close();
        return false;
    }

    public boolean isOpen() {
        return open;
    }

    /** Writes the queued frames, in order, until the connection closes. */
    private void writeLoop(DataOutputStream out) {
        try {
            while (open) {
                String message = queue.take();
                if (message == CLOSE)
                    break;
                out.writeUTF(message);
                if (queue.isEmpty())
                    out.flush();
                if (!message.equals(PING))
                    Log.i(TAG, "sent(" + connectionId + "): " + message);
            }
            out.flush();
        } catch (InterruptedException e) {
            // closed
        } catch (IOException e) {
            if (open) Log.w(TAG, "Write to connection " + connectionId + " failed", e);
        } finally {
            close();
        }
    }

    /**
     * The next complete frame, or null if the socket's read timeout came first. For the
     * connection's reader thread only.
     *
     * @throws java.io.IOException when the connection is closed or broken
     */
    public String poll() throws IOException {
        FrameReader input = reader;
        if (input == null || !open) throw new IOException("closed");
        return input.poll();
    }

    /** Milliseconds since the peer last sent a byte. */
    public long silenceMillis() {
        FrameReader input = reader;
        return input != null ? input.silenceMillis() : 0;
    }

    /** Closes the connection once the frames queued so far have been written. */
    public void closeAfterPending() {
        if (!open) return;
        if (!queue.offer(CLOSE))
            close();
    }

    /** Closes the socket now. Frames still queued are dropped. */
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            open = false;
        }
        if (socket == null) return;
        try { socket.close(); } catch (IOException e) {/*do nothing*/}
        if (writer != null && writer != Thread.currentThread())
            writer.interrupt();
        queue.clear();
    }
}
