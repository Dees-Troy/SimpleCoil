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
 * Which advertising device to connect to. Extracted verbatim from FullscreenActivity's
 * scan callback: with no saved address, the first device whose name starts with `SRG1`;
 * otherwise only the device with exactly the saved MAC address.
 */
object ScanMatcher {
    const val BLASTER_NAME_PREFIX = "SRG1"

    @JvmStatic
    fun matches(deviceName: String?, deviceAddress: String?, savedAddress: String): Boolean {
        if (deviceName == null || deviceName.isEmpty()) return false
        return (savedAddress.isEmpty() && deviceName.startsWith(BLASTER_NAME_PREFIX)) || savedAddress == deviceAddress
    }
}
