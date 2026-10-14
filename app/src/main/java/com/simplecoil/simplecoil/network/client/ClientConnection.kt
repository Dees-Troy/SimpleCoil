/*
 * Copyright (C) 2018 Ethan Yonker
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

import android.net.Network
import android.util.Log
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.EventBus
import com.simplecoil.simplecoil.network.FrameReader
import com.simplecoil.simplecoil.network.NetTrace
import com.simplecoil.simplecoil.network.WireProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The client's connection to the server: the socket, a [FrameReader] with the liveness check,
 * one writer thread, and the reconnect loop (network design §7.1 step 5).
 *
 * The loop runs as a coroutine on [io], doing blocking socket I/O; a stop closes the socket,
 * which ends a blocking read at once. Wi-Fi coming back wakes the retry wait early (Q4).
 */
class ClientConnection(
    /** Where the server is; read at each connection attempt. */
    private val serverAddress: () -> InetAddress?,
    private val handler: ClientMessageHandler,
    private val events: EventBus<ClientEvent>,
    private val listener: Listener,
    /** The Wi-Fi network while it is up; sockets are bound to it (§3.6). */
    private val network: () -> Network? = { null },
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    interface Listener {
        /** A session is starting, on the caller's thread: show the network notification. */
        fun onStarting()

        /** A new session: forget the last one. On the connection's thread, before connecting. */
        fun onSessionStart()

        /** Connected: say hello, as a rejoin after the session's first connection. */
        fun onConnected(rejoin: Boolean)

        /** The session is over: take the notification down. */
        fun onStopped()
    }

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val reconnectPolicy = ReconnectPolicy()

    /** Wakes the retry wait early: Wi-Fi came back, or the client stopped. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Guards starting and the end of a session, so a client started right after a stop keeps its notification. */
    private val lock = Any()

    @Volatile
    private var keepListening = false

    @Volatile
    private var isListening = false

    /** The connected socket; closed by a queued close (N11). */
    @Volatile
    private var socket: Socket? = null

    private val outLock = Any()
    private var out: DataOutputStream? = null

    /**
     * Every write goes through this one thread, in the order sent (D3). Thread-per-message used
     * to let queued eliminations overtake the hello they followed.
     */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sc-client-write").apply { isDaemon = true } }

    /** Starts a session, unless one is running or still stopping. */
    fun start() {
        synchronized(lock) {
            if (keepListening) {
                Log.d(TAG, "Client is already listening")
                return
            }
            if (isListening) {
                Log.w(TAG, "Please wait for client to stop listening")
                return
            }
            keepListening = true
            isListening = true
            listener.onStarting()
            scope.launch {
                try {
                    run()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Client thread failed", e)
                }
            }
        }
    }

    /** Queues [message] for the writer thread. Without a connection it is dropped. */
    fun send(message: String) {
        writer.execute { write(message) }
    }

    private fun write(message: String) {
        synchronized(outLock) {
            val output = out
            if (output == null) {
                Log.d(TAG, "Writer is null, dropping: $message")
                return
            }
            try {
                output.writeUTF(message)
                output.flush()
                NetTrace.sent("server", message)
            } catch (e: IOException) {
                Log.w(TAG, "Write failed", e)
            }
        }
    }

    /** Wi-Fi is back: retry now, without the backoff. */
    fun onNetworkAvailable() {
        reconnectPolicy.onNetworkAvailable()
        wake.trySend(Unit)
    }

    /**
     * Stops the client once everything sent so far has been written: the stop is queued on the
     * writer, behind the frames already there (N11).
     */
    fun stopAfterPending() {
        try {
            writer.execute { stopNow() }
        } catch (e: RejectedExecutionException) {
            stopNow() // the service is gone, and its writer with it
        }
    }

    private fun stopNow() {
        keepListening = false
        wake.trySend(Unit)
        try {
            socket?.close() // the reader stops at once
        } catch (e: IOException) {
            // already closed
        }
    }

    /** The client is still running, or still stopping. */
    val isRunning: Boolean get() = keepListening

    /** The service is going away. */
    fun shutdown() {
        writer.shutdown()
    }

    private suspend fun run() {
        listener.onSessionStart()
        keepListening = true
        isListening = true
        Log.d(TAG, "Starting client...")
        var rejoin = false
        var wasConnected = false
        var firstAttempt = true
        var gaveUp = false
        var cancelled = false
        reconnectPolicy.onConnected()
        wake.tryReceive()
        while (keepListening && !cancelled) {
            if (!firstAttempt) {
                // D6, D10: retry for as long as the server could still recognize us, spaced out
                val delay = reconnectPolicy.nextDelay(handler.sessionPhase)
                if (delay == null) {
                    gaveUp = true
                    break
                }
                withTimeoutOrNull(delay) { wake.receive() }
                if (!keepListening) break
            }
            firstAttempt = false
            var s: Socket? = null
            try {
                if (wasConnected) {
                    wasConnected = false
                    events.publish(ClientEvent.NetworkDisconnected)
                }
                s = Socket()
                socket = s
                bindToWifi(s)
                s.connect(InetSocketAddress(serverAddress(), WireProtocol.TCP_SERVER_PORT), CONNECTION_TIMEOUT_MS)
                s.soTimeout = READ_WAIT_MS
                val reader = FrameReader(s.getInputStream())
                synchronized(outLock) {
                    out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                }
                listener.onConnected(rejoin)
                wasConnected = true
                if (rejoin) events.publish(ClientEvent.NetworkConnected)
                rejoin = true
                reconnectPolicy.onConnected()
                while (keepListening) {
                    // Times out after 100 ms; a half-received frame waits for the next poll (N4)
                    val message = reader.poll()
                    if (message != null) {
                        val ended = try {
                            handleFrame(message)
                        } catch (e: RuntimeException) {
                            // Skip the frame and keep reading (N5)
                            Log.e(TAG, "Error handling '$message'", e)
                            false
                        }
                        if (ended) {
                            cancelled = true
                            break
                        }
                    } else if (reader.silenceMillis() >= SILENCE_DROP_MS) {
                        Log.d(TAG, "no ping from server, disconnecting")
                        break
                    }
                }
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unknown server address", e)
            } catch (e: ConnectException) {
                Log.d(TAG, "Server not running: " + e.localizedMessage)
            } catch (e: IOException) {
                Log.w(TAG, "Connection failed", e)
            } catch (e: RuntimeException) {
                // Drop the connection; the reconnect policy decides what happens next (N5)
                Log.e(TAG, "Reader failed, dropping the connection", e)
            } finally {
                synchronized(outLock) {
                    try {
                        out?.close()
                    } catch (e: Exception) {
                        // ignored
                    }
                    out = null
                }
                socket = null
                try {
                    s?.close()
                } catch (e: Exception) {
                    // ignored
                }
            }
        }
        if (gaveUp && keepListening) {
            events.publish(ClientEvent.ServerCancel)
        }
        Log.d(TAG, "Client stopping")
        synchronized(lock) {
            listener.onStopped()
            keepListening = false
            isListening = false
        }
    }

    /** One frame from the server; true when the server ended the session. */
    private fun handleFrame(raw: String): Boolean {
        if (raw == WireProtocol.PING) {
            send(WireProtocol.PONG)
            return false
        }
        return handler.handle(raw)
    }

    /** Routes [socket] over Wi-Fi even when Android made mobile data the default network. */
    private fun bindToWifi(socket: Socket) {
        val wifi = network() ?: return
        try {
            wifi.bindSocket(socket)
        } catch (e: IOException) {
            Log.w(TAG, "Could not bind to Wi-Fi: " + e.message)
        }
    }

    /** For tests: writes go to [output] as if connected. */
    internal fun attachOutputForTest(output: DataOutputStream?) {
        synchronized(outLock) { out = output }
    }

    /** For tests: waits until everything sent so far has been written (or dropped). */
    internal fun awaitWritesForTest() {
        writer.submit {}.get()
    }

    companion object {
        private const val TAG = "TCPClient"
        private const val CONNECTION_TIMEOUT_MS = 1000

        /** Read timeout: the granularity of the liveness check (network design Q5). */
        private const val READ_WAIT_MS = 100

        /** No byte from the server for this long: the connection is dead. */
        const val SILENCE_DROP_MS = 3500L
    }
}
