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

package com.simplecoil.simplecoil.data.repository;

import com.simplecoil.simplecoil.Globals;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerRepository {
    private static volatile PlayerRepository instance;

    public static final int INVALID_PLAYER_ID = -100;
    public static final int GRENADE_PLAYER_ID = 167;
    public static final int MAX_GRENADE_IDS = 16;

    private volatile byte playerID = 0;
    private volatile String playerName = "";
    private volatile boolean isAdmin = false;

    private final Map<InetAddress, Byte> ipTeamMap = new ConcurrentHashMap<>();
    private final Map<Byte, InetAddress> teamIPMap = new ConcurrentHashMap<>();
    private final Map<Byte, String> teamPlayerNameMap = new ConcurrentHashMap<>();
    private final Map<Byte, Globals.GPSData> gpsData = new ConcurrentHashMap<>();
    private final Map<Byte, Globals.PlayerSettings> playerSettings = new ConcurrentHashMap<>();
    private final Map<String, Byte> macPlayerMap = new ConcurrentHashMap<>();
    private final Map<Byte, String> playerMacMap = new ConcurrentHashMap<>();

    public static class ActiveGamePlayerInfo {
        public final String macAddress;
        public final byte playerId;
        public final int team;
        public final String originalName;

        public ActiveGamePlayerInfo(String macAddress, byte playerId, int team, String originalName) {
            this.macAddress = macAddress;
            this.playerId = playerId;
            this.team = team;
            this.originalName = originalName != null ? originalName : "";
        }
    }

    private final Map<String, ActiveGamePlayerInfo> activeGameMacMap = new ConcurrentHashMap<>();

    private volatile byte pairedGrenadeID = 0;
    private final int[] grenadePairings = new int[MAX_GRENADE_IDS];

    private PlayerRepository() {
        clearGrenadePairings();
    }

    public static PlayerRepository getInstance() {
        if (instance == null) {
            synchronized (PlayerRepository.class) {
                if (instance == null) {
                    instance = new PlayerRepository();
                }
            }
        }
        return instance;
    }

    public byte getPlayerID() { return playerID; }
    public void setPlayerID(byte playerID) {
        this.playerID = playerID;
        Globals.updatePlayerIDFromRepo(playerID);
    }
    public void setPlayerIDDirect(byte playerID) {
        this.playerID = playerID;
    }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) {
        this.playerName = playerName != null ? playerName : "";
        Globals.updatePlayerNameFromRepo(this.playerName);
    }
    public void setPlayerNameDirect(String playerName) {
        this.playerName = playerName != null ? playerName : "";
    }

    public boolean isAdmin() { return isAdmin; }
    public void setAdmin(boolean admin) { isAdmin = admin; }

    public byte getPairedGrenadeID() { return pairedGrenadeID; }
    public void setPairedGrenadeID(byte pairedGrenadeID) { this.pairedGrenadeID = pairedGrenadeID; }

    public Map<InetAddress, Byte> getIpTeamMap() { return ipTeamMap; }
    public Map<Byte, InetAddress> getTeamIPMap() { return teamIPMap; }
    public Map<Byte, String> getTeamPlayerNameMap() { return teamPlayerNameMap; }
    public Map<Byte, Globals.GPSData> getGpsData() { return gpsData; }
    public Map<Byte, Globals.PlayerSettings> getPlayerSettings() { return playerSettings; }
    public Map<String, Byte> getMacPlayerMap() { return macPlayerMap; }
    public Map<Byte, String> getPlayerMacMap() { return playerMacMap; }

    public synchronized int[] getGrenadePairings() {
        return grenadePairings.clone();
    }

    public synchronized int[] getGrenadePairingsInternal() {
        return grenadePairings;
    }

    public synchronized int getGrenadePairing(int index) {
        if (index >= 0 && index < MAX_GRENADE_IDS) {
            return grenadePairings[index];
        }
        return INVALID_PLAYER_ID;
    }

    public synchronized void setGrenadePairing(int index, int value) {
        if (index >= 0 && index < MAX_GRENADE_IDS) {
            grenadePairings[index] = value;
        }
    }

    public synchronized void clearGrenadePairings() {
        Arrays.fill(grenadePairings, INVALID_PLAYER_ID);
    }

    public synchronized void addOrUpdatePlayer(byte playerId, InetAddress ip, String name) {
        addOrUpdatePlayer(playerId, ip, name, null);
    }

    public synchronized void addOrUpdatePlayer(byte playerId, InetAddress ip, String name, String mac) {
        if (ip != null) {
            teamIPMap.put(playerId, ip);
            ipTeamMap.put(ip, playerId);
        }
        if (name != null) {
            teamPlayerNameMap.put(playerId, name);
        }
        if (mac != null && !mac.isEmpty()) {
            String oldMac = playerMacMap.remove(playerId);
            if (oldMac != null) {
                macPlayerMap.remove(oldMac);
            }
            macPlayerMap.put(mac, playerId);
            playerMacMap.put(playerId, mac);
        }
    }

    public synchronized Byte getPlayerIdByMac(String mac) {
        if (mac == null || mac.isEmpty()) return null;
        return macPlayerMap.get(mac);
    }

    public synchronized void clearActiveGameMacMap() {
        activeGameMacMap.clear();
    }

    public synchronized void addActiveGamePlayer(String mac, byte playerId, int team, String originalName) {
        if (mac != null && !mac.isEmpty()) {
            activeGameMacMap.put(mac, new ActiveGamePlayerInfo(mac, playerId, team, originalName));
        }
    }

    public synchronized ActiveGamePlayerInfo getActiveGamePlayerByMac(String mac) {
        if (mac == null || mac.isEmpty()) return null;
        return activeGameMacMap.get(mac);
    }

    public Map<String, ActiveGamePlayerInfo> getActiveGameMacMap() {
        return activeGameMacMap;
    }

    public synchronized boolean isNameUnique(String name, byte excludePlayerId) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String trimmed = name.trim();
        for (Map.Entry<Byte, String> entry : teamPlayerNameMap.entrySet()) {
            if (excludePlayerId != 0 && entry.getKey() == excludePlayerId) {
                continue;
            }
            if (trimmed.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        if (excludePlayerId != playerID && trimmed.equalsIgnoreCase(playerName)) {
            return false;
        }
        return true;
    }

    public synchronized void removePlayer(byte playerId) {
        removePlayer(playerId, GameStateRepository.getInstance().isGameRunning());
    }

    public synchronized void removePlayer(byte playerId, boolean isGameRunning) {
        InetAddress ip = teamIPMap.remove(playerId);
        if (ip != null) {
            ipTeamMap.remove(ip);
        }
        gpsData.remove(playerId);
        playerSettings.remove(playerId);
        if (!isGameRunning) {
            teamPlayerNameMap.remove(playerId);
            String mac = playerMacMap.remove(playerId);
            if (mac != null) {
                macPlayerMap.remove(mac);
            }
        }
    }

    public synchronized void reassignPlayer(byte oldPlayerId, byte newPlayerId) {
        InetAddress ip = teamIPMap.remove(oldPlayerId);
        String name = teamPlayerNameMap.remove(oldPlayerId);
        Globals.PlayerSettings settings = playerSettings.remove(oldPlayerId);
        Globals.GPSData gps = gpsData.remove(oldPlayerId);
        String mac = playerMacMap.remove(oldPlayerId);

        if (ip != null) {
            teamIPMap.put(newPlayerId, ip);
            ipTeamMap.put(ip, newPlayerId);
        }
        if (name != null) {
            teamPlayerNameMap.put(newPlayerId, name);
        }
        if (settings != null) {
            playerSettings.put(newPlayerId, settings);
        }
        if (gps != null) {
            gpsData.put(newPlayerId, gps);
        }
        if (mac != null && !mac.isEmpty()) {
            macPlayerMap.put(mac, newPlayerId);
            playerMacMap.put(newPlayerId, mac);
        }
    }

    public synchronized void clearAllPlayers() {
        teamIPMap.clear();
        ipTeamMap.clear();
        teamPlayerNameMap.clear();
        gpsData.clear();
        playerSettings.clear();
        macPlayerMap.clear();
        playerMacMap.clear();
        activeGameMacMap.clear();
    }

    public synchronized int calcNetworkTeam(byte id) {
        if (id < 1 || id > GameSettingsRepository.MAX_PLAYER_ID) {
            return INVALID_PLAYER_ID;
        }
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        int gameMode = settings.getGameMode();
        if (gameMode == GameSettingsRepository.GAME_MODE_FFA) {
            return id;
        }
        int numTeams = (gameMode == GameSettingsRepository.GAME_MODE_4TEAMS) ? 4 : 2;
        int[] sizes = settings.getTeamSizes();
        if (sizes == null || sizes.length < numTeams) {
            return numTeams;
        }
        int currentCumulative = 0;
        for (int t = 0; t < numTeams; t++) {
            currentCumulative += sizes[t];
            if (id <= currentCumulative) {
                return t + 1;
            }
        }
        return numTeams;
    }

    public synchronized byte findAutoSortPlayerID() {
        return findAutoSortPlayerID(null);
    }

    public synchronized byte findAutoSortPlayerID(Set<Byte> additionalOccupiedIDs) {
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        int gameMode = settings.getGameMode();
        int maxPlayers = GameSettingsRepository.MAX_PLAYER_ID;

        boolean[] occupied = new boolean[maxPlayers + 1];
        if (playerID != 0 && playerID <= maxPlayers) {
            occupied[playerID] = true;
        }
        for (byte id : teamIPMap.keySet()) {
            if (id >= 1 && id <= maxPlayers) {
                occupied[id] = true;
            }
        }
        if (additionalOccupiedIDs != null) {
            for (byte id : additionalOccupiedIDs) {
                if (id >= 1 && id <= maxPlayers) {
                    occupied[id] = true;
                }
            }
        }

        if (gameMode == GameSettingsRepository.GAME_MODE_FFA) {
            for (byte i = 1; i <= maxPlayers; i++) {
                if (!occupied[i]) {
                    return i;
                }
            }
        } else {
            int numTeams = (gameMode == GameSettingsRepository.GAME_MODE_4TEAMS) ? 4 : 2;
            int[] teamSizes = settings.getTeamSizes();
            int[] teamCounts = new int[numTeams + 1];

            for (byte i = 1; i <= maxPlayers; i++) {
                if (occupied[i]) {
                    int team = calcNetworkTeam(i);
                    if (team >= 1 && team <= numTeams) {
                        teamCounts[team]++;
                    }
                }
            }

            double minRatio = Double.MAX_VALUE;
            int bestTeam = -1;
            for (int t = 1; t <= numTeams; t++) {
                int capacity = teamSizes[t - 1];
                if (teamCounts[t] < capacity) {
                    double fillRatio = (double) teamCounts[t] / capacity;
                    if (fillRatio < minRatio) {
                        minRatio = fillRatio;
                        bestTeam = t;
                    }
                }
            }

            if (bestTeam != -1) {
                int startID = settings.getTeamStartID(bestTeam);
                int endID = settings.getTeamEndID(bestTeam);
                for (int i = startID; i <= endID; i++) {
                    byte testID = (byte) i;
                    if (!occupied[testID]) {
                        return testID;
                    }
                }
            }

            for (byte i = 1; i <= maxPlayers; i++) {
                if (!occupied[i]) {
                    return i;
                }
            }
        }
        return 0;
    }

    public synchronized byte findOpenPlayerIDOnTeam(int targetTeam) {
        return findOpenPlayerIDOnTeam(targetTeam, null);
    }

    public synchronized byte findOpenPlayerIDOnTeam(int targetTeam, Set<Byte> additionalOccupiedIDs) {
        GameSettingsRepository settings = GameSettingsRepository.getInstance();
        int gameMode = settings.getGameMode();
        int numTeams = (gameMode == GameSettingsRepository.GAME_MODE_4TEAMS) ? 4 : 2;
        if (targetTeam < 1 || targetTeam > numTeams) {
            return 0;
        }

        int maxPlayers = GameSettingsRepository.MAX_PLAYER_ID;
        boolean[] occupied = new boolean[maxPlayers + 1];
        if (playerID != 0 && playerID <= maxPlayers) {
            occupied[playerID] = true;
        }
        for (byte id : teamIPMap.keySet()) {
            if (id >= 1 && id <= maxPlayers) {
                occupied[id] = true;
            }
        }
        if (additionalOccupiedIDs != null) {
            for (byte id : additionalOccupiedIDs) {
                if (id >= 1 && id <= maxPlayers) {
                    occupied[id] = true;
                }
            }
        }

        int startID = settings.getTeamStartID(targetTeam);
        int endID = settings.getTeamEndID(targetTeam);

        for (int i = startID; i <= endID; i++) {
            byte testID = (byte) i;
            if (!occupied[testID]) {
                return testID;
            }
        }
        return 0;
    }

    public String getPlayerName(Byte id) {
        if (id == null) return "";
        String name = teamPlayerNameMap.get(id);
        return name != null ? name : "";
    }

    public int getPlayerCount(boolean isDedicated) {
        int ret = teamIPMap.size();
        return ret + (isDedicated ? 0 : 1);
    }

    public static String getIPAddressStr() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }

    public static InetAddress getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return addr;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}
