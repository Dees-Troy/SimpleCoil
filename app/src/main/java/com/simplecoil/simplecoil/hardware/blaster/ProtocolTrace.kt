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

package com.simplecoil.simplecoil.hardware.blaster

import android.os.SystemClock
import android.util.Log
import com.simplecoil.simplecoil.BuildConfig
import com.simplecoil.simplecoil.GattAttributes
import java.util.UUID

/**
 * Debug-only, read-only log of every GATT operation and inbound packet, used to compare the
 * BLE traffic of a refactored build against a baseline capture (REFACTOR_DESIGN.md §3.0).
 *
 * Capture with `adb logcat -s SCProto:D`. Each line is
 * `<elapsedRealtime ms> <direction> <target> [extra] <hex bytes>`. Nothing here changes what is
 * sent or when; release builds skip it entirely.
 */
object ProtocolTrace {
    const val TAG = "SCProto"

    /** Outbound characteristic write, logged as the bytes and write type handed to the stack. */
    @JvmStatic
    fun write(uuid: UUID?, value: ByteArray?, writeType: Int) {
        if (BuildConfig.DEBUG) log("TX ${name(uuid)} wt=$writeType ${hex(value)}")
    }

    /** Outbound descriptor write (CCCD). */
    @JvmStatic
    fun descriptorWrite(uuid: UUID?, value: ByteArray?) {
        if (BuildConfig.DEBUG) log("TXD ${name(uuid)} ${hex(value)}")
    }

    /** Outbound characteristic read request. */
    @JvmStatic
    fun read(uuid: UUID?) {
        if (BuildConfig.DEBUG) log("RD ${name(uuid)}")
    }

    /** Inbound notification or read result. */
    @JvmStatic
    fun receive(uuid: UUID?, value: ByteArray?) {
        if (BuildConfig.DEBUG) log("RX ${name(uuid)} ${hex(value)}")
    }

    /** Connection-level events (connect, discover, write-finished, ...). */
    @JvmStatic
    fun event(description: String) {
        if (BuildConfig.DEBUG) log("EV $description")
    }

    @JvmStatic
    fun hex(value: ByteArray?): String =
        value?.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) } ?: "null"

    @JvmStatic
    fun name(uuid: UUID?): String = when (uuid?.toString()) {
        null -> "null"
        GattAttributes.RECOIL_TELEMETRY_UUID -> "TELEMETRY"
        GattAttributes.RECOIL_COMMAND_UUID -> "COMMAND"
        GattAttributes.RECOIL_CONFIG_UUID -> "CONFIG"
        GattAttributes.RECOIL_ID_UUID -> "ID"
        GattAttributes.CLIENT_CHARACTERISTIC_CONFIG -> "CCCD"
        else -> uuid.toString()
    }

    private fun log(line: String) {
        Log.d(TAG, "${SystemClock.elapsedRealtime()} $line")
    }
}
