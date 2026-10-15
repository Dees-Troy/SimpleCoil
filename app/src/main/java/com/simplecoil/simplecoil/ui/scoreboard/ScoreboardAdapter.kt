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

package com.simplecoil.simplecoil.ui.scoreboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simplecoil.simplecoil.R
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler

/**
 * The scoreboard of the dedicated server and of the play screen's scoreboard dialog. Updates are
 * diffed, so the list keeps its scroll position (S10).
 */
class ScoreboardAdapter(
    private val isClient: Boolean,
    private val listener: Listener,
) : ListAdapter<ScoreboardRow, RecyclerView.ViewHolder>(Diff) {

    interface Listener {
        /** An editable team header was tapped. */
        fun onTeamHeaderClicked()

        /** A player row was tapped (the server opens its settings). */
        fun onPlayerClicked(playerId: Byte) {}

        fun onKickClicked(playerId: Byte)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ScoreboardRow.TeamHeader -> TYPE_TEAM_HEADER
        else -> TYPE_PLAYER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_TEAM_HEADER) {
            TeamHeaderHolder(inflater.inflate(R.layout.player_display_team_header, parent, false))
        } else {
            val layout = if (isClient) R.layout.player_display_data_client else R.layout.player_display_data
            PlayerHolder(inflater.inflate(layout, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ScoreboardRow.TeamHeader -> (holder as TeamHeaderHolder).bind(row)
            is ScoreboardRow.ColumnHeader -> (holder as PlayerHolder).bindColumnHeader(row)
            is ScoreboardRow.Player -> (holder as PlayerHolder).bind(row)
        }
    }

    private inner class TeamHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.team_header_title_tv)
        private val action: TextView = view.findViewById(R.id.team_header_action_tv)
        private val colorSquare: View? = view.findViewById(R.id.team_header_color_sq)

        fun bind(row: ScoreboardRow.TeamHeader) {
            val context = itemView.context
            if (row.team == 0) {
                title.setText(R.string.scoreboard_players_header)
                action.visibility = View.GONE
                colorSquare?.visibility = View.GONE
            } else {
                title.text = context.getString(R.string.player_list_team_header, row.team, row.playerCount, row.capacity, row.score)
                colorSquare?.let {
                    val color = TeamColors.colorOf(row.team)
                    if (color != Color.TRANSPARENT) {
                        it.setBackgroundColor(color)
                        it.visibility = View.VISIBLE
                    } else {
                        it.visibility = View.GONE
                    }
                }
                action.visibility = if (row.editable) View.VISIBLE else View.GONE
                action.setText(R.string.scoreboard_edit_ratio)
            }
            if (row.editable) itemView.setOnClickListener { listener.onTeamHeaderClicked() } else itemView.setOnClickListener(null)
            itemView.isClickable = row.editable
        }
    }

    private inner class PlayerHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.player_name_tv)
        private val points: TextView = view.findViewById(R.id.player_points_tv)
        private val stat: TextView = view.findViewById(R.id.player_eliminated_tv)
        private val blaster: ImageView = view.findViewById(R.id.blaster_type_iv)
        private val grenade: ImageView = view.findViewById(R.id.grenade_iv)
        private val networkStatus: ImageView? = view.findViewById(R.id.network_status_iv)
        private val kick: ImageView? = view.findViewById(R.id.kick_player_iv)
        private val defaultNameColors: ColorStateList = name.textColors

        fun bindColumnHeader(row: ScoreboardRow.ColumnHeader) {
            name.setText(R.string.player_list_name_label)
            name.setTextColor(defaultNameColors)
            points.setText(R.string.player_list_points_label)
            stat.setText(if (row.livesColumn) R.string.scoreboard_lives else R.string.player_list_eliminated_label)
            // Invisible, not gone: the icons keep their space so the columns line up
            blaster.visibility = View.INVISIBLE
            grenade.visibility = View.INVISIBLE
            networkStatus?.visibility = View.GONE
            kick?.visibility = View.GONE
            kick?.setOnClickListener(null)
            itemView.setOnClickListener(null)
            itemView.isClickable = false
        }

        fun bind(row: ScoreboardRow.Player) {
            val context = itemView.context
            name.setTextColor(if (row.isSelf) ScoreboardColors.SELF_NAME else Color.WHITE)
            name.text = row.name
            points.text = context.getString(R.string.number, row.points)
            stat.text = context.getString(R.string.number, row.livesOrEliminations)
            when (row.blasterType) {
                GattProtocolHandler.BLASTER_TYPE_RIFLE -> showIcon(blaster, R.drawable.ic_blaster_rifle, R.string.cd_blaster_rifle)
                GattProtocolHandler.BLASTER_TYPE_PISTOL -> showIcon(blaster, R.drawable.ic_blaster_pistol, R.string.cd_blaster_pistol)
                else -> blaster.visibility = View.INVISIBLE
            }
            if (row.hasGrenade) showIcon(grenade, R.drawable.ic_grenade, R.string.cd_grenade_paired) else grenade.visibility = View.INVISIBLE
            networkStatus?.let {
                it.visibility = View.VISIBLE
                it.setImageResource(if (row.connected) R.drawable.ic_network_connected_24dp else R.drawable.ic_network_disconnected_24dp)
                it.contentDescription = context.getString(if (row.connected) R.string.scoreboard_connected else R.string.scoreboard_disconnected)
            }
            kick?.let {
                if (row.kickable) {
                    it.visibility = View.VISIBLE
                    it.setOnClickListener { listener.onKickClicked(row.playerId) }
                } else {
                    it.visibility = View.GONE
                    it.setOnClickListener(null)
                }
            }
            if (isClient) {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            } else {
                itemView.setOnClickListener { listener.onPlayerClicked(row.playerId) }
            }
        }
    }

    private fun showIcon(view: ImageView, icon: Int, description: Int) {
        view.setImageResource(icon)
        view.contentDescription = view.context.getString(description)
        view.visibility = View.VISIBLE
    }

    private object Diff : DiffUtil.ItemCallback<ScoreboardRow>() {
        override fun areItemsTheSame(oldItem: ScoreboardRow, newItem: ScoreboardRow) = when (oldItem) {
            is ScoreboardRow.ColumnHeader -> newItem is ScoreboardRow.ColumnHeader
            is ScoreboardRow.TeamHeader -> newItem is ScoreboardRow.TeamHeader && newItem.team == oldItem.team
            is ScoreboardRow.Player -> newItem is ScoreboardRow.Player && newItem.playerId == oldItem.playerId
        }

        override fun areContentsTheSame(oldItem: ScoreboardRow, newItem: ScoreboardRow) = oldItem == newItem
    }

    private companion object {
        const val TYPE_TEAM_HEADER = 0
        const val TYPE_PLAYER = 1
    }
}
