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

public class PlayerSettingsAlertDialog extends AlertDialog implements PopupMenu.OnMenuItemClickListener {
    private TextView mTitleTV = null;
    private EditText mHealthET = null;
    private EditText mReloadShotsET = null;
    private EditText mReloadTimeET = null;
    private Switch mReloadOnEmptySwitch = null;
    private EditText mSpawnTimeET = null;
    private EditText mDamageET = null;
    private Switch mOverrideLivesSwitch = null;
    private EditText mLivesET = null;
    private Switch mShotModeSingle = null;
    private Switch mShotModeBurst3 = null;
    private Switch mShotModeAuto = null;
    private Button mFiringModeButton = null;
    private Button mResetButton = null;
    private Switch mApplyAllSwitch = null;
    private Switch mAllowPlayerSettingsSwitch = null;

    private boolean isServer = false;
    private byte mPlayerID = 0;

    private TcpServer mTcpServer = null;
    private TcpClient mTcpClient = null;

    private Context mContext = null;

    public PlayerSettingsAlertDialog(Context context) {
        super(context);
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
        mTitleTV.setText(mContext.getString(R.string.player_settings_id_title, mPlayerID));
        mHealthET.setText("" + playerSettings.health);
        mReloadShotsET.setText("" + playerSettings.shots);
        mReloadTimeET.setText("" + playerSettings.reloadTime);
        mReloadOnEmptySwitch.setChecked(playerSettings.reloadOnEmpty);
        mSpawnTimeET.setText("" + playerSettings.spawnTime);
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
        mAllowPlayerSettingsSwitch.setChecked(Globals.getInstance().mAllowPlayerSettings);
    }

    public void setLocal(TcpClient tcpClient) {
        isServer = false;
        mTcpClient = tcpClient;
    }

    private void getLocalSettings() {
        mHealthET.setText("" + Globals.getInstance().mFullHealth);
        mReloadShotsET.setText("" + Globals.getInstance().mFullReload);
        mReloadTimeET.setText("" + Globals.getInstance().mReloadTime);
        mReloadOnEmptySwitch.setChecked(Globals.getInstance().mReloadOnEmpty);
        mSpawnTimeET.setText("" + Globals.getInstance().mRespawnTime);
        mDamageET.setText("" + (Globals.getInstance().mDamage * -1));
        mOverrideLivesSwitch.setChecked(Globals.getInstance().mOverrideLives);
        mLivesET.setText("" + Globals.getInstance().mOverrideLivesVal);
        mShotModeSingle.setChecked(Globals.getInstance().mAllowSingleShotMode);
        mShotModeBurst3.setChecked(Globals.getInstance().mAllowBurst3ShotMode);
        mShotModeAuto.setChecked(Globals.getInstance().mAllowAutoShotMode);
        mAllowPlayerSettingsSwitch.setVisibility(View.GONE);
        mFiringModeButton.setVisibility(View.GONE);
        mApplyAllSwitch.setVisibility(View.GONE);
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
                PopupMenu popup = new PopupMenu(getContext(), view);
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
                mDamageET.setText("" + (Globals.DAMAGE_PER_HIT * -1));
                mOverrideLivesSwitch.setChecked(false);
                mShotModeAuto.setChecked(true);
                mShotModeBurst3.setChecked(true);
                mShotModeSingle.setChecked(true);
                mFiringModeButton.setText(R.string.firing_mode_outdoor_no_cone);
                mLivesET.setText("" + 0);
            }
        });
        mAllowPlayerSettingsSwitch = view.findViewById(R.id.allow_player_settings_switch);
        mApplyAllSwitch = view.findViewById(R.id.apply_to_all_switch);
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
                            mContext.sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
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
                            if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_no_cone)))
                                playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_NO_CONE;
                            else if (mFiringModeButton.getText().equals(getContext().getString(R.string.firing_mode_outdoor_with_cone)))
                                playerSettings.firingMode = Globals.FIRING_MODE_OUTDOOR_WITH_CONE;
                            else
                                playerSettings.firingMode = Globals.FIRING_MODE_INDOOR_NO_CONE;
                            Globals.getInstance().mPlayerSettingsSemaphore.release();
                            if (mTcpServer != null) {
                                mTcpServer.sendPlayerSettings(mPlayerID, mApplyAllSwitch.isChecked(), mAllowPlayerSettingsSwitch.isChecked());
                            }
                            mContext.sendBroadcast(new Intent(NetMsg.NETMSG_PLAYERDATAUPDATE));
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
