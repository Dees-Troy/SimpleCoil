/*
 * Copyright (C) 2018 Ethan Yonker
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

package com.simplecoil.simplecoil;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

public class PlayerDisplayDataListAdapter extends ArrayAdapter<PlayerDisplayData> {
    private final Activity context;
    private PlayerDisplayData[] data = new PlayerDisplayData[Globals.MAX_PLAYER_ID];
    private boolean isClient;

    public PlayerDisplayDataListAdapter(Activity context,
                                        PlayerDisplayData[] data, boolean isClient) {
        super(context, R.layout.player_display_data, data);
        this.context = context;
        this.data = data;
        this.isClient = isClient;
    }

    public void setData(PlayerDisplayData[] data) {
        this.data = data;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        LayoutInflater inflater = context.getLayoutInflater();
        View rowView;
        if (isClient)
            rowView = inflater.inflate(R.layout.player_display_data_client, null, true);
        else
            rowView = inflater.inflate(R.layout.player_display_data, null, true);
        TextView playerIDTV = rowView.findViewById(R.id.player_id_tv);
        TextView playerNameTV = rowView.findViewById(R.id.player_name_tv);
        TextView playerPointsTV = rowView.findViewById(R.id.player_points_tv);
        TextView playerEliminatedTV = rowView.findViewById(R.id.player_eliminated_tv);
        if (position == 0) {
            playerIDTV.setText(R.string.player_list_id_label);
            playerNameTV.setText(R.string.player_list_name_label);
            playerPointsTV.setText(R.string.player_list_points_label);
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText(R.string.game_limit_lives);
            else
                playerEliminatedTV.setText(R.string.player_list_eliminated_label);
            return rowView;
        }
        playerIDTV.setText("" + position);
        if (data[position] == null) {
            playerNameTV.setText(R.string.player_name_not_connected);
            playerPointsTV.setText("");
            playerEliminatedTV.setText("");
            if (!isClient) {
                ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
                networkStatus.setVisibility(View.GONE);
            }
            return rowView;
        }
        playerNameTV.setText(data[position].playerName);
        playerPointsTV.setText("" + data[position].points);
        if (data[position].overrideLives) {
            if (data[position].lives != 0)
                playerEliminatedTV.setText("" + (data[position].lives - data[position].eliminated));
            else
                playerEliminatedTV.setText("" + data[position].eliminated);
        } else {
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText("" + (Globals.getInstance().mLivesLimit - data[position].eliminated));
            else
                playerEliminatedTV.setText("" + data[position].eliminated);
        }
        if (!isClient) {
            ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
            networkStatus.setVisibility(View.VISIBLE);
            if (data[position].isConnected)
                networkStatus.setImageResource(R.drawable.ic_network_connected_24dp);
            else
                networkStatus.setImageResource(R.drawable.ic_network_disconnected_24dp);
        }
        return rowView;
    }
}