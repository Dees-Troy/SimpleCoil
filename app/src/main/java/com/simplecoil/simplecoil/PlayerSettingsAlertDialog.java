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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.simplecoil.simplecoil.data.model.PlayerSettings;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;
import com.simplecoil.simplecoil.domain.team.TeamLayout;

public class PlayerSettingsAlertDialog extends AlertDialog implements PopupMenu.OnMenuItemClickListener {
    private TextView mTitleTV = null;
    private EditText mHealthET = null;
    private EditText mReloadShotsET = null;
    private EditText mReloadTimeET = null;
    private Switch mReloadOnEmptySwitch = null;
    private Switch mManualRespawnSwitch = null;
    private EditText mSpawnTimeET = null;
    private EditText mRespawnShieldTimeET = null;
    private EditText mDamageET = null;
    private Switch mOverrideLivesSwitch = null;
    private EditText mLivesET = null;
    private Switch mShotModeSingle = null;
    private Switch mShotModeBurst3 = null;
    private Switch mShotModeAuto = null;
    private Button mFiringModeButton = null;
    private Button mResetButton = null;
    private Switch mApplyAllSwitch = null;
    private Switch mApplyTeamSwitch = null;
    private Switch mAdminSwitch = null;
    private Button mSwitchTeamButton = null;
    private int mSelectedTargetTeam = 0;
    private Button mRecoilSettingButton = null;
    private int mSelectedRecoilSetting = GameSettingsRepository.RECOIL_SETTING_DEFAULT;
    private Button mKickPlayerButton = null;

    private boolean isServer = false;
    private byte mPlayerID = 0;

    private TcpServer mTcpServer = null;
    private TcpClient mTcpClient = null;

    private Context mContext = null;

    public PlayerSettingsAlertDialog(Context context) {
        super(context, R.style.AppDialogTheme);
        mContext = context;
    }

    public void setServer(byte playerID, TcpServer tcpServer) {
        isServer = true;
        mPlayerID = playerID;
        mTcpServer = tcpServer;
    }

