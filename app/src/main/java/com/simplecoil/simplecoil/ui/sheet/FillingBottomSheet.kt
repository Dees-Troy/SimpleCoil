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

package com.simplecoil.simplecoil.ui.sheet

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior

/**
 * A bottom sheet under a screen's controls. Collapsed, it fills the space left below [content]
 * (at least [minPeek] pixels); dragging it or tapping [header] expands it to the whole
 * [coordinator], and Back or another tap collapses it again.
 *
 * [scroll] holds [content] and is padded by the peek height, so nothing hides behind the sheet;
 * it needs clipToPadding off. [onCollapsed] runs each time the sheet settles collapsed.
 */
class FillingBottomSheet(
    activity: AppCompatActivity,
    sheet: View,
    header: View,
    private val coordinator: View,
    private val content: View,
    private val scroll: View,
    private val minPeek: Int,
    onCollapsed: () -> Unit = {},
) {
    private val behavior = BottomSheetBehavior.from(sheet)

    private val collapseOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    init {
        behavior.isHideable = false
        header.setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                BottomSheetBehavior.STATE_COLLAPSED
            } else {
                BottomSheetBehavior.STATE_EXPANDED
            }
        }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                collapseOnBack.isEnabled = newState == BottomSheetBehavior.STATE_EXPANDED
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) onCollapsed()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
        // Registered after the activity's own Back handling, so it goes first while expanded.
        activity.onBackPressedDispatcher.addCallback(activity, collapseOnBack)
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPeek() }
        coordinator.addOnLayoutChangeListener(relayout)
        content.addOnLayoutChangeListener(relayout)
    }

    /** Peek into whatever the controls leave free, and pad the controls so none sit under the sheet. */
    private fun fitPeek() {
        val free = coordinator.height - content.height
        val peek = maxOf(minPeek, free)
        if (peek != behavior.peekHeight) behavior.peekHeight = peek
        if (scroll.paddingBottom != peek) {
            scroll.post { scroll.setPadding(0, 0, 0, peek) }
        }
    }
}
