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

package com.simplecoil.simplecoil

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Process
import android.util.Log
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quits the app, from the Quit action on the blaster and network notifications: tells the server
 * we are leaving, disconnects the blaster, stops every service, closes the screens and ends the
 * process, so the next launch starts clean rather than from a half-torn-down session.
 *
 * Main thread.
 */
class AppQuitter(
    private val app: Application,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val net: NetworkSessionGateway,
    private val ble: BleConnectionManager,
    private val server: ServerController,
) {
    private var quitting = false

    fun quit() {
        if (quitting) return
        quitting = true
        Log.d(TAG, "Quitting")
        val client = net.tcpClient
        client?.leaveServer() // LEAVE is the last frame written, then the client stops
        net.udp?.stopListen()

        ble.cancelScanTimeout()
        ble.stopScan()
        ble.reset() // disconnects, closes and stops BluetoothLeService
        server.stop()

        // Finishes every screen and takes the task out of recents, so nothing comes back on relaunch
        app.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() }

        scope.launch {
            // The writer thread is a daemon: give the LEAVE a moment to go out before the process ends
            if (client != null && !withContext(ioDispatcher) { client.awaitPendingWrites(LEAVE_WAIT_MILLIS) }) {
                Log.w(TAG, "LEAVE not written in time")
            }
            net.unbindServices()
            // UDPListenerService is sticky: stopped first, so it isn't restarted after the process ends
            app.stopService(Intent(app, TcpClient::class.java))
            app.stopService(Intent(app, UDPListenerService::class.java))
            app.getSystemService(NotificationManager::class.java)?.cancelAll()
            Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val TAG = "AppQuitter"
        const val LEAVE_WAIT_MILLIS = 1000L
    }
}
