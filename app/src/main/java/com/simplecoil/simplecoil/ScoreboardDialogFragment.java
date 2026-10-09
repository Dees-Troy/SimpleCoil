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

package com.simplecoil.simplecoil;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

public class ScoreboardDialogFragment extends DialogFragment {

    private PlayerDisplayData[] playerDisplayData;
    private PlayerDisplayDataListAdapter adapter;
    private OnPlayerItemClickListener listener;

    public interface OnPlayerItemClickListener {
        void onTeamHeaderClicked();
        void onPlayerClicked(byte playerID);
    }

    public void setData(PlayerDisplayData[] data) {
        this.playerDisplayData = data;
        if (adapter != null && data != null) {
            adapter.setData(data);
        }
    }

    public void setListener(OnPlayerItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.Theme_AppCompat_DayNight_Dialog_Alert);
        builder.setNegativeButton(R.string.ok, (dialog, id) -> dialog.cancel());

        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.player_data_dialog, null);
        builder.setView(view);

        ListView listView = view.findViewById(R.id.player_list);
        adapter = new PlayerDisplayDataListAdapter(requireActivity(), playerDisplayData, true);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, v, position, id) -> {
            if (listener != null) {
                PlayerDisplayDataListAdapter.DisplayRow row = adapter.getDisplayRow(position);
                if (row != null && row.type == PlayerDisplayDataListAdapter.DisplayRow.TYPE_TEAM_HEADER && row.teamNumber > 0) {
                    listener.onTeamHeaderClicked();
                    return;
                }
                byte playerID = adapter.getPlayerID(position);
                if (playerID > 0) {
                    listener.onPlayerClicked(playerID);
                }
            }
        });

        return builder.create();
    }
}
