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

import com.simplecoil.simplecoil.data.model.GpsData;
import com.simplecoil.simplecoil.data.model.PlayerSettings;
import com.simplecoil.simplecoil.domain.team.TeamLayout;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerRepository {
    private static volatile PlayerRepository instance;

    public static final int INVALID_PLAYER_ID = -100;
    public static final int GRENADE_PLAYER_ID = 167;
    public static final int MAX_GRENADE_IDS = 16;

    // This phone's own player. The dedicated server shares the process but is not a player, so the
    // server-side lookups below (auto-sort, open ids, name checks, purge) never consult these.
    private volatile byte playerID = 0;
    private volatile String playerName = "";
    private volatile boolean isAdmin = false;
    private volatile String deviceAddress = "";

    private final Map<InetAddress, Byte> ipTeamMap = new ConcurrentHashMap<>();
    private final Map<Byte, InetAddress> teamIPMap = new ConcurrentHashMap<>();
    private final Map<Byte, String> teamPlayerNameMap = new ConcurrentHashMap<>();
    private final Map<Byte, GpsData> gpsData = new ConcurrentHashMap<>();
    private final Map<Byte, PlayerSettings> playerSettings = new ConcurrentHashMap<>();
    private final Map<String, Byte> macPlayerMap = new ConcurrentHashMap<>();
    private final Map<Byte, String> playerMacMap = new ConcurrentHashMap<>();

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
    public void setPlayerID(byte playerID) { this.playerID = playerID; }

    public String getPlayerName() { return playerName; }
    public void setPlayerName(String playerName) {
        this.playerName = playerName != null ? playerName : "";
    }

    /** The paired blaster's Bluetooth address, sent to the server so it can recognise a rejoin. */
    public String getDeviceAddress() { return deviceAddress; }
    public void setDeviceAddress(String deviceAddress) {
        this.deviceAddress = deviceAddress != null ? deviceAddress : "";
    }

    public boolean isAdmin() { return isAdmin; }
    public void setAdmin(boolean admin) { isAdmin = admin; }

    public byte getPairedGrenadeID() { return pairedGrenadeID; }
    public void setPairedGrenadeID(byte pairedGrenadeID) { this.pairedGrenadeID = pairedGrenadeID; }

    public Map<InetAddress, Byte> getIpTeamMap() { return ipTeamMap; }
    public Map<Byte, InetAddress> getTeamIPMap() { return teamIPMap; }
    public Map<Byte, String> getTeamPlayerNameMap() { return teamPlayerNameMap; }
    public Map<Byte, GpsData> getGpsData() { return gpsData; }
    public Map<Byte, PlayerSettings> getPlayerSettings() { return playerSettings; }
    public Map<String, Byte> getMacPlayerMap() { return macPlayerMap; }
    public Map<Byte, String> getPlayerMacMap() { return playerMacMap; }

    public synchronized int[] getGrenadePairings() {
        return grenadePairings.clone();
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

    /** Pairs [grenadeId] with [playerId]; ignores ids outside the grenade range. */
    public synchronized void pairGrenade(int grenadeId, int playerId) {
        setGrenadePairing(grenadeId, playerId);
    }

    public synchronized void clearGrenadePairings() {
        Arrays.fill(grenadePairings, INVALID_PLAYER_ID);
    }

    /** Unpairs every grenade paired with [playerId]. */
    public synchronized void removeGrenadePairings(byte playerId) {
        for (int i = 0; i < grenadePairings.length; i++) {
            if (grenadePairings[i] == playerId) grenadePairings[i] = INVALID_PLAYER_ID;
        }
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
            // Ensure any stale entries in macPlayerMap referencing this playerId are removed
            for (Map.Entry<String, Byte> entry : new HashSet<>(macPlayerMap.entrySet())) {
                if (entry.getValue() != null && entry.getValue() == playerId) {
                    macPlayerMap.remove(entry.getKey());
                }
            }
        }
    }

    public synchronized void purgeDisconnectedPlayers(Set<Byte> activePlayerIds) {
        Set<Byte> activeIds = activePlayerIds != null ? activePlayerIds : Collections.emptySet();

        Set<Byte> knownIds = new HashSet<>();
        knownIds.addAll(teamIPMap.keySet());
        knownIds.addAll(teamPlayerNameMap.keySet());
        knownIds.addAll(playerMacMap.keySet());

        for (byte id : knownIds) {
            if (id != 0 && !activeIds.contains(id)) {
                removePlayer(id, false);
            }
        }
    }

    public synchronized void reassignPlayer(byte oldPlayerId, byte newPlayerId) {
        InetAddress ip = teamIPMap.remove(oldPlayerId);
        String name = teamPlayerNameMap.remove(oldPlayerId);
        PlayerSettings settings = playerSettings.remove(oldPlayerId);
        GpsData gps = gpsData.remove(oldPlayerId);
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

    /**
     * Moves every player in [oldToNew] to its new id, in all the maps. Players not in [oldToNew]
     * are dropped.
     */
    public synchronized void remapPlayers(Map<Byte, Byte> oldToNew) {
        Map<Byte, InetAddress> newTeamIPMap = new HashMap<>();
        Map<InetAddress, Byte> newIPTeamMap = new HashMap<>();
        Map<Byte, String> newNameMap = new HashMap<>();
        Map<Byte, PlayerSettings> newSettingsMap = new HashMap<>();
        Map<Byte, GpsData> newGPSMap = new HashMap<>();
        Map<Byte, String> newPlayerMacMap = new HashMap<>();
        Map<String, Byte> newMacPlayerMap = new HashMap<>();
        for (Map.Entry<Byte, Byte> entry : oldToNew.entrySet()) {
            byte oldId = entry.getKey();
            byte newId = entry.getValue();
            InetAddress ip = teamIPMap.get(oldId);
            if (ip != null) {
                newTeamIPMap.put(newId, ip);
                newIPTeamMap.put(ip, newId);
            }
            String name = teamPlayerNameMap.get(oldId);
            if (name != null && !name.isEmpty()) newNameMap.put(newId, name);
            PlayerSettings settings = playerSettings.get(oldId);
            if (settings != null) newSettingsMap.put(newId, settings);
            GpsData gps = gpsData.get(oldId);
            if (gps != null) newGPSMap.put(newId, gps);
            String mac = playerMacMap.get(oldId);
            if (mac != null && !mac.isEmpty()) {
                newPlayerMacMap.put(newId, mac);
                newMacPlayerMap.put(mac, newId);
            }
        }
        updateAllPlayerMappings(newTeamIPMap, newIPTeamMap, newNameMap, newSettingsMap, newGPSMap, newPlayerMacMap, newMacPlayerMap);
    }

    public synchronized void updateAllPlayerMappings(
            Map<Byte, InetAddress> newTeamIPMap,
            Map<InetAddress, Byte> newIPTeamMap,
            Map<Byte, String> newNameMap,
            Map<Byte, PlayerSettings> newSettingsMap,
            Map<Byte, GpsData> newGPSMap,
            Map<Byte, String> newPlayerMacMap,
            Map<String, Byte> newMacPlayerMap) {
        teamIPMap.clear();
        if (newTeamIPMap != null) {
            teamIPMap.putAll(newTeamIPMap);
        }

        ipTeamMap.clear();
        if (newIPTeamMap != null) {
            ipTeamMap.putAll(newIPTeamMap);
        }

        teamPlayerNameMap.clear();
        if (newNameMap != null) {
            teamPlayerNameMap.putAll(newNameMap);
        }

        playerSettings.clear();
        if (newSettingsMap != null) {
            playerSettings.putAll(newSettingsMap);
        }

        gpsData.clear();
        if (newGPSMap != null) {
            gpsData.putAll(newGPSMap);
        }

        playerMacMap.clear();
        if (newPlayerMacMap != null) {
            playerMacMap.putAll(newPlayerMacMap);
        }

        macPlayerMap.clear();
        if (newMacPlayerMap != null) {
            macPlayerMap.putAll(newMacPlayerMap);
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
    }

    /** The team [id] is on under the current team layout (see TeamLayout.teamOf). */
    public int calcNetworkTeam(byte id) {
        return GameSettingsRepository.getInstance().getTeamLayout().teamOf(id);
    }

    public synchronized byte findAutoSortPlayerID() {
        return findAutoSortPlayerID(null);
    }

    public synchronized byte findAutoSortPlayerID(Set<Byte> additionalOccupiedIDs) {
        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
        int maxPlayers = GameSettingsRepository.MAX_PLAYER_ID;

        boolean[] occupied = new boolean[maxPlayers + 1];
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

        if (layout.getMode().isFreeForAll()) {
            for (byte i = 1; i <= maxPlayers; i++) {
                if (!occupied[i]) {
                    return i;
                }
            }
        } else {
            int numTeams = layout.getMode().teamCount;
            int[] teamSizes = layout.teamSizes();
            int[] teamCounts = new int[numTeams + 1];

            for (byte i = 1; i <= maxPlayers; i++) {
                if (occupied[i]) {
                    int team = layout.teamOf(i);
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
                int startID = layout.teamStartId(bestTeam);
                int endID = layout.teamEndId(bestTeam);
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
        TeamLayout layout = GameSettingsRepository.getInstance().getTeamLayout();
        int numTeams = layout.getMode().isFreeForAll() ? 2 : layout.getMode().teamCount;
        if (targetTeam < 1 || targetTeam > numTeams) {
            return 0;
        }

        int maxPlayers = GameSettingsRepository.MAX_PLAYER_ID;
        boolean[] occupied = new boolean[maxPlayers + 1];
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

        int startID = layout.teamStartId(targetTeam);
        int endID = layout.teamEndId(targetTeam);

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
