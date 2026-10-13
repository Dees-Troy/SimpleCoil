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
    private val localNetwork = "android.permission.ACCESS_LOCAL_NETWORK"

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
    fun network_perApiLevel() {
        assertEquals(emptyList<String>(), BlePermissions.forNetwork(32))
        assertEquals(listOf(notifications), BlePermissions.forNetwork(33))
        assertEquals(listOf(notifications), BlePermissions.forNetwork(36))
        assertEquals(listOf(localNetwork, notifications), BlePermissions.forNetwork(37))
    }

    @Test
    fun network_onlyLocalNetworkIsRequired() {
        assertEquals(emptyList<String>(), BlePermissions.requiredForNetwork(36))
        assertEquals(listOf(localNetwork), BlePermissions.requiredForNetwork(37))
    }

    @Test
    fun outcomeWithRequired_deniedOptionalStillGrants() {
        val required = listOf(localNetwork)
        assertEquals(Outcome.GRANTED, BlePermissions.outcome(mapOf(localNetwork to true, notifications to false), required) { false })
        // Local network already held: only notifications were asked for.
        assertEquals(Outcome.GRANTED, BlePermissions.outcome(mapOf(notifications to false), required) { false })
        // Before Android 17 nothing is required.
        assertEquals(Outcome.GRANTED, BlePermissions.outcome(mapOf(notifications to false), emptyList()) { false })
    }

    @Test
    fun outcomeWithRequired_deniedRequired() {
        val required = listOf(localNetwork)
        assertEquals(Outcome.DENIED, BlePermissions.outcome(mapOf(localNetwork to false, notifications to true), required) { true })
        assertEquals(Outcome.PERMANENTLY_DENIED, BlePermissions.outcome(mapOf(localNetwork to false), required) { false })
        assertEquals(Outcome.DENIED, BlePermissions.outcome(emptyMap(), required) { false })
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
