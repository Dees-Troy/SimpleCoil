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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.ui.game.GameViewModel
import com.simplecoil.simplecoil.ui.permissions.PermissionDialog.Flow

/**
 * The permission and Bluetooth checks in front of a connect, and the camera permission in front of
 * a QR scan, on the ActivityResult API. Moved from FullscreenActivity.connectWeapon() and
 * onRequestPermissionsResult() with the same order of checks; adds a rationale before a request
 * the system says needs one, and the app settings after a permanent denial.
 *
 * Construct it in the activity's onCreate (it registers its launchers).
 */
class BlePermissionController(
    private val activity: FragmentActivity,
    private val vm: GameViewModel,
    /** The camera may be used: start the QR scanner. */
    private val onQrScanAllowed: () -> Unit,
) {
    private val connectPermissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        when (outcome(results)) {
            BlePermissions.Outcome.GRANTED -> vm.onLocationPermissionsGranted()
            BlePermissions.Outcome.DENIED ->
                Toast.makeText(activity, activity.getString(R.string.error_location_permission_required), Toast.LENGTH_SHORT).show()
            BlePermissions.Outcome.PERMANENTLY_DENIED -> showDialog(PermissionDialog.openSettings(Flow.CONNECT))
        }
    }

    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        when (outcome(results)) {
            BlePermissions.Outcome.GRANTED -> onQrScanAllowed()
            BlePermissions.Outcome.DENIED ->
                Toast.makeText(activity, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
            BlePermissions.Outcome.PERMANENTLY_DENIED -> showDialog(PermissionDialog.openSettings(Flow.QR_SCAN))
        }
    }

    private val enableBluetooth = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { connect() }

    init {
        activity.supportFragmentManager.setFragmentResultListener(PermissionDialog.REQUEST_KEY, activity) { _, result ->
            when (Flow.valueOf(result.getString(PermissionDialog.ARG_FLOW)!!)) {
                Flow.CONNECT -> request(Flow.CONNECT, rationaleShown = true)
                Flow.QR_SCAN -> request(Flow.QR_SCAN, rationaleShown = true)
            }
        }
    }

    /** "Connect": permissions, Bluetooth on, then [GameViewModel.startScan] (scan + 30 s give-up). */
    fun connect() {
        if (vm.isScanning) return

        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(activity, R.string.ble_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }

        if (missing(Flow.CONNECT).isNotEmpty()) {
            request(Flow.CONNECT, rationaleShown = false)
            return
        }

        // Initializes a Bluetooth manager and adapter.
        val bluetoothManager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (bluetoothManager == null) {
            Log.e(TAG, "Failed to get Bluetooth service")
            Toast.makeText(activity, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }
        // Checks if Bluetooth is supported on the device.
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Toast.makeText(activity, R.string.error_bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            activity.finish()
            return
        }
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetooth is off")
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException enabling bluetooth: " + e.message)
            }
            return
        }

        if (!vm.startScan(adapter)) {
            Toast.makeText(activity, R.string.error_location_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    /** The QR button: camera permission, then [onQrScanAllowed]. */
    fun scanQr() {
        if (missing(Flow.QR_SCAN).isNotEmpty()) {
            request(Flow.QR_SCAN, rationaleShown = false)
            return
        }
        onQrScanAllowed()
    }

    private fun permissions(flow: Flow) = when (flow) {
        Flow.CONNECT -> BlePermissions.forConnect(Build.VERSION.SDK_INT)
        Flow.QR_SCAN -> BlePermissions.forQrScan(Build.VERSION.SDK_INT)
    }

    private fun missing(flow: Flow) = BlePermissions.missing(permissions(flow)) {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun request(flow: Flow, rationaleShown: Boolean) {
        val missing = missing(flow)
        if (missing.isEmpty()) {
            // Granted meanwhile (e.g. in the settings).
            if (flow == Flow.CONNECT) vm.onLocationPermissionsGranted() else onQrScanAllowed()
            return
        }
        if (!rationaleShown && missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }) {
            showDialog(PermissionDialog.rationale(flow))
            return
        }
        val launcher = if (flow == Flow.CONNECT) connectPermissions else cameraPermission
        launcher.launch(missing.toTypedArray())
    }

    private fun outcome(results: Map<String, Boolean>) =
        BlePermissions.outcome(results) { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    private fun showDialog(dialog: PermissionDialog) {
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(PermissionDialog.TAG) != null) return
        dialog.show(fm, PermissionDialog.TAG)
    }

    private companion object {
        const val TAG = "scmain"
    }
}
