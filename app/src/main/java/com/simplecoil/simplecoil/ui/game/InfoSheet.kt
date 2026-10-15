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
import androidx.appcompat.app.AppCompatActivity
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.databinding.ActivityFullscreenBinding
import com.simplecoil.simplecoil.ui.sheet.FillingBottomSheet

/**
 * The About sheet on the connect screen. Collapsed, it fills the space left below the buttons
 * (at least [R.dimen.info_sheet_min_peek]); dragging it or tapping its header expands it to the
 * whole screen, and Back or another tap collapses it again.
 */
class InfoSheet(activity: AppCompatActivity, private val b: ActivityFullscreenBinding) {

    init {
        b.infoWv.loadUrl("file:///android_asset/simplecoil.html")
        b.infoWv.visibility = View.VISIBLE
        FillingBottomSheet(activity, b.infoSheet, b.infoSheetHeader, b.connectCoordinator, b.connectContent, b.connectScroll,
            activity.resources.getDimensionPixelSize(R.dimen.info_sheet_min_peek)) { b.infoScroll.scrollTo(0, 0) }
    }
}
