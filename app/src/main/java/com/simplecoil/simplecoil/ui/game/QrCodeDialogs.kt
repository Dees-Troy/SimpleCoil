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

package com.simplecoil.simplecoil.ui.game

import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.QrCodeDialogBinding
import com.simplecoil.simplecoil.databinding.QrInfoDialogBinding
import com.simplecoil.simplecoil.databinding.QrNameDialogBinding
import com.simplecoil.simplecoil.ui.dialogs.AppDialogFragment
import com.simplecoil.simplecoil.ui.qr.QrLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * QR pairing labels (docs/QR_CODES.md): what they are for, the name to put in one, and the code
 * itself with a Save button.
 */

/** Explains pairing QR codes and asks for the (optional) player name to put in one. */
class QrNameDialog : GameDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = QrNameDialogBinding.inflate(appInflater)
        if (savedInstanceState == null) binding.qrPlayerNameEt.setText(requireArguments().getString(ARG_NAME))
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.qr_generate_button) { dialog, _ ->
                vm.onQrNameEntered(binding.qrPlayerNameEt.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        fun newInstance(current: String) = QrNameDialog().apply { arguments = bundleOf(ARG_NAME to current) }
    }
}

/** The pairing code as it will print, with Save; stays open after saving. */
class QrCodeDialog : AppDialogFragment() {

    private val label by lazy {
        val args = requireArguments()
        QrLabel.create(args.getString(ARG_PAYLOAD).orEmpty(), args.getString(ARG_NAME).orEmpty())
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(PNG)) { uri ->
        if (uri != null) save(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = QrCodeDialogBinding.inflate(appInflater)
        // Scaled up on screen: keep the modules sharp instead of blurring their edges.
        binding.qrCodeIv.setImageDrawable(label.bitmap.toDrawable(resources).apply { isFilterBitmap = false })
        val dialog = alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.qr_save_button, null)
            .setNegativeButton(R.string.qr_close_button) { d, _ -> d.dismiss() }
            .create()
        // Set here rather than in the builder so Save doesn't close the dialog.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { createDocument.launch(fileName()) }
        }
        return dialog
    }

    private fun fileName(): String {
        val name = requireArguments().getString(ARG_NAME).orEmpty().ifEmpty { "Blaster" }
        return getString(R.string.qr_file_name, name.replace(Regex("[^A-Za-z0-9_-]+"), "_"))
    }

    private fun save(uri: Uri) {
        val appContext = requireContext().applicationContext
        val png = label.toPng()
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    appContext.contentResolver.openOutputStream(uri)?.use { it.write(png) } != null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save QR code: " + e.message)
                    false
                }
            }
            Toast.makeText(appContext, if (saved) R.string.qr_saved_toast else R.string.qr_save_failed_toast, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "scmain"
        private const val PNG = "image/png"
        private const val ARG_PAYLOAD = "payload"

        fun newInstance(payload: String, playerName: String) =
            QrCodeDialog().apply { arguments = bundleOf(ARG_PAYLOAD to payload, ARG_NAME to playerName) }
    }
}

/** "Learn more about using QR codes" on the connect screen. */
class QrInfoDialog : AppDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        alertBuilder()
            .setView(QrInfoDialogBinding.inflate(appInflater).root)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .create()
}

private const val ARG_NAME = "name"
