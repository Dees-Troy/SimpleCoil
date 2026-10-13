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

/**
 * QR pairing payload: `MAC` or `MAC-PlayerName`. Extracted verbatim from
 * FullscreenActivity.processQrResult().
 */
data class QrPairingPayload(val macAddress: String, val playerName: String) {
    companion object {
        private const val MAC_LENGTH = 17

        /** Returns null when the payload is empty or has no MAC part. [playerName] is "" when absent. */
        @JvmStatic
        fun parse(rawQr: String?): QrPairingPayload? {
            if (rawQr == null || rawQr.javaTrim().isEmpty()) return null
            val raw = rawQr.javaTrim()
            val macAddress: String
            var playerName = ""
            if (raw.length > MAC_LENGTH && raw[MAC_LENGTH] == '-') {
                macAddress = raw.substring(0, MAC_LENGTH).javaTrim()
                playerName = raw.substring(MAC_LENGTH + 1).javaTrim()
            } else {
                val dashIndex = raw.indexOf('-')
                if (dashIndex != -1) {
                    macAddress = raw.substring(0, dashIndex).javaTrim()
                    playerName = raw.substring(dashIndex + 1).javaTrim()
                } else {
                    macAddress = raw
                }
            }
            if (macAddress.isEmpty()) return null
            return QrPairingPayload(macAddress, playerName)
        }

        /** java.lang.String.trim() semantics (strips chars <= ' '), not Kotlin's Unicode-whitespace trim. */
        @Suppress("TrimLambda") // Java's String.trim: every char up to ' ', not Kotlin's isWhitespace
        private fun String.javaTrim(): String = trim { it <= ' ' }
    }
}
