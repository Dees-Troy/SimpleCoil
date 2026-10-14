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

package com.simplecoil.simplecoil.data.model;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

/** One network player's settings, as the server assigns them. */
public class PlayerSettings {
    public volatile int health = GameSettingsRepository.MAX_HEALTH;
    public volatile byte shots = GameSettingsRepository.RELOAD_COUNT;
    public volatile long reloadTime = GameSettingsRepository.RELOAD_TIME_MILLISECONDS;
    public volatile boolean reloadOnEmpty = false;
    public volatile boolean manualRespawn = false;
    public volatile long spawnTime = GameSettingsRepository.RESPAWN_TIME_SECONDS;
    public volatile long respawnShieldTime = GameSettingsRepository.RESPAWN_SHIELD_TIME_SECONDS;
    public volatile int damage = GameSettingsRepository.DAMAGE_PER_HIT;
    public volatile boolean overrideLives = false;
    public volatile int lives = 0;
    public volatile boolean allowShotModeSingle = true;
    public volatile boolean allowShotModeBurst3 = true;
    public volatile boolean allowShotModeAuto = true;
    public volatile int firingMode = GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE;
    public volatile int recoilSetting = GameSettingsRepository.RECOIL_SETTING_DEFAULT;
    public volatile boolean isAdmin = false;
}
