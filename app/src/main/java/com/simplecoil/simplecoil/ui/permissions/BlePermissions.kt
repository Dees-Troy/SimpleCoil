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

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/** Which runtime permissions a flow needs, and what a request's result means. Pure; no Android calls. */
object BlePermissions {

    /** What a permission request came back with. */
    enum class Outcome {
        GRANTED,

        /** Denied, but the system will still show the request again. */
        DENIED,

        /** Denied with no further prompts ("don't ask again"): only the app settings can grant it. */
        PERMANENTLY_DENIED,
    }

    /**
     * The permissions "Connect blaster" asks for on [sdkInt], in request order. Unchanged from the
     * activity: all of them must be granted before scanning.
     */
    fun forConnect(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Needed for BLE scanning before Android 12, and by the GPS map on all versions.
        if (sdkInt >= Build.VERSION_CODES.M) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Android 17, where local network access became a runtime permission. */
    const val SDK_LOCAL_NETWORK = 37

    /**
     * The permissions hosting the dedicated server or joining a network game asks for on [sdkInt],
     * in request order: local network access (Android 17+; without it every LAN packet fails with
     * EPERM) and notifications (the server's and the client's network notification).
     */
    @SuppressLint("InlinedApi") // only listed from Android 17 on
    fun forNetwork(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= SDK_LOCAL_NETWORK) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** The permissions in [forNetwork] a network game can't run without; notifications are optional. */
    @SuppressLint("InlinedApi") // only listed from Android 17 on
    fun requiredForNetwork(sdkInt: Int): List<String> =
        if (sdkInt >= SDK_LOCAL_NETWORK) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()

    /** The QR button's permissions on [sdkInt] (install-time before Android 6). */
    fun forQrScan(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.M) listOf(Manifest.permission.CAMERA) else emptyList()

    /** [permissions] the app doesn't hold yet. */
    fun missing(permissions: List<String>, isGranted: (String) -> Boolean): List<String> =
        permissions.filterNot(isGranted)

    /**
     * The outcome of a request. An empty result (the request was interrupted) counts as denied. A
     * denied permission whose rationale the system no longer offers is permanently denied.
     */
    fun outcome(results: Map<String, Boolean>, shouldShowRationale: (String) -> Boolean): Outcome {
        if (results.isEmpty()) return Outcome.DENIED
        val denied = results.filterValues { !it }.keys
        return when {
            denied.isEmpty() -> Outcome.GRANTED
            denied.any { !shouldShowRationale(it) } -> Outcome.PERMANENTLY_DENIED
            else -> Outcome.DENIED
        }
    }

    /** [outcome], where only the [required] permissions count: an optional one may be denied. */
    fun outcome(results: Map<String, Boolean>, required: Collection<String>, shouldShowRationale: (String) -> Boolean): Outcome {
        if (results.isEmpty()) return Outcome.DENIED
        val requiredResults = results.filterKeys { it in required }
        if (requiredResults.isEmpty()) return Outcome.GRANTED
        return outcome(requiredResults, shouldShowRationale)
    }
}
