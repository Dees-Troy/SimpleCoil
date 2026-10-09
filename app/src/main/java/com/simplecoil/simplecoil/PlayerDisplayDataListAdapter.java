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
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class PlayerDisplayDataListAdapter extends ArrayAdapter<PlayerDisplayData> {

    public static class DisplayRow {
        public static final int TYPE_COLUMN_HEADER = 0;
        public static final int TYPE_TEAM_TOTALS = 1;
        public static final int TYPE_TEAM_HEADER = 2;
        public static final int TYPE_PLAYER = 3;

        public int type;
        public int teamNumber;  // 1-based team index, or 0 for FFA
        public byte playerID;
        public int indexInTeam; // 1-based index within team
    }

    private final Activity context;
    private PlayerDisplayData[] data = new PlayerDisplayData[Globals.MAX_PLAYER_ID];
    private boolean isClient;
    private ArrayList<DisplayRow> displayRows = new ArrayList<>();

    public PlayerDisplayDataListAdapter(Activity context,
                                        PlayerDisplayData[] data, boolean isClient) {
        super(context, R.layout.player_display_data, data != null ? data : new PlayerDisplayData[0]);
        this.context = context;
        this.data = data;
        this.isClient = isClient;
        updateDisplayRows();
    }

    public void setData(PlayerDisplayData[] data) {
        this.data = data;
        updateDisplayRows();
        notifyDataSetChanged();
    }

    private void updateDisplayRows() {
        displayRows.clear();

        // 1. Column header row
        DisplayRow colHeader = new DisplayRow();
        colHeader.type = DisplayRow.TYPE_COLUMN_HEADER;
        displayRows.add(colHeader);

        int gameMode = Globals.getInstance().mGameMode;

        if (gameMode == Globals.GAME_MODE_FFA) {
            DisplayRow teamHeader = new DisplayRow();
            teamHeader.type = DisplayRow.TYPE_TEAM_HEADER;
            teamHeader.teamNumber = 0;
            displayRows.add(teamHeader);

            for (int pId = 1; pId <= Globals.MAX_PLAYER_ID; pId++) {
                if (data != null && pId < data.length && data[pId] != null) {
                    DisplayRow pRow = new DisplayRow();
                    pRow.type = DisplayRow.TYPE_PLAYER;
                    pRow.playerID = (byte) pId;
                    pRow.teamNumber = 0;
                    pRow.indexInTeam = pId;
                    displayRows.add(pRow);
                }
            }
        } else {
            int numTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;
            for (int t = 1; t <= numTeams; t++) {
                DisplayRow tHeader = new DisplayRow();
                tHeader.type = DisplayRow.TYPE_TEAM_HEADER;
                tHeader.teamNumber = t;
                displayRows.add(tHeader);

                int startID = Globals.getInstance().getTeamStartID(t);
                int endID = Globals.getInstance().getTeamEndID(t);
                int indexInTeam = 1;
                for (int pId = startID; pId <= endID; pId++) {
                    if (data != null && pId < data.length && data[pId] != null) {
                        DisplayRow pRow = new DisplayRow();
                        pRow.type = DisplayRow.TYPE_PLAYER;
                        pRow.playerID = (byte) pId;
                        pRow.teamNumber = t;
                        pRow.indexInTeam = indexInTeam;
                        displayRows.add(pRow);
                    }
                    indexInTeam++;
                }
            }
        }
    }

    @Override
    public int getCount() {
        return displayRows.size();
    }

    @Override
    public PlayerDisplayData getItem(int position) {
        if (position >= 0 && position < displayRows.size()) {
            DisplayRow row = displayRows.get(position);
            if (row.type == DisplayRow.TYPE_PLAYER && data != null && row.playerID < data.length) {
                return data[row.playerID];
            } else if (row.type == DisplayRow.TYPE_TEAM_TOTALS) {
                int teamTotalsIndex = Globals.MAX_PLAYER_ID + 1;
                if (data != null && data.length > teamTotalsIndex) {
                    return data[teamTotalsIndex];
                }
            }
        }
        return null;
    }

    public DisplayRow getDisplayRow(int position) {
        if (position >= 0 && position < displayRows.size()) {
            return displayRows.get(position);
        }
        return null;
    }

    public byte getPlayerID(int position) {
        DisplayRow row = getDisplayRow(position);
        if (row != null && row.type == DisplayRow.TYPE_PLAYER) {
            return row.playerID;
        }
        return -1;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        DisplayRow row = getDisplayRow(position);
        if (row == null) {
            return new View(context);
        }

        LayoutInflater inflater = context.getLayoutInflater();

        if (row.type == DisplayRow.TYPE_TEAM_HEADER) {
            View headerView = inflater.inflate(R.layout.player_display_team_header, null, true);
            TextView titleTV = headerView.findViewById(R.id.team_header_title_tv);
            TextView actionTV = headerView.findViewById(R.id.team_header_action_tv);

            boolean isAdmin = Globals.getInstance().mIsAdmin;
            boolean canEdit = (!isClient || isAdmin);

            int gameMode = Globals.getInstance().mGameMode;
            if (gameMode == Globals.GAME_MODE_FFA) {
                titleTV.setText("PLAYERS");
                actionTV.setVisibility(View.GONE);
            } else {
                int[] sizes = Globals.getInstance().getTeamSizes();
                int capacity = 16;
                if (sizes != null && (row.teamNumber - 1) < sizes.length) {
                    capacity = sizes[row.teamNumber - 1];
                }
                int teamScore = 0;
                int startID = Globals.getInstance().getTeamStartID(row.teamNumber);
                int endID = Globals.getInstance().getTeamEndID(row.teamNumber);
                if (data != null) {
                    for (int pId = startID; pId <= endID; pId++) {
                        if (pId < data.length && data[pId] != null) {
                            teamScore += data[pId].points;
                        }
                    }
                }
                titleTV.setText(context.getString(R.string.player_list_team_header, row.teamNumber, capacity, teamScore));
                if (canEdit) {
                    actionTV.setVisibility(View.VISIBLE);
                    actionTV.setText("Tap to edit ratio");
                } else {
                    actionTV.setVisibility(View.GONE);
                }
            }

            if (canEdit) {
                headerView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (Globals.getInstance().mGameState != Globals.GAME_STATE_NONE) {
                            Toast.makeText(context, "Cannot edit team ratios while a game is in progress", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (context instanceof DedicatedServerActivity) {
                            ((DedicatedServerActivity) context).showTeamRatioDialog();
                        } else if (context instanceof FullscreenActivity) {
                            ((FullscreenActivity) context).showTeamRatioDialog();
                        }
                    }
                });
            } else {
                headerView.setOnClickListener(null);
            }

            return headerView;
        }

        View rowView;
        if (isClient)
            rowView = inflater.inflate(R.layout.player_display_data_client, null, true);
        else
            rowView = inflater.inflate(R.layout.player_display_data, null, true);

        TextView playerIDTV = rowView.findViewById(R.id.player_id_tv);
        TextView playerNameTV = rowView.findViewById(R.id.player_name_tv);
        TextView playerPointsTV = rowView.findViewById(R.id.player_points_tv);
        TextView playerEliminatedTV = rowView.findViewById(R.id.player_eliminated_tv);

        if (row.type == DisplayRow.TYPE_COLUMN_HEADER) {
            playerIDTV.setText(R.string.player_list_id_label);
            playerNameTV.setText(R.string.player_list_name_label);
            playerPointsTV.setText(R.string.player_list_points_label);
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText(R.string.game_limit_lives);
            else
                playerEliminatedTV.setText(R.string.player_list_eliminated_label);
            return rowView;
        } else if (row.type == DisplayRow.TYPE_TEAM_TOTALS) {
            int teamTotalsIndex = Globals.MAX_PLAYER_ID + 1;
            if (data != null && data.length > teamTotalsIndex && data[teamTotalsIndex] != null && data[teamTotalsIndex].playerName != null) {
                playerIDTV.setText(data[teamTotalsIndex].playerName);
            } else {
                playerIDTV.setText("");
            }
            playerNameTV.setText("");
            playerPointsTV.setText("");
            playerEliminatedTV.setText("");
            ImageView kickPlayerIV = rowView.findViewById(R.id.kick_player_iv);
            if (kickPlayerIV != null) {
                kickPlayerIV.setVisibility(View.GONE);
            }
            if (!isClient) {
                ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
                if (networkStatus != null) {
                    networkStatus.setVisibility(View.GONE);
                }
            }
            return rowView;
        }

        int playerID = row.playerID;
        if (playerID < 0 || data == null || playerID >= data.length || data[playerID] == null) {
            return rowView;
        }

        if (playerID == Globals.getInstance().mPlayerID) {
            playerNameTV.setTextColor(Color.parseColor("#FF8C00"));
        } else {
            playerNameTV.setTextColor(Color.WHITE);
        }

        switch (Globals.getInstance().mGameMode) {
            case Globals.GAME_MODE_FFA:
                playerIDTV.setText("" + playerID);
                break;
            case Globals.GAME_MODE_2TEAMS:
            case Globals.GAME_MODE_4TEAMS:
                playerIDTV.setText(row.teamNumber + "-" + row.indexInTeam);
                break;
        }

        playerNameTV.setText(data[playerID].playerName);
        playerPointsTV.setText("" + data[playerID].points);
        if (data[playerID].overrideLives) {
            if (data[playerID].lives != 0)
                playerEliminatedTV.setText("" + (data[playerID].lives - data[playerID].eliminated));
            else
                playerEliminatedTV.setText("" + data[playerID].eliminated);
        } else {
            if ((Globals.getInstance().mGameLimit & Globals.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText("" + (Globals.getInstance().mLivesLimit - data[playerID].eliminated));
            else
                playerEliminatedTV.setText("" + data[playerID].eliminated);
        }
        if (!isClient) {
            ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
            if (networkStatus != null) {
                networkStatus.setVisibility(View.VISIBLE);
                if (data[playerID].isConnected)
                    networkStatus.setImageResource(R.drawable.ic_network_connected_24dp);
                else
                    networkStatus.setImageResource(R.drawable.ic_network_disconnected_24dp);
            }
        }

        ImageView kickPlayerIV = rowView.findViewById(R.id.kick_player_iv);
        boolean isAdmin = Globals.getInstance().mIsAdmin;
        boolean isServerAdmin = (!isClient || isAdmin);

        if (kickPlayerIV != null) {
            if (isServerAdmin && playerID >= 1 && playerID <= Globals.MAX_PLAYER_ID && data[playerID] != null && playerID != Globals.getInstance().mPlayerID) {
                kickPlayerIV.setVisibility(View.VISIBLE);
                final byte targetID = (byte) playerID;
                kickPlayerIV.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (context instanceof FullscreenActivity) {
                            ((FullscreenActivity) context).kickPlayerFromAdmin(targetID);
                        } else if (context instanceof DedicatedServerActivity) {
                            ((DedicatedServerActivity) context).kickPlayerFromAdmin(targetID);
                        }
                    }
                });
            } else {
                kickPlayerIV.setVisibility(View.GONE);
            }
        }
        return rowView;
    }
}
