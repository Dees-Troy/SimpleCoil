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

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding

/**
 * The About sheet on the connect screen. Collapsed, it fills the space left below the buttons
 * (at least [R.dimen.info_sheet_min_peek]); dragging it or tapping its header expands it to the
 * whole screen, and Back or another tap collapses it again.
 */
class InfoSheet(activity: AppCompatActivity, private val b: ActivityFullscreenBinding) {

    private val behavior = BottomSheetBehavior.from(b.infoSheet)
    private val minPeek = activity.resources.getDimensionPixelSize(R.dimen.info_sheet_min_peek)

    private val collapseOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    init {
        b.infoWv.loadUrl("file:///android_asset/simplecoil.html")
        b.infoWv.visibility = View.VISIBLE
        behavior.isHideable = false
        b.infoSheetHeader.setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                collapseOnBack.isEnabled = newState == BottomSheetBehavior.STATE_EXPANDED
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) b.infoScroll.scrollTo(0, 0)
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
        // Registered after the activity's own Back handling, so it goes first while expanded.
        activity.onBackPressedDispatcher.addCallback(activity, collapseOnBack)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPeek() }
        b.connectCoordinator.addOnLayoutChangeListener(relayout)
        b.connectContent.addOnLayoutChangeListener(relayout)
    }

    /** Peek into whatever the buttons leave free, and pad the buttons so none sit under the sheet. */
    private fun fitPeek() {
        val free = b.connectCoordinator.height - b.connectContent.height
        val peek = maxOf(minPeek, free)
        if (peek != behavior.peekHeight) behavior.peekHeight = peek
        if (b.connectScroll.paddingBottom != peek) {
            b.connectScroll.post { b.connectScroll.setPadding(0, 0, 0, peek) }
        }
    }
}
