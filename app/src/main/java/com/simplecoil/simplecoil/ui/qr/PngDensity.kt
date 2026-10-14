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

package com.simplecoil.simplecoil.ui.qr

import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * Stamps a print density into a PNG. Bitmap.compress writes no pHYs chunk, so printers and image
 * viewers would guess the size; with one, the saved QR label prints at its intended size.
 */
object PngDensity {
    private const val SIGNATURE_LENGTH = 8
    private const val IHDR_END = SIGNATURE_LENGTH + 4 + 4 + 13 + 4 // length, type, data, CRC
    private const val METERS_PER_INCH = 0.0254

    /** [png] with a pHYs chunk of [dpi] right after IHDR (where the spec wants it, before IDAT). */
    fun withDpi(png: ByteArray, dpi: Double): ByteArray {
        require(png.size > IHDR_END && String(png, 12, 4, Charsets.US_ASCII) == "IHDR") { "Not a PNG" }
        val pixelsPerMeter = (dpi / METERS_PER_INCH).roundToInt()
        val data = ByteBuffer.allocate(9).putInt(pixelsPerMeter).putInt(pixelsPerMeter).put(1).array() // 1 = meters
        val type = "pHYs".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }
        val chunk = ByteBuffer.allocate(4 + 4 + data.size + 4)
            .putInt(data.size).put(type).put(data).putInt(crc.value.toInt())
            .array()
        return png.copyOfRange(0, IHDR_END) + chunk + png.copyOfRange(IHDR_END, png.size)
    }
}
