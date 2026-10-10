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
import android.util.Log;
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

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

public class PlayerSettingsAlertDialog extends AlertDialog implements PopupMenu.OnMenuItemClickListener {
    private TextView mTitleTV = null;
    private EditText mHealthET = null;
    private EditText mReloadShotsET = null;
    private EditText mReloadTimeET = null;
    private Switch mReloadOnEmptySwitch = null;
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
    private int mSelectedRecoilSetting = Globals.RECOIL_SETTING_DEFAULT;
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
        Globals.getmPlayerSettingsSemaphore();
        Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(mPlayerID);
        if (playerSettings == null)
            playerSettings = new Globals.PlayerSettings();
        switch (Globals.getInstance().mGameMode) {
            case Globals.GAME_MODE_FFA:
                mTitleTV.setText(mContext.getString(R.string.player_settings_id_title, mPlayerID));
                break;
            case Globals.GAME_MODE_2TEAMS:
                if (mPlayerID > Globals.MAX_PLAYER_ID / 2)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 2, (mPlayerID - (Globals.MAX_PLAYER_ID / 2))));
                else
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 1, mPlayerID));
                break;
            case Globals.GAME_MODE_4TEAMS:
                int playersPerTeam = Globals.MAX_PLAYER_ID / 4;
                if (mPlayerID > playersPerTeam * 3)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 4, (mPlayerID - (playersPerTeam * 3))));
                else if (mPlayerID > playersPerTeam * 2)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 3, (mPlayerID - (playersPerTeam * 2))));
                else if (mPlayerID > playersPerTeam)
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 2, (mPlayerID - playersPerTeam)));
                else
                    mTitleTV.setText(mContext.getString(R.string.player_settings_team_title, 1, mPlayerID));
                break;
        }
        mHealthET.setText("" + playerSettings.health);
        mReloadShotsET.setText("" + playerSettings.shots);
        mReloadTimeET.setText("" + playerSettings.reloadTime);
        mReloadOnEmptySwitch.setChecked(playerSettings.reloadOnEmpty);
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
            case Globals.FIRING_MODE_OUTDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                break;
            case Globals.FIRING_MODE_OUTDOOR_WITH_CONE:
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                break;
            case Globals.FIRING_MODE_INDOOR_NO_CONE:
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                break;
        }
        Globals.getInstance().mPlayerSettingsSemaphore.release();
        mApplyAllSwitch.setChecked(false);
        if (mApplyTeamSwitch != null) {
            mApplyTeamSwitch.setChecked(false);
            if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA) {
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
            if (Globals.getInstance().mGameMode == Globals.GAME_MODE_FFA) {
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
            case Globals.RECOIL_SETTING_ENABLED:
                mRecoilSettingButton.setText(R.string.player_settings_recoil_enabled);
                break;
            case Globals.RECOIL_SETTING_DISABLED:
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
        mHealthET.setText("" + Globals.getInstance().mFullHealth);
        mReloadShotsET.setText("" + Globals.getInstance().mFullReload);
        mReloadTimeET.setText("" + Globals.getInstance().mReloadTime);
        mReloadOnEmptySwitch.setChecked(Globals.getInstance().mReloadOnEmpty);
        mSpawnTimeET.setText("" + Globals.getInstance().mRespawnTime);
        mRespawnShieldTimeET.setText("" + Globals.getInstance().mRespawnShieldTime);
        mDamageET.setText("" + (Globals.getInstance().mDamage * -1));
        mOverrideLivesSwitch.setChecked(Globals.getInstance().mOverrideLives);
        mLivesET.setText("" + Globals.getInstance().mOverrideLivesVal);
        mShotModeSingle.setChecked(Globals.getInstance().mAllowSingleShotMode);
        mShotModeBurst3.setChecked(Globals.getInstance().mAllowBurst3ShotMode);
        mShotModeAuto.setChecked(Globals.getInstance().mAllowAutoShotMode);
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
                mHealthET.setText("" + Globals.MAX_HEALTH);
                mReloadShotsET.setText("" + Globals.RELOAD_COUNT);
                mReloadTimeET.setText("" + Globals.RELOAD_TIME_MILLISECONDS);
                mReloadOnEmptySwitch.setChecked(false);
                mSpawnTimeET.setText("" + Globals.RESPAWN_TIME_SECONDS);
                mRespawnShieldTimeET.setText("" + Globals.RESPAWN_SHIELD_TIME_SECONDS);
                mDamageET.setText("" + (Globals.DAMAGE_PER_HIT * -1));
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
                    popup.getMenu().add(0, Globals.RECOIL_SETTING_DEFAULT, 10, R.string.player_settings_recoil_default);
                    popup.getMenu().add(0, Globals.RECOIL_SETTING_ENABLED, 20, R.string.player_settings_recoil_enabled);
                    popup.getMenu().add(0, Globals.RECOIL_SETTING_DISABLED, 30, R.string.player_settings_recoil_disabled);
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
                    int currentTeam = Globals.getInstance().calcNetworkTeam(mPlayerID);
                    int gameMode = Globals.getInstance().mGameMode;
                    int totalTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;

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
                            if (value > 1000 || value <= 0) value = Globals.MAX_HEALTH;
                            Globals.getInstance().mFullHealth = value;
                            value = Integer.parseInt(mReloadShotsET.getText().toString());
                            if (value > 255 || value <= 0) value = (int) Globals.RELOAD_COUNT;
                            Globals.getInstance().mFullReload = (byte) (int) value;
                            value = Integer.parseInt(mReloadTimeET.getText().toString());
                            if (value > 10000 || value < 0)
                                value = (int) Globals.RELOAD_TIME_MILLISECONDS;
                            Globals.getInstance().mReloadTime = value;
                            Globals.getInstance().mReloadOnEmpty = mReloadOnEmptySwitch.isChecked();
                            value = Integer.parseInt(mSpawnTimeET.getText().toString());
                            if (value > 1000 || value <= 0)
                                value = (int) Globals.RESPAWN_TIME_SECONDS;
                            Globals.getInstance().mRespawnTime = value;
                            value = Integer.parseInt(mRespawnShieldTimeET.getText().toString());
                            if (value > 1000 || value < 0)
                                value = (int) Globals.RESPAWN_SHIELD_TIME_SECONDS;
                            Globals.getInstance().mRespawnShieldTime = value;
                            GameSettingsRepository.getInstance().setRespawnShieldTime(value);
                            value = Integer.parseInt(mDamageET.getText().toString());
                            if (value > 1000 || value <= 0) value = Globals.DAMAGE_PER_HIT * -1;
                            value = value * -1;
                            Globals.getInstance().mDamage = value;
                            Globals.getInstance().mOverrideLives = mOverrideLivesSwitch.isChecked();
                            value = Integer.parseInt(mLivesET.getText().toString());
                            if (value > 1000 || value <= 0)
                                value = 0;
                            Globals.getInstance().mOverrideLivesVal = value;
                            Globals.getInstance().mAllowSingleShotMode = mShotModeSingle.isChecked();
                            Globals.getInstance().mAllowBurst3ShotMode = mShotModeBurst3.isChecked();
                            Globals.getInstance().mAllowAutoShotMode = mShotModeAuto.isChecked();
                            if (mTcpClient != null) {
                                mTcpClient.sendPlayerSettings();
                            }
                            Intent intentSettings = new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE);
                            intentSettings.setPackage(mContext.getPackageName());
                            mContext.sendBroadcast(intentSettings);
                        } else {
                            Globals.getmPlayerSettingsSemaphore();
                            Globals.PlayerSettings playerSettings = Globals.getInstance().mPlayerSettings.get(mPlayerID);
                            if (playerSettings == null) {
                                playerSettings = new Globals.PlayerSettings();
                                Globals.getInstance().mPlayerSettings.put(mPlayerID, playerSettings);
                            }
                            Integer value = Integer.parseInt(mHealthET.getText().toString());
                            if (value > 1000 || value <= 0) value = Globals.MAX_HEALTH;
                            playerSettings.health = value;
                            value = Integer.parseInt(mReloadShotsET.getText().toString());
                            if (value > 255 || value <= 0) value = (int) Globals.RELOAD_COUNT;
                            playerSettings.shots = (byte) (int) value;
                            value = Integer.parseInt(mReloadTimeET.getText().toString());
                            if (value > 10000 || value < 0)
                                value = (int) Globals.RELOAD_TIME_MILLISECONDS;
                            playerSettings.reloadTime = value;
                            playerSettings.reloadOnEmpty = mReloadOnEmptySwitch.isChecked();
                            value = Integer.parseInt(mSpawnTimeET.getText().toString());
                            if (value > 1000 || value <= 0)
                                value = (int) Globals.RESPAWN_TIME_SECONDS;
                            playerSettings.spawnTime = value;
                            value = Integer.parseInt(mRespawnShieldTimeET.getText().toString());
                            if (value > 1000 || value < 0)
                                value = (int) Globals.RESPAWN_SHIELD_TIME_SECONDS;
                            playerSettings.respawnShieldTime = value;
                            value = Integer.parseInt(mDamageET.getText().toString());
                            if (value > 1000 || value <= 0) value = Globals.DAMAGE_PER_HIT * -1;
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
                                playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                            else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                                playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                            else
                                playerSettings.firingMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                            Globals.getInstance().mPlayerSettingsSemaphore.release();
                            byte targetPlayerID = mPlayerID;
                            if (mSelectedTargetTeam != 0 && isServer) {
                                byte newPlayerID = Globals.findOpenPlayerIDOnTeam(mSelectedTargetTeam);
                                if (newPlayerID == 0) {
                                    Toast.makeText(getContext(), mContext.getString(R.string.error_team_full, mSelectedTargetTeam), Toast.LENGTH_SHORT).show();
                                } else if (mTcpServer != null) {
                                    mTcpServer.switchPlayerTeam(mPlayerID, newPlayerID);
                                    targetPlayerID = newPlayerID;
                                }
                            }
                            if (mTcpServer != null) {
                                boolean applyTeam = (mApplyTeamSwitch != null && mApplyTeamSwitch.isChecked());
                                mTcpServer.sendPlayerSettings(targetPlayerID, mApplyAllSwitch.isChecked(), applyTeam, Globals.getInstance().mAllowPlayerSettings);
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
        switch (item.getItemId()) {
            case R.id.firing_mode_outdoor_no_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                return true;
            case R.id.firing_mode_outdoor_with_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                mFiringModeButton.setText(R.string.firing_mode_outdoor_with_cone);
                return true;
            case R.id.firing_mode_indoor_no_cone_item:
                if (!isServer)
                    Globals.getInstance().mCurrentFiringMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                mFiringModeButton.setText(R.string.firing_mode_indoor_no_cone);
                return true;
            default:
                return false;
        }
    }
}
