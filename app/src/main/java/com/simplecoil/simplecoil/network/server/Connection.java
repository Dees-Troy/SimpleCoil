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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One per accepted socket. Has no game state. Keyed in {@link GameRoster} by its connection id,
 * which comes from the accept counter and never changes.
 */
public final class Connection {
    private static final String TAG = "TCPServer";
    private static final String PING = "ping";

    private final int connectionId;
    private final Object outLock = new Object();
    private final Socket socket;
    private volatile DataInputStream in;
    private DataOutputStream out;
    /** The player this connection speaks for; null until its hello is processed. */
    volatile PlayerRecord player;
    private final AtomicInteger noReadCount = new AtomicInteger(0);

    /** [socket] may be null (tests): the connection then has no streams and sends nothing. */
    public Connection(int connectionId, Socket socket) {
        this.connectionId = connectionId;
        this.socket = socket;
        if (socket == null) return;
        try {
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    /** Writes [message]; false if the connection is closed or the write failed. */
    public boolean send(String message) {
        synchronized (outLock) {
            if (out == null) return false;
            try {
                out.writeUTF(message);
                out.flush();
                if (!message.equals(PING))
                    Log.i(TAG, "sent(" + connectionId + "): " + message);
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    public boolean isOpen() {
        synchronized (outLock) {
            return out != null;
        }
    }

    /** Bytes ready to read; 0 once closed. */
    public int available() throws IOException {
        DataInputStream input = in;
        return input != null ? input.available() : 0;
    }

    public String readUTF() throws IOException {
        DataInputStream input = in;
        if (input == null) throw new IOException("closed");
        return input.readUTF();
    }

    /** Polls without data since the last read. */
    public int incrementNoReadCount() {
        return noReadCount.incrementAndGet();
    }

    public void resetNoReadCount() {
        noReadCount.set(0);
    }

    public void close() {
        synchronized (outLock) {
            if (socket == null) return;
            try { socket.close(); } catch (IOException e) {/*do nothing*/}
            try { if (in != null) in.close(); } catch (IOException e) {/*do nothing*/}
            try { if (out != null) out.close(); } catch (IOException e) {/*do nothing*/}
            in = null;
            out = null;
        }
    }
}
