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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * A printable pairing label: the QR code, with the player name in regular text below it. The PNG
 * carries a print density that makes the code itself [SYMBOL_INCHES] wide (about 25 mm), small
 * enough to tape to the side of a blaster.
 */
class QrLabel private constructor(val bitmap: Bitmap, private val dpi: Double) {

    /** The label as a PNG that prints at its intended size. */
    fun toPng(): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return PngDensity.withDpi(out.toByteArray(), dpi)
    }

    companion object {
        /** Printed width of the code, quiet zone excluded. */
        const val SYMBOL_INCHES = 1.0
        /** Quiet zone in modules: below the spec's 4 to keep the label small; phone scanners read 2 fine. */
        const val QUIET_ZONE_MODULES = 2
        const val MODULE_PIXELS = 10
        private const val TEXT_POINTS = 9.0
        private const val POINTS_PER_INCH = 72.0

        /**
         * The modules of [payload], quiet zone included, one bit per module. ISO-8859-1 (ZXing's
         * default) when the payload fits it; otherwise UTF-8, flagged with an ECI so scanners
         * decode it as such.
         */
        fun matrix(payload: String): BitMatrix = QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, 0, 0,
            buildMap<EncodeHintType, Any> {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES)
                if (!Charsets.ISO_8859_1.newEncoder().canEncode(payload)) put(EncodeHintType.CHARACTER_SET, Charsets.UTF_8.name())
            },
        )

        /** The print density that makes a code of [matrixWidth] modules (quiet zone included) [SYMBOL_INCHES] wide. */
        fun dpi(matrixWidth: Int): Double = (matrixWidth - 2 * QUIET_ZONE_MODULES) * MODULE_PIXELS / SYMBOL_INCHES

        fun create(payload: String, playerName: String): QrLabel {
            val matrix = matrix(payload)
            val dpi = dpi(matrix.width)
            val qrPixels = matrix.width * MODULE_PIXELS
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                textSize = (TEXT_POINTS / POINTS_PER_INCH * dpi).toFloat()
            }
            val name = playerName.trim()
            val margin = QUIET_ZONE_MODULES * MODULE_PIXELS
            // A long name widens the label rather than shrinking below regular text size.
            val width = if (name.isEmpty()) qrPixels else max(qrPixels, ceil(paint.measureText(name)).toInt() + 2 * margin)
            val metrics = paint.fontMetrics
            val textHeight = if (name.isEmpty()) 0 else ceil(metrics.descent - metrics.ascent).toInt() + margin
            val bitmap = createBitmap(width, qrPixels + textHeight)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val left = (width - qrPixels) / 2f
            val module = Paint().apply { color = Color.BLACK }
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    if (!matrix[x, y]) continue
                    val l = left + x * MODULE_PIXELS
                    val t = (y * MODULE_PIXELS).toFloat()
                    canvas.drawRect(l, t, l + MODULE_PIXELS, t + MODULE_PIXELS, module)
                }
            }
            if (name.isNotEmpty()) canvas.drawText(name, width / 2f, qrPixels - metrics.ascent, paint)
            return QrLabel(bitmap, dpi)
        }
    }
}
