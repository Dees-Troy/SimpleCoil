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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.GameStateRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.team.TeamColors;
import com.simplecoil.simplecoil.domain.team.TeamLayout;

import java.util.ArrayList;
import java.util.Collections;

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
    private PlayerDisplayData[] data = new PlayerDisplayData[GameSettingsRepository.MAX_PLAYER_ID];
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

    private static class TeamInfo {
        int teamNumber;
        int totalPoints;
        int totalLivesRemaining;
        int totalEliminations;
        boolean livesLimitSet;
        ArrayList<PlayerDisplayData> players = new ArrayList<>();
    }

    private boolean isLivesLimitSet(PlayerDisplayData p) {
        if (p == null) return false;
        if (p.overrideLives) {
            return p.lives != 0;
        }
        return (GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0;
    }

    private int getLivesRemaining(PlayerDisplayData p) {
        if (p == null) return 0;
        if (p.overrideLives) {
            if (p.lives != 0) {
                return p.lives - p.eliminated;
            }
        } else if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0) {
            return GameSettingsRepository.getInstance().getLivesLimit() - p.eliminated;
        }
        return 0;
    }

    private int comparePlayers(PlayerDisplayData p1, PlayerDisplayData p2) {
        if (p1 == p2) return 0;
        if (p1 == null) return 1;
        if (p2 == null) return -1;

        // 1. Score (highest first)
        if (p1.points != p2.points) {
            return Integer.compare(p2.points, p1.points);
        }

        // 2. Most lives remaining (if lives limit is set) OR least eliminations
        boolean livesLimitSet1 = isLivesLimitSet(p1);
        boolean livesLimitSet2 = isLivesLimitSet(p2);
        if (livesLimitSet1 || livesLimitSet2) {
            int livesRemaining1 = getLivesRemaining(p1);
            int livesRemaining2 = getLivesRemaining(p2);
            if (livesRemaining1 != livesRemaining2) {
                return Integer.compare(livesRemaining2, livesRemaining1);
            }
        } else {
            if (p1.eliminated != p2.eliminated) {
                return Integer.compare(p1.eliminated, p2.eliminated);
            }
        }

        // 3. Player name (case-insensitive)
        String name1 = p1.playerName != null ? p1.playerName : "";
        String name2 = p2.playerName != null ? p2.playerName : "";
        int nameComp = name1.compareToIgnoreCase(name2);
        if (nameComp != 0) {
            return nameComp;
        }

        // 4. Player ID
        return Integer.compare(p1.playerID & 0xFF, p2.playerID & 0xFF);
    }

    private void updateDisplayRows() {
        displayRows.clear();

        // 1. Column header row
        DisplayRow colHeader = new DisplayRow();
        colHeader.type = DisplayRow.TYPE_COLUMN_HEADER;
        displayRows.add(colHeader);

        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();

        if (layout.getMode().isFreeForAll()) {
            DisplayRow teamHeader = new DisplayRow();
            teamHeader.type = DisplayRow.TYPE_TEAM_HEADER;
            teamHeader.teamNumber = 0;
            displayRows.add(teamHeader);

            ArrayList<PlayerDisplayData> playerList = new ArrayList<>();
            for (int pId = 1; pId <= GameSettingsRepository.MAX_PLAYER_ID; pId++) {
                if (data != null && pId < data.length && data[pId] != null) {
                    playerList.add(data[pId]);
                }
            }

            Collections.sort(playerList, this::comparePlayers);

            for (PlayerDisplayData p : playerList) {
                DisplayRow pRow = new DisplayRow();
                pRow.type = DisplayRow.TYPE_PLAYER;
                pRow.playerID = p.playerID;
                pRow.teamNumber = 0;
                pRow.indexInTeam = p.playerID & 0xFF;
                displayRows.add(pRow);
            }
        } else {
            int numTeams = layout.getMode().teamCount;
            ArrayList<TeamInfo> teams = new ArrayList<>();

            for (int t = 1; t <= numTeams; t++) {
                TeamInfo teamInfo = new TeamInfo();
                teamInfo.teamNumber = t;

                int startID = layout.teamStartId(t);
                int endID = layout.teamEndId(t);
                if (data != null) {
                    for (int pId = startID; pId <= endID; pId++) {
                        if (pId < data.length && data[pId] != null) {
                            PlayerDisplayData p = data[pId];
                            teamInfo.players.add(p);
                            teamInfo.totalPoints += p.points;
                            teamInfo.totalEliminations += p.eliminated;
                            if (isLivesLimitSet(p)) {
                                teamInfo.livesLimitSet = true;
                                teamInfo.totalLivesRemaining += getLivesRemaining(p);
                            }
                        }
                    }
                }
                teams.add(teamInfo);
            }

            Collections.sort(teams, (t1, t2) -> {
                // 1. Team total score descending
                if (t1.totalPoints != t2.totalPoints) {
                    return Integer.compare(t2.totalPoints, t1.totalPoints);
                }

                // 2. Team lives remaining / eliminations
                boolean livesLimit = t1.livesLimitSet || t2.livesLimitSet ||
                        ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0);
                if (livesLimit) {
                    if (t1.totalLivesRemaining != t2.totalLivesRemaining) {
                        return Integer.compare(t2.totalLivesRemaining, t1.totalLivesRemaining);
                    }
                } else {
                    if (t1.totalEliminations != t2.totalEliminations) {
                        return Integer.compare(t1.totalEliminations, t2.totalEliminations);
                    }
                }

                // 3. Team number ascending
                return Integer.compare(t1.teamNumber, t2.teamNumber);
            });

            for (TeamInfo teamInfo : teams) {
                DisplayRow tHeader = new DisplayRow();
                tHeader.type = DisplayRow.TYPE_TEAM_HEADER;
                tHeader.teamNumber = teamInfo.teamNumber;
                displayRows.add(tHeader);

                Collections.sort(teamInfo.players, this::comparePlayers);

                int startID = GameSettingsRepository.getInstance().getTeamLayout().teamStartId(teamInfo.teamNumber);
                for (PlayerDisplayData p : teamInfo.players) {
                    DisplayRow pRow = new DisplayRow();
                    pRow.type = DisplayRow.TYPE_PLAYER;
                    pRow.playerID = p.playerID;
                    pRow.teamNumber = teamInfo.teamNumber;
                    pRow.indexInTeam = (p.playerID & 0xFF) - startID + 1;
                    displayRows.add(pRow);
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
                int teamTotalsIndex = GameSettingsRepository.MAX_PLAYER_ID + 1;
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
            View colorSq = headerView.findViewById(R.id.team_header_color_sq);

            boolean isAdmin = PlayerRepository.getInstance().isAdmin();
            boolean canEdit = (!isClient || isAdmin);

            TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
            if (layout.getMode().isFreeForAll()) {
                titleTV.setText("PLAYERS");
                actionTV.setVisibility(View.GONE);
                if (colorSq != null) {
                    colorSq.setVisibility(View.GONE);
                }
            } else {
                int[] sizes = layout.teamSizes();
                int capacity = 16;
                if (sizes != null && (row.teamNumber - 1) < sizes.length) {
                    capacity = sizes[row.teamNumber - 1];
                }
                int teamScore = 0;
                int playerCount = 0;
                int startID = GameSettingsRepository.getInstance().getTeamLayout().teamStartId(row.teamNumber);
                int endID = GameSettingsRepository.getInstance().getTeamLayout().teamEndId(row.teamNumber);
                if (data != null) {
                    for (int pId = startID; pId <= endID; pId++) {
                        if (pId < data.length && data[pId] != null) {
                            teamScore += data[pId].points;
                            playerCount++;
                        }
                    }
                }
                titleTV.setText(context.getString(R.string.player_list_team_header, row.teamNumber, playerCount, capacity, teamScore));
                if (colorSq != null) {
                    int teamColor = TeamColors.colorOf(row.teamNumber);
                    if (teamColor != Color.TRANSPARENT) {
                        colorSq.setBackgroundColor(teamColor);
                        colorSq.setVisibility(View.VISIBLE);
                    } else {
                        colorSq.setVisibility(View.GONE);
                    }
                }
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
                        if (GameStateRepository.getInstance().getGameState() != GameStateRepository.GAME_STATE_NONE) {
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
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText(R.string.game_limit_lives);
            else
                playerEliminatedTV.setText(R.string.player_list_eliminated_label);
            return rowView;
        } else if (row.type == DisplayRow.TYPE_TEAM_TOTALS) {
            int teamTotalsIndex = GameSettingsRepository.MAX_PLAYER_ID + 1;
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

        if (playerID == PlayerRepository.getInstance().getPlayerID()) {
            playerNameTV.setTextColor(Color.parseColor("#FF8C00"));
        } else {
            playerNameTV.setTextColor(Color.WHITE);
        }

        switch (GameSettingsRepository.getInstance().getTeamMode()) {
            case FREE_FOR_ALL:
                playerIDTV.setText("" + playerID);
                break;
            case TWO_TEAMS:
            case FOUR_TEAMS:
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
            if ((GameSettingsRepository.getInstance().getGameLimit() & GameSettingsRepository.GAME_LIMIT_LIVES) != 0)
                playerEliminatedTV.setText("" + (GameSettingsRepository.getInstance().getLivesLimit() - data[playerID].eliminated));
            else
                playerEliminatedTV.setText("" + data[playerID].eliminated);
        }
        // Clients get "connected" in the player data too, so their scoreboards show who is away.
        ImageView networkStatus = rowView.findViewById(R.id.network_status_iv);
        if (networkStatus != null) {
            networkStatus.setVisibility(View.VISIBLE);
            if (data[playerID].isConnected)
                networkStatus.setImageResource(R.drawable.ic_network_connected_24dp);
            else
                networkStatus.setImageResource(R.drawable.ic_network_disconnected_24dp);
        }

        ImageView kickPlayerIV = rowView.findViewById(R.id.kick_player_iv);
        boolean isAdmin = PlayerRepository.getInstance().isAdmin();
        boolean isServerAdmin = (!isClient || isAdmin);

        if (kickPlayerIV != null) {
            if (isServerAdmin && playerID >= 1 && playerID <= GameSettingsRepository.MAX_PLAYER_ID && data[playerID] != null && playerID != PlayerRepository.getInstance().getPlayerID()) {
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
