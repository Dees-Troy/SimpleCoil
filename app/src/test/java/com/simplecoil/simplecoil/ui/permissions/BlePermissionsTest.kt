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

package com.simplecoil.simplecoil.ui.permissions

import com.simplecoil.simplecoil.ui.permissions.BlePermissions.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** The permission sets per API level (unchanged from the activity) and how a result is read. */
class BlePermissionsTest {

    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val fineLocation = "android.permission.ACCESS_FINE_LOCATION"
    private val notifications = "android.permission.POST_NOTIFICATIONS"
    private val camera = "android.permission.CAMERA"

    @Test
    fun connect_perApiLevel() {
        assertEquals(emptyList<String>(), BlePermissions.forConnect(21))
        assertEquals(emptyList<String>(), BlePermissions.forConnect(22))
        assertEquals(listOf(fineLocation), BlePermissions.forConnect(23))
        assertEquals(listOf(fineLocation), BlePermissions.forConnect(30))
        assertEquals(listOf(scan, connect, fineLocation), BlePermissions.forConnect(31))
        assertEquals(listOf(scan, connect, fineLocation), BlePermissions.forConnect(32))
        assertEquals(listOf(scan, connect, fineLocation, notifications), BlePermissions.forConnect(33))
        assertEquals(listOf(scan, connect, fineLocation, notifications), BlePermissions.forConnect(34))
    }

    @Test
    fun qrScan_cameraFromApi23() {
        assertEquals(emptyList<String>(), BlePermissions.forQrScan(22))
        assertEquals(listOf(camera), BlePermissions.forQrScan(23))
        assertEquals(listOf(camera), BlePermissions.forQrScan(34))
    }

    @Test
    fun missing_keepsRequestOrder() {
        val held = setOf(connect, notifications)
        assertEquals(listOf(scan, fineLocation), BlePermissions.missing(BlePermissions.forConnect(34)) { it in held })
    }

    @Test
    fun outcome_allGranted() {
        assertEquals(Outcome.GRANTED, BlePermissions.outcome(mapOf(scan to true, connect to true)) { false })
    }

    @Test
    fun outcome_emptyResultIsDenied() {
        assertEquals(Outcome.DENIED, BlePermissions.outcome(emptyMap()) { false })
    }

    @Test
    fun outcome_deniedButAskableAgain() {
        assertEquals(Outcome.DENIED, BlePermissions.outcome(mapOf(scan to false, connect to true)) { it == scan })
    }

    @Test
    fun outcome_deniedWithoutRationaleIsPermanent() {
        assertEquals(Outcome.PERMANENTLY_DENIED, BlePermissions.outcome(mapOf(scan to false, connect to false)) { it == scan })
        // A granted permission's rationale flag doesn't matter.
        assertEquals(Outcome.DENIED, BlePermissions.outcome(mapOf(scan to false, connect to true)) { it == scan })
    }
}
