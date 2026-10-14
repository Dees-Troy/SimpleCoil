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

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.RGBLuminanceSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.CRC32

class PngDensityTest {

    /** A 1x1 PNG as Bitmap.compress lays it out: signature, IHDR, IDAT, IEND. */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0,
        0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        0, 0, 0, 0, 0x49, 0x44, 0x41, 0x54, 0x35, 0xAF.toByte(), 0x06, 0x1E,
        0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    @Test
    fun insertsPhysAfterIhdr() {
        val out = PngDensity.withDpi(png, 254.0)
        assertEquals(png.size + 21, out.size)
        assertArrayEquals(png.copyOfRange(0, 33), out.copyOfRange(0, 33))
        val chunk = ByteBuffer.wrap(out, 33, 21)
        assertEquals(9, chunk.int)
        assertEquals("pHYs", String(out, 37, 4, Charsets.US_ASCII))
        chunk.position(41)
        assertEquals(10000, chunk.int) // 254 dpi = 10000 pixels per meter
        assertEquals(10000, chunk.int)
        assertEquals(1, chunk.get().toInt())
        val crc = CRC32().apply { update(out, 37, 13) }
        assertEquals(crc.value.toInt(), chunk.int)
        assertArrayEquals(png.copyOfRange(33, png.size), out.copyOfRange(54, out.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPng() {
        PngDensity.withDpi(ByteArray(40), 300.0)
    }
}

class QrLabelMatrixTest {

    @Test
    fun symbolPrintsOneInchWide() {
        val matrix = QrLabel.matrix("AA:BB:CC:DD:EE:FF-Mary-Jane")
        val dpi = QrLabel.dpi(matrix.width)
        val symbolModules = matrix.width - 2 * QrLabel.QUIET_ZONE_MODULES
        assertEquals(QrLabel.SYMBOL_INCHES, symbolModules * QrLabel.MODULE_PIXELS / dpi, 1e-9)
    }

    @Test
    fun matrixDecodesBackToThePayload() {
        assertEquals("AA:BB:CC:DD:EE:FF-Mary-Jane", decode("AA:BB:CC:DD:EE:FF-Mary-Jane"))
        assertEquals("AA:BB:CC:DD:EE:FF-Zoë", decode("AA:BB:CC:DD:EE:FF-Zoë"))
    }

    @Test
    fun nonLatin1Names_areEncodedAsUtf8() {
        assertEquals("AA:BB:CC:DD:EE:FF-東京🔥", decode("AA:BB:CC:DD:EE:FF-東京🔥"))
    }

    private fun decode(payload: String): String {
        val matrix = QrLabel.matrix(payload)
        val scale = 4
        val size = matrix.width * scale
        val pixels = IntArray(size * size) { i ->
            if (matrix[(i % size) / scale, (i / size) / scale]) 0xFF000000.toInt() else -1
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        return QRCodeReader().decode(bitmap).text
    }
}
