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

package com.simplecoil.simplecoil.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.databinding.GameLimitDialogBinding
import com.simplecoil.simplecoil.databinding.StartingTimerDialogBinding
import com.simplecoil.simplecoil.databinding.TeamRatioDialogBinding
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.domain.rules.TeamSizes

/*
 * Dialogs both screens show (DEDICATED_SERVER_DESIGN.md §7.1). They know nothing about their
 * host: each one returns what was entered through the Fragment Result API, and the host routes
 * it to its own view model.
 */

/** A dialog in the app's dark dialog style. */
abstract class AppDialogFragment : DialogFragment() {
    /** The dialogs' layouts were inflated with the application context; keep their look. */
    protected val appInflater: LayoutInflater get() = LayoutInflater.from(requireContext().applicationContext)

    protected fun alertBuilder() = AlertDialog.Builder(requireContext(), R.style.AppDialogTheme)
}

/** A game limit: the text entered and which limit it is for. Results under [REQUEST_KEY]. */
class GameLimitDialog : AppDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = GameLimitDialogBinding.inflate(appInflater)
        val time = binding.gameLimitTimeRadio
        val lives = binding.gameLimitLivesRadio
        val score = binding.gameLimitScoreRadio
        time.isChecked = true
        lives.isChecked = false
        score.isChecked = false
        score.visibility = if (requireArguments().getBoolean(ARG_ALLOW_SCORE)) View.VISIBLE else View.GONE
        time.setOnClickListener {
            time.isChecked = true
            lives.isChecked = false
            score.isChecked = false
        }
        lives.setOnClickListener {
            time.isChecked = false
            lives.isChecked = true
            score.isChecked = false
        }
        score.setOnClickListener {
            time.isChecked = false
            lives.isChecked = false
            score.isChecked = true
        }
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val kind = when {
                    time.isChecked -> LimitKind.TIME
                    lives.isChecked -> LimitKind.LIVES
                    else -> LimitKind.SCORE
                }
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(RESULT_TEXT to binding.gameLimitEt.text.toString(), RESULT_KIND to kind.name),
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        const val REQUEST_KEY = "gameLimit"
        private const val ARG_ALLOW_SCORE = "allowScoreLimit"
        private const val RESULT_TEXT = "text"
        private const val RESULT_KIND = "kind"

        @JvmStatic
        fun newInstance(allowScoreLimit: Boolean) = GameLimitDialog().apply { arguments = bundleOf(ARG_ALLOW_SCORE to allowScoreLimit) }

        @JvmStatic
        fun text(result: Bundle): String = result.getString(RESULT_TEXT, "")

        @JvmStatic
        fun kind(result: Bundle): LimitKind = LimitKind.valueOf(result.getString(RESULT_KIND, LimitKind.TIME.name))
    }
}

/**
 * The team sizes. Stays open, with a toast, until they are valid (each team at least 1, every
 * player slot used); then returns them under [REQUEST_KEY].
 */
class TeamRatioDialog : AppDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val numTeams = requireArguments().getInt(ARG_NUM_TEAMS)
        val sizes = requireArguments().getIntArray(ARG_SIZES)!!
        val binding = TeamRatioDialogBinding.inflate(LayoutInflater.from(requireContext()))
        val editTexts = listOf(binding.team1RatioEt, binding.team2RatioEt, binding.team3RatioEt, binding.team4RatioEt)
        editTexts[0].setText(sizes[0].toString())
        editTexts[1].setText(sizes[1].toString())
        if (numTeams == 4) {
            binding.team3Layout.visibility = View.VISIBLE
            binding.team4Layout.visibility = View.VISIBLE
            editTexts[2].setText(sizes[2].toString())
            editTexts[3].setText(sizes[3].toString())
        } else {
            binding.team3Layout.visibility = View.GONE
            binding.team4Layout.visibility = View.GONE
        }
        val dialog = alertBuilder()
            .setView(binding.root)
            .setTitle(R.string.team_ratio_dialog_title)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { d, _ -> d.cancel() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = TeamSizes.validate(editTexts.map { it.text.toString() }, numTeams)) {
                    TeamSizes.Result.BelowOne -> toast(getString(R.string.team_ratio_min_one), Toast.LENGTH_SHORT)
                    is TeamSizes.Result.WrongTotal ->
                        toast(getString(R.string.team_ratio_total, GameSettingsRepository.MAX_PLAYER_ID.toInt(), result.total), Toast.LENGTH_LONG)
                    is TeamSizes.Result.Valid -> {
                        parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_SIZES to result.sizes))
                        dialog.dismiss()
                    }
                }
            }
        }
        return dialog
    }

    private fun toast(text: String, duration: Int) = Toast.makeText(requireActivity(), text, duration).show()

    companion object {
        const val REQUEST_KEY = "teamRatio"
        private const val ARG_NUM_TEAMS = "numTeams"
        private const val ARG_SIZES = "sizes"
        private const val RESULT_SIZES = "sizes"

        @JvmStatic
        fun newInstance(numTeams: Int, sizes: IntArray) =
            TeamRatioDialog().apply { arguments = bundleOf(ARG_NUM_TEAMS to numTeams, ARG_SIZES to sizes) }

        @JvmStatic
        fun sizes(result: Bundle): IntArray = result.getIntArray(RESULT_SIZES) ?: IntArray(0)
    }
}

/** The starting timer in seconds, as entered. Results under [REQUEST_KEY]. */
class StartingTimerDialog : AppDialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        val binding = StartingTimerDialogBinding.inflate(appInflater)
        binding.startingTimerEt.setText(requireArguments().getLong(ARG_CURRENT).toString())
        return alertBuilder()
            .setView(binding.root)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_TEXT to binding.startingTimerEt.text.toString()))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }

    companion object {
        const val REQUEST_KEY = "startingTimer"
        private const val ARG_CURRENT = "current"
        private const val RESULT_TEXT = "text"

        @JvmStatic
        fun newInstance(currentSeconds: Long) = StartingTimerDialog().apply { arguments = bundleOf(ARG_CURRENT to currentSeconds) }

        @JvmStatic
        fun text(result: Bundle): String = result.getString(RESULT_TEXT, "")
    }
}