    public void getServerSettings() {
        PlayerSettings playerSettings = PlayerRepository.getInstance().getPlayerSettings().get(mPlayerID);
        if (playerSettings == null)
            playerSettings = new PlayerSettings();
        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
        if (layout.getMode().isFreeForAll()) {
            mTitleTV.setText(mContext.getString(R.string.player_settings_id_title, mPlayerID));
        } else {
            int team = layout.teamOf(mPlayerID);
            mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, team, mPlayerID - layout.teamStartId(team) + 1));
        }
        mHealthET.setText("" + playerSettings.health);
        mReloadShotsET.setText("" + playerSettings.shots);
        mReloadTimeET.setText("" + playerSettings.reloadTime);
        mReloadOnEmptySwitch.setChecked(playerSettings.reloadOnEmpty);
        mManualRespawnSwitch.setChecked(playerSettings.manualRespawn);
        mSpawnTimeET.setText("" + playerSettings.spawnTime);
        mRespawnShieldTimeET.setText("" + playerSettings.respawnShieldTime);
        mDamageET.setText("" + (playerSettings.damage * -1));
        mOverrideLivesSwitch.setChecked(playerSettings.overrideLives);
        mLivesET.setText("" + playerSettings.lives);
        mShotModeSingle.setChecked(playerSettings.allowShotModeSingle);
        mShotModeBurst3.setChecked(playerSettings.allowShotModeBurst3);
        mShotModeAuto.setChecked(playerSettings.allowShotModeAuto);
        mFiringModeButton.setVisibility(View.VISIBLE);
        switch (playerSettings.firingMode) {
            case GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                break;
            case GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                break;
            case GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                break;
        }
        mApplyAllSwitch.setChecked(false);
        if (mApplyTeamSwitch != null) {
            mApplyTeamSwitch.setChecked(false);
            if (layout.getMode().isFreeForAll()) {
                mApplyTeamSwitch.setVisibility(View.GONE);
            } else {
                mApplyTeamSwitch.setVisibility(View.VISIBLE);
            }
        }
        if (mAdminSwitch != null) {
            mAdminSwitch.setVisibility(View.VISIBLE);
            mAdminSwitch.setChecked(playerSettings.isAdmin);
        }
        if (mKickPlayerButton != null) {
            mKickPlayerButton.setVisibility(View.VISIBLE);
        }
        if (mRecoilSettingButton != null) {
            mRecoilSettingButton.setVisibility(View.VISIBLE);
            mSelectedRecoilSetting = playerSettings.recoilSetting;
            updateRecoilSettingButtonText(mSelectedRecoilSetting);
        }
        if (mSwitchTeamButton != null) {
            if (layout.getMode().isFreeForAll()) {
                mSwitchTeamButton.setVisibility(View.GONE);
            } else {
                mSwitchTeamButton.setVisibility(View.VISIBLE);
                mSwitchTeamButton.setText(R.string.switch_team_button);
                mSelectedTargetTeam = 0;
            }
        }
    }

    private void updateRecoilSettingButtonText(int mode) {
        if (mRecoilSettingButton == null) return;
        switch (mode) {
            case GameSettingsRepository.RECOIL_SETTING_ENABLED:
                mRecoilSettingButton.setText(R.string.player_settings_recoil_enabled);
                break;
            case GameSettingsRepository.RECOIL_SETTING_DISABLED:
                mRecoilSettingButton.setText(R.string.player_settings_recoil_disabled);
                break;
            default:
                mRecoilSettingButton.setText(R.string.player_settings_recoil_default);
                break;
        }
    }

    public void setLocal(TcpClient tcpClient) {
        isServer = false;
        mTcpClient = tcpClient;
    }

    private void getLocalSettings() {
        if (mAdminSwitch != null) {
            mAdminSwitch.setVisibility(View.GONE);
        }
        if (mKickPlayerButton != null) {
            mKickPlayerButton.setVisibility(View.GONE);
        }
        if (mRecoilSettingButton != null) {
            mRecoilSettingButton.setVisibility(View.GONE);
        }
        if (mSwitchTeamButton != null) {
            mSwitchTeamButton.setVisibility(View.GONE);
        }
        mHealthET.setText("" + GameSettingsRepository.getInstance().getFullHealth());
        mReloadShotsET.setText("" + GameSettingsRepository.getInstance().getFullReload());
        mReloadTimeET.setText("" + GameSettingsRepository.getInstance().getReloadTime());
        mReloadOnEmptySwitch.setChecked(GameSettingsRepository.getInstance().isReloadOnEmpty());
        mManualRespawnSwitch.setChecked(GameSettingsRepository.getInstance().isManualRespawn());
        mSpawnTimeET.setText("" + GameSettingsRepository.getInstance().getRespawnTime());
        mRespawnShieldTimeET.setText("" + GameSettingsRepository.getInstance().getRespawnShieldTime());
        mDamageET.setText("" + (GameSettingsRepository.getInstance().getDamage() * -1));
        mOverrideLivesSwitch.setChecked(GameSettingsRepository.getInstance().isOverrideLives());
        mLivesET.setText("" + GameSettingsRepository.getInstance().getOverrideLivesVal());
        mShotModeSingle.setChecked(GameSettingsRepository.getInstance().isAllowSingleShotMode());
        mShotModeBurst3.setChecked(GameSettingsRepository.getInstance().isAllowBurst3ShotMode());
        mShotModeAuto.setChecked(GameSettingsRepository.getInstance().isAllowAutoShotMode());
        mFiringModeButton.setVisibility(View.GONE);
        mApplyAllSwitch.setVisibility(View.GONE);
        if (mApplyTeamSwitch != null) {
            mApplyTeamSwitch.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LayoutInflater inflater = getLayoutInflater();
        View view = inflater.inflate(R.layout.player_settings_dialog, null);
        setView(view);
        mTitleTV = view.findViewById(R.id.title_tv);
        mHealthET = view.findViewById(R.id.health_et);
        mReloadShotsET = view.findViewById(R.id.reload_shots_et);
        mReloadTimeET = view.findViewById(R.id.reload_time_et);
        mReloadOnEmptySwitch = view.findViewById(R.id.reload_on_empty_switch);
        mManualRespawnSwitch = view.findViewById(R.id.manual_respawn_switch);
        mSpawnTimeET = view.findViewById(R.id.spawn_time_et);
        mRespawnShieldTimeET = view.findViewById(R.id.respawn_shield_time_et);
        mDamageET = view.findViewById(R.id.damage_et);
        mOverrideLivesSwitch = view.findViewById(R.id.override_lives_limit_switch);
        mLivesET = view.findViewById(R.id.lives_et);
        mShotModeSingle = view.findViewById(R.id.shot_mode_single);
        mShotModeBurst3 = view.findViewById(R.id.shot_mode_burst3);
        mShotModeAuto = view.findViewById(R.id.shot_mode_auto);
        mFiringModeButton = view.findViewById(R.id.firing_mode_button);
        mFiringModeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                PopupMenu popup = new PopupMenu(new ContextThemeWrapper(getContext(), R.style.AppTheme_PopupOverlay), view);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.firing_mode_menu, popup.getMenu());
                popup.setOnMenuItemClickListener(PlayerSettingsAlertDialog.this);
                popup.show();
                Toast.makeText(getContext(), getContext().getString(R.string.firing_mode_cone_toast), Toast.LENGTH_LONG).show();
            }
        });
        mResetButton = view.findViewById(R.id.reset_defaults_button);
        mResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mHealthET.setText("" + GameSettingsRepository.MAX_HEALTH);
                mReloadShotsET.setText("" + GameSettingsRepository.RELOAD_COUNT);
                mReloadTimeET.setText("" + GameSettingsRepository.RELOAD_TIME_MILLISECONDS);
                mReloadOnEmptySwitch.setChecked(false);
                mManualRespawnSwitch.setChecked(false);
                mSpawnTimeET.setText("" + GameSettingsRepository.RESPAWN_TIME_SECONDS);
                mRespawnShieldTimeET.setText("" + GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS);
                mDamageET.setText("" + (GameSettingsRepository.DAMAGE_PER_HIT * -1));
                mOverrideLivesSwitch.setChecked(false);
                mShotModeAuto.setChecked(true);
                mShotModeBurst3.setChecked(true);
                mShotModeSingle.setChecked(true);
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                mLivesET.setText("" + 0);
            }
        });
        mApplyAllSwitch = view.findViewById(R.id.apply_to_all_switch);
        mApplyTeamSwitch = view.findViewById(R.id.apply_to_team_switch);
        if (mApplyAllSwitch != null && mApplyTeamSwitch != null) {
            mApplyAllSwitch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mApplyAllSwitch.isChecked()) {
                        mApplyTeamSwitch.setChecked(false);
                    }
                }
            });
            mApplyTeamSwitch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mApplyTeamSwitch.isChecked()) {
                        mApplyAllSwitch.setChecked(false);
                    }
                }
            });
        }
        mAdminSwitch = view.findViewById(R.id.admin_switch);
        mRecoilSettingButton = view.findViewById(R.id.recoil_setting_button);
        if (mRecoilSettingButton != null) {
            mRecoilSettingButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(new ContextThemeWrapper(getContext(), R.style.AppTheme_PopupOverlay), v);
                    popup.getMenu().add(0, GameSettingsRepository.RECOIL_SETTING_DEFAULT, 10, R.string.player_settings_recoil_default);
                    popup.getMenu().add(0, GameSettingsRepository.RECOIL_SETTING_ENABLED, 20, R.string.player_settings_recoil_enabled);
                    popup.getMenu().add(0, GameSettingsRepository.RECOIL_SETTING_DISABLED, 30, R.string.player_settings_recoil_disabled);
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            mSelectedRecoilSetting = item.getItemId();
                            updateRecoilSettingButtonText(mSelectedRecoilSetting);
                            return true;
                        }
                    });
                    popup.show();
                }
            });
        }
        mSwitchTeamButton = view.findViewById(R.id.switch_team_button);
        if (mSwitchTeamButton != null) {
            mSwitchTeamButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    PopupMenu popup = new PopupMenu(new ContextThemeWrapper(getContext(), R.style.AppTheme_PopupOverlay), view);
                    TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
                    int currentTeam = layout.teamOf(mPlayerID);
                    int totalTeams = layout.getMode().teamCount;

                    for (int team = 1; team <= totalTeams; team++) {
                        if (team != currentTeam) {
                            popup.getMenu().add(0, team, team * 10, mContext.getString(R.string.team_number_label, team));
                        }
                    }

                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            mSelectedTargetTeam = item.getItemId();
                            mSwitchTeamButton.setText(mContext.getString(R.string.team_number_label, mSelectedTargetTeam));
                            return true;
                        }
                    });
                    popup.show();
                }
            });
        }
        mKickPlayerButton = view.findViewById(R.id.kick_player_button);
        if (mKickPlayerButton != null) {
            mKickPlayerButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isServer && mTcpServer != null) {
                        mTcpServer.kickPlayer(mPlayerID);
                        Toast.makeText(getContext(), "Player kicked", Toast.LENGTH_SHORT).show();
                        dismiss();
                    }
                }
            });
        }
        setButton(DialogInterface.BUTTON_POSITIVE, mContext.getString(R.string.ok),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!mShotModeSingle.isChecked() && !mShotModeBurst3.isChecked() && !mShotModeAuto.isChecked()) {
                            Toast.makeText(getContext(), getContext().getString(R.string.player_settings_shot_mode_error), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (!isServer) {
                            Integer value = Integer.parseInt(mHealthET.getText().toString());
                            if (value > 1000 || value <= 0) value = GameSettingsRepository.MAX_HEALTH;
                            GameSettingsRepository.getInstance().setFullHealth(value);
                            value = Integer.parseInt(mReloadShotsET.getText().toString());
                            if (value > 255 || value <= 0) value = (int) GameSettingsRepository.RELOAD_COUNT;
                            GameSettingsRepository.getInstance().setFullReload((byte) (int) value);
                            value = Integer.parseInt(mReloadTimeET.getText().toString());
                            if (value > 10000 || value < 0)
                                value = (int) GameSettingsRepository.RELOAD_TIME_MILLISECONDS;
                            GameSettingsRepository.getInstance().setReloadTime(value);
                            GameSettingsRepository.getInstance().setReloadOnEmpty(mReloadOnEmptySwitch.isChecked());
                            GameSettingsRepository.getInstance().setManualRespawn(mManualRespawnSwitch.isChecked());
                            value = Integer.parseInt(mSpawnTimeET.getText().toString());
                            if (value > 1000 || value <= 0)
                                value = (int) GameSettingsRepository.RESPAWN_TIME_SECONDS;
                            GameSettingsRepository.getInstance().setRespawnTime(value);
                            value = Integer.parseInt(mRespawnShieldTimeET.getText().toString());
                            if (value > 1000 || value < 0)
                                value = (int) GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS;
                            GameSettingsRepository.getInstance().setRespawnShieldTime(value);
                            GameSettingsRepository.getInstance().setRespawnShieldTime(value);
                            value = Integer.parseInt(mDamageET.getText().toString());
                            if (value > 1000 || value <= 0) value = GameSettingsRepository.DAMAGE_PER_HIT * -1;
                            value = value * -1;
                            GameSettingsRepository.getInstance().setDamage(value);
                            GameSettingsRepository.getInstance().setOverrideLives(mOverrideLivesSwitch.isChecked());
                            value = Integer.parseInt(mLivesET.getText().toString());
                            if (value > 1000 || value <= 0)
                                value = 0;
                            GameSettingsRepository.getInstance().setOverrideLivesVal(value);
                            GameSettingsRepository.getInstance().setAllowSingleShotMode(mShotModeSingle.isChecked());
                            GameSettingsRepository.getInstance().setAllowBurst3ShotMode(mShotModeBurst3.isChecked());
                            GameSettingsRepository.getInstance().setAllowAutoShotMode(mShotModeAuto.isChecked());
                            if (mTcpClient != null) {
                                mTcpClient.sendPlayerSettings();
                            }
                            Intent intentSettings = new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE);
                            intentSettings.setPackage(mContext.getPackageName());
                            mContext.sendBroadcast(intentSettings);
                        } else {
                            PlayerRepository repo = PlayerRepository.getInstance();
                            synchronized (repo) {
                                PlayerSettings playerSettings = repo.getPlayerSettings().get(mPlayerID);
                                if (playerSettings == null) {
                                    playerSettings = new PlayerSettings();
                                    repo.getPlayerSettings().put(mPlayerID, playerSettings);
                                }
                                Integer value = Integer.parseInt(mHealthET.getText().toString());
                                if (value > 1000 || value <= 0) value = GameSettingsRepository.MAX_HEALTH;
                                playerSettings.health = value;
                                value = Integer.parseInt(mReloadShotsET.getText().toString());
                                if (value > 255 || value <= 0) value = (int) GameSettingsRepository.RELOAD_COUNT;
                                playerSettings.shots = (byte) (int) value;
                                value = Integer.parseInt(mReloadTimeET.getText().toString());
                                if (value > 10000 || value < 0)
                                    value = (int) GameSettingsRepository.RELOAD_TIME_MILLISECONDS;
                                playerSettings.reloadTime = value;
                                playerSettings.reloadOnEmpty = mReloadOnEmptySwitch.isChecked();
                                playerSettings.manualRespawn = mManualRespawnSwitch.isChecked();
                                value = Integer.parseInt(mSpawnTimeET.getText().toString());
                                if (value > 1000 || value <= 0)
                                    value = (int) GameSettingsRepository.RESPAWN_TIME_SECONDS;
                                playerSettings.spawnTime = value;
                                value = Integer.parseInt(mRespawnShieldTimeET.getText().toString());
                                if (value > 1000 || value < 0)
                                    value = (int) GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS;
                                playerSettings.respawnShieldTime = value;
                                value = Integer.parseInt(mDamageET.getText().toString());
                                if (value > 1000 || value <= 0) value = GameSettingsRepository.DAMAGE_PER_HIT * -1;
                                value = value * -1;
                                playerSettings.damage = value;
                                playerSettings.overrideLives = mOverrideLivesSwitch.isChecked();
                                value = Integer.parseInt(mLivesET.getText().toString());
                                if (value > 1000 || value <= 0)
                                    value = 0;
                                playerSettings.lives = value;
                                playerSettings.allowShotModeSingle = mShotModeSingle.isChecked();
                                playerSettings.allowShotModeBurst3 = mShotModeBurst3.isChecked();
                                playerSettings.allowShotModeAuto = mShotModeAuto.isChecked();
                                playerSettings.recoilSetting = mSelectedRecoilSetting;
                                if (mAdminSwitch != null) {
                                    playerSettings.isAdmin = mAdminSwitch.isChecked();
                                }
                                if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_no_cone)))
                                    playerSettings.firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE;
                                else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                                    playerSettings.firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE;
                                else
                                    playerSettings.firingMode = GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE;
                            }
                            byte targetPlayerID = mPlayerID;
                            if (mSelectedTargetTeam != 0 && isServer) {
                                byte newPlayerID = PlayerRepository.getInstance().findOpenPlayerIDOnTeam(mSelectedTargetTeam);
                                if (newPlayerID == 0) {
                                    Toast.makeText(getContext(), mContext.getString(R.string.error_team_full, mSelectedTargetTeam), Toast.LENGTH_SHORT).show();
                                } else if (mTcpServer != null) {
                                    mTcpServer.switchPlayerTeam(mPlayerID, newPlayerID);
                                    targetPlayerID = newPlayerID;
                                }
                            }
                            if (mTcpServer != null) {
                                boolean applyTeam = (mApplyTeamSwitch != null && mApplyTeamSwitch.isChecked());
                                mTcpServer.sendPlayerSettings(targetPlayerID, mApplyAllSwitch.isChecked(), applyTeam, GameSettingsRepository.getInstance().isAllowPlayerSettings());
                            }
                            Intent intentData = new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE);
                            intentData.setPackage(mContext.getPackageName());
                            mContext.sendBroadcast(intentData);
                        }
                        dismiss();
                    }
                });

        setButton(DialogInterface.BUTTON_NEGATIVE, mContext.getString(R.string.cancel),
                new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dismiss();
                    }
                });
        if (isServer)
            getServerSettings();
        else
            getLocalSettings();
        super.onCreate(savedInstanceState);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.firing_mode_outdoor_no_cone_item) {
            if (!isServer)
                GameSettingsRepository.getInstance().setCurrentFiringMode(GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE);
            mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
            return true;
        } else if (id == R.id.firing_mode_outdoor_with_cone_item) {
            if (!isServer)
                GameSettingsRepository.getInstance().setCurrentFiringMode(GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE);
            mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
            return true;
        } else if (id == R.id.firing_mode_indoor_no_cone_item) {
            if (!isServer)
                GameSettingsRepository.getInstance().setCurrentFiringMode(GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE);
            mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
            return true;
        }
        return false;
    }
}
