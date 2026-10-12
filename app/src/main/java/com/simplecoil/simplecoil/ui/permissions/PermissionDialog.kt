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

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.simplecoil.simplecoil.R

/**
 * Explains a permission request before it is made (rationale; Continue reports back through the
 * fragment result [REQUEST_KEY]), or, after a permanent denial, offers the app settings.
 */
class PermissionDialog : DialogFragment() {

    enum class Flow { CONNECT, QR_SCAN }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val flow = Flow.valueOf(requireArguments().getString(ARG_FLOW)!!)
        val settings = requireArguments().getBoolean(ARG_SETTINGS)
        val builder = AlertDialog.Builder(requireContext(), R.style.Theme_AppCompat_DayNight_Dialog_Alert)
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
        return if (settings) {
            builder.setTitle(R.string.permission_settings_title)
                .setMessage(if (flow == Flow.CONNECT) R.string.permission_settings_ble else R.string.permission_settings_camera)
                .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                    val context = requireContext()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }
                .create()
        } else {
            builder.setTitle(R.string.permission_rationale_title)
                .setMessage(if (flow == Flow.CONNECT) R.string.permission_rationale_ble else R.string.permission_rationale_camera)
                .setPositiveButton(R.string.permission_continue) { _, _ ->
                    parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(ARG_FLOW to flow.name))
                }
                .create()
        }
    }

    companion object {
        const val REQUEST_KEY = "permissionRationale"
        const val TAG = "permissionDialog"
        const val ARG_FLOW = "flow"
        private const val ARG_SETTINGS = "settings"

        fun rationale(flow: Flow) = PermissionDialog().apply { arguments = bundleOf(ARG_FLOW to flow.name, ARG_SETTINGS to false) }

        fun openSettings(flow: Flow) = PermissionDialog().apply { arguments = bundleOf(ARG_FLOW to flow.name, ARG_SETTINGS to true) }
    }
}
