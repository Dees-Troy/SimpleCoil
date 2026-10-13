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

import android.content.pm.PackageManager

/**
 * Whether this device can play with a blaster. A Chromebook (or anything without Bluetooth LE)
 * runs server-only: the connect screen offers just the dedicated server.
 */
object BlasterSupport {
    private const val FEATURE_ARC = "org.chromium.arc"
    private const val FEATURE_ARC_DEVICE_MANAGEMENT = "org.chromium.arc.device_management"

    @JvmStatic
    fun isChromebook(pm: PackageManager): Boolean =
        pm.hasSystemFeature(FEATURE_ARC) || pm.hasSystemFeature(FEATURE_ARC_DEVICE_MANAGEMENT)

    @JvmStatic
    fun isServerOnly(pm: PackageManager): Boolean =
        isChromebook(pm) || !pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
}
