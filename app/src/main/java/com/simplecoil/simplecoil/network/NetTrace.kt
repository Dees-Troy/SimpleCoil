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

import android.os.SystemClock
import android.util.Log
import com.simplecoil.simplecoil.BuildConfig

/**
 * Debug-only log of every TCP frame sent and received but the pings (network design N9), in the
 * style of ProtocolTrace. Release builds skip it entirely: GPS alone used to log every second.
 *
 * Capture with `adb logcat -s SCNet:D`. Each line is `<elapsedRealtime ms> <TX|RX> <peer> <frame>`,
 * where the peer is `server` on a client and `c<connection id>` on the server.
 */
object NetTrace {
    const val TAG = "SCNet"

    /** A frame written to [peer]. */
    @JvmStatic
    fun sent(peer: String, frame: String) {
        if (BuildConfig.DEBUG && !isPing(frame)) log("TX $peer $frame")
    }

    /** A frame read from [peer]. */
    @JvmStatic
    fun received(peer: String, frame: String) {
        if (BuildConfig.DEBUG && !isPing(frame)) log("RX $peer $frame")
    }

    private fun isPing(frame: String) = frame == WireProtocol.PING || frame == WireProtocol.PONG

    private fun log(line: String) {
        Log.d(TAG, "${SystemClock.elapsedRealtime()} $line")
    }
}
