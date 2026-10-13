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

package com.simplecoil.simplecoil.network.server;

import com.simplecoil.simplecoil.Globals;
import com.simplecoil.simplecoil.NameGenerator;
import com.simplecoil.simplecoil.ProfanityFilter;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dedicated server's players, connections and game roster (reconnect design §3.2). Player
 * records are keyed by player id, connections only by their connection id, so nothing that
 * renumbers players can collide with a connection (D4, D9).
 *
 * One lock, the roster itself, guards all of it. No method does socket I/O: each returns what
 * changed, and TcpServer sends the messages after the lock is released. PlayerRepository's maps
 * (ip, name, MAC, settings, GPS) are kept in step for the screens that read them.
 */
public final class GameRoster {

    /** What a hello did. */
    public static final class HelloResult {
        public enum Kind { JOINED, REJOINED, REFUSED_IN_PROGRESS, FULL }

        public final Kind kind;
        /** The player the connection now speaks for (JOINED, REJOINED). */
        public final PlayerRecord record;
        /** The player's previous connection, replaced by this one; close it. */
        public final Connection displaced;

        HelloResult(Kind kind, PlayerRecord record, Connection displaced) {
            this.kind = kind;
            this.record = record;
            this.displaced = displaced;
        }
    }

    /** A reported elimination: [victimId] (0 if the sender had not said hello) was eliminated by [shooterId]. */
    public static final class Elimination {
        public final byte victimId;
        public final byte shooterId;
        /** The shooter's points after the kill; 0 if the shooter is unknown. */
        public final int shooterPoints;

        Elimination(byte victimId, byte shooterId, int shooterPoints) {
            this.victimId = victimId;
            this.shooterId = shooterId;
            this.shooterPoints = shooterPoints;
        }
    }

    /** Result of renumbering everyone. */
    public static final class Rebalance {
        /** Every player that was given an id, in order; each is told its (possibly unchanged) id. */
        public final List<PlayerRecord> assigned;
        /** Connections of players that did not fit; close them. */
        public final List<Connection> dropped;

        Rebalance(List<PlayerRecord> assigned, List<Connection> dropped) {
            this.assigned = assigned;
            this.dropped = dropped;
        }
    }

    /** A player's score. */
    public static final class Score {
        public final int points;
        public final int eliminated;
        public final boolean connected;

        Score(int points, int eliminated, boolean connected) {
            this.points = points;
            this.eliminated = eliminated;
            this.connected = connected;
        }
    }

    private int nextConnectionId = 0;
    private SessionPhase phase = SessionPhase.LOBBY;
    private final Map<Byte, PlayerRecord> byId = new TreeMap<>();
    private final Map<String, PlayerRecord> cacheByMac = new HashMap<>();
    private final Map<Integer, Connection> connections = new LinkedHashMap<>();

    private final PlayerRepository repo = PlayerRepository.getInstance();

    // ---------------------------------------------------------------------------------------
    // Connections
    // ---------------------------------------------------------------------------------------

    /** Forgets every player and connection (the server is starting). Does not close sockets. */
    public synchronized void clear() {
        nextConnectionId = 0;
        phase = SessionPhase.LOBBY;
        byId.clear();
        cacheByMac.clear();
        connections.clear();
    }

    /** Wraps an accepted socket in a new connection with the next connection id. */
    public synchronized Connection accept(java.net.Socket socket) {
        Connection connection = new Connection(++nextConnectionId, socket);
        connections.put(connection.getConnectionId(), connection);
        return connection;
    }

    /** Forgets a connection that was refused or closed, without touching its player. */
    public synchronized void removeConnection(Connection connection) {
        connections.remove(connection.getConnectionId());
        PlayerRecord record = connection.player;
        if (record != null && record.connection == connection)
            record.connection = null;
        connection.player = null;
    }

    public synchronized List<Connection> connections() {
        return new ArrayList<>(connections.values());
    }

    public synchronized int connectionCount() {
        return connections.size();
    }

    /** Players with a live connection. */
    public synchronized int connectedCount() {
        int count = 0;
        for (PlayerRecord record : byId.values()) {
            if (record.connection != null) count++;
        }
        return count;
    }

    /** Players plus connections that have not said hello: what the server used to count as its clients. */
    public synchronized int size() {
        int size = byId.size();
        for (Connection connection : connections.values()) {
            if (connection.player == null) size++;
        }
        return size;
    }

    public synchronized boolean isEmpty() {
        return byId.isEmpty() && connections.isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------

    public synchronized PlayerRecord record(byte playerId) {
        return byId.get(playerId);
    }

    public synchronized List<PlayerRecord> records() {
        return new ArrayList<>(byId.values());
    }

    /** Players whose team is [team]. */
    public synchronized List<PlayerRecord> recordsOnTeam(int team) {
        List<PlayerRecord> result = new ArrayList<>();
        for (PlayerRecord record : byId.values()) {
            if (record.team == team) result.add(record);
        }
        return result;
    }

    public synchronized Score score(byte playerId) {
        PlayerRecord record = byId.get(playerId);
        if (record == null) return null;
        return new Score(record.points, record.eliminated, record.connection != null);
    }

    /** Points of every player whose id falls in [team]. */
    public synchronized int teamPoints(int team) {
        int points = 0;
        for (PlayerRecord record : byId.values()) {
            if (Globals.getInstance().calcNetworkTeam(record.playerId) == team) points += record.points;
        }
        return points;
    }

    public synchronized SessionPhase phase() {
        return phase;
    }

    public synchronized boolean isRunning() {
        return phase == SessionPhase.RUNNING;
    }

    public synchronized boolean isCached(String mac) {
        return mac != null && cacheByMac.containsKey(mac);
    }

    // ---------------------------------------------------------------------------------------
    // Game transitions
    // ---------------------------------------------------------------------------------------

    /**
     * Starts a game from the lobby: scores to 0 and every player with a MAC into the roster
     * cache. False (and nothing changes) outside the lobby (after a game, reset first) or if
     * nobody is there.
     */
    public synchronized boolean startGame() {
        if (phase != SessionPhase.LOBBY) return false;
        if (connections.isEmpty() && byId.isEmpty()) return false;
        phase = SessionPhase.RUNNING;
        resetScoresLocked();
        cacheByMac.clear();
        for (PlayerRecord record : byId.values()) {
            if (!record.blasterMac.isEmpty()) cacheByMac.put(record.blasterMac, record);
        }
        return true;
    }

    /**
     * Ends a running game. The roster cache and every player, connected or not, are kept so that
     * cached players can still come back. False if no game was running.
     */
    public synchronized boolean endGame() {
        if (phase != SessionPhase.RUNNING) return false;
        phase = SessionPhase.POST_GAME;
        return true;
    }

    /**
     * After a game: removes every disconnected player, zeroes the others, forgets the roster
     * cache and returns to the lobby. Returns the removed players, or null outside POST_GAME.
     */
    public synchronized List<PlayerRecord> reset() {
        if (phase != SessionPhase.POST_GAME) return null;
        return resetLocked();
    }

    private List<PlayerRecord> resetLocked() {
        List<PlayerRecord> removed = new ArrayList<>();
        Set<Byte> remainingIds = new HashSet<>();
        for (PlayerRecord record : new ArrayList<>(byId.values())) {
            if (record.connection == null) {
                removeRecordLocked(record);
                repo.removePlayer(record.playerId, false);
                repo.removeGrenadePairings(record.playerId);
                removed.add(record);
            } else {
                remainingIds.add(record.playerId);
            }
        }
        repo.purgeDisconnectedPlayers(remainingIds);
        resetScoresLocked();
        cacheByMac.clear();
        phase = SessionPhase.LOBBY;
        return removed;
    }

    public synchronized void resetScores() {
        resetScoresLocked();
    }

    private void resetScoresLocked() {
        for (PlayerRecord record : byId.values()) {
            record.points = 0;
            record.eliminated = 0;
            record.messageQueue.clear();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------

    /**
     * A client's hello: [requestedId] (0 = auto-sort), [requestedName], [mac] ("" or null if none)
     * and the address it connected from.
     */
    public synchronized HelloResult onHello(Connection connection, byte requestedId, String requestedName, String mac, InetAddress address) {
        if (mac == null) mac = "";
        if (phase != SessionPhase.LOBBY && !mac.isEmpty()) {
            PlayerRecord cached = cacheByMac.get(mac);
            if (cached != null) {
                Connection displaced = bindLocked(cached, connection);
                repo.addOrUpdatePlayer(cached.playerId, address, cached.name, mac);
                return new HelloResult(HelloResult.Kind.REJOINED, cached, displaced);
            }
        }
        PlayerRecord current = connection.player;
        if (phase == SessionPhase.RUNNING && current == null && !GameSettingsRepository.getInstance().isAllowJoinInProgress()) {
            return new HelloResult(HelloResult.Kind.REFUSED_IN_PROGRESS, null, null);
        }

        // Every known player's id is taken, connected or not: cached players keep theirs.
        Set<Byte> occupied = new HashSet<>();
        for (PlayerRecord record : byId.values()) {
            if (record != current) occupied.add(record.playerId);
        }
        byte id = requestedId;
        if (id != 0 && occupied.contains(id)) id = 0;
        if (id == 0) {
            id = Globals.findAutoSortPlayerID(occupied);
            if (id == 0) return new HelloResult(HelloResult.Kind.FULL, null, null);
        }
        int team = 1;
        if (Globals.getInstance().mGameMode != Globals.GAME_MODE_FFA) {
            team = Globals.getInstance().calcNetworkTeam(id);
        }

        PlayerRecord record = current;
        if (record == null) {
            record = new PlayerRecord(id, team, null, mac);
        } else if (record.playerId != id) {
            byId.remove(record.playerId);
            repo.reassignPlayer(record.playerId, id);
        }
        record.playerId = id;
        record.team = team;
        record.name = uniqueName(id, requestedName);
        byId.put(id, record);
        bindLocked(record, connection);
        repo.addOrUpdatePlayer(id, address, record.name, mac);
        if (phase == SessionPhase.RUNNING && !mac.isEmpty()) cacheByMac.put(mac, record);
        return new HelloResult(HelloResult.Kind.JOINED, record, null);
    }

    /** Points [connection] at [record]; returns the record's previous connection, if any. */
    private Connection bindLocked(PlayerRecord record, Connection connection) {
        PlayerRecord other = connection.player;
        if (other != null && other != record && other.connection == connection) other.connection = null;
        Connection previous = record.connection;
        if (previous == connection) previous = null;
        if (previous != null) {
            previous.player = null;
            connections.remove(previous.getConnectionId());
        }
        record.connection = connection;
        connection.player = record;
        return previous;
    }

    /**
     * [requested] unless it is empty, "Player", profane or (ignoring case) another player's
     * name; then a random unused name.
     */
    private String uniqueName(byte playerId, String requested) {
        Set<String> usedNames = new HashSet<>();
        for (Map.Entry<Byte, String> entry : repo.getTeamPlayerNameMap().entrySet()) {
            if (entry.getKey() != playerId) usedNames.add(entry.getValue());
        }
        boolean isDuplicate = false;
        for (String used : usedNames) {
            if (used != null && used.equalsIgnoreCase(requested)) {
                isDuplicate = true;
                break;
            }
        }
        if (requested == null || requested.isEmpty() || requested.equalsIgnoreCase("Player")
                || ProfanityFilter.containsProfanity(requested) || isDuplicate) {
            return NameGenerator.getRandomUniqueName(usedNames);
        }
        return requested;
    }

    /** The player behind [connection] asked for a new name; returns the name it got, or null. */
    public synchronized String onNameChange(Connection connection, String requested) {
        PlayerRecord record = connection.player;
        if (record == null) return null;
        record.name = uniqueName(record.playerId, requested);
        repo.getTeamPlayerNameMap().put(record.playerId, record.name);
        return record.name;
    }

    /** The connection was dropped or closed. */
    public enum Disconnect { NO_PLAYER, KEPT, REMOVED }

    /**
     * [connection] is gone: dropped, timed out, or the player left on purpose. A player in the
     * game's roster stays, disconnected, with its score until the reset (Q12). Anyone else is
     * removed.
     */
    public synchronized Disconnect onDisconnect(Connection connection) {
        connections.remove(connection.getConnectionId());
        PlayerRecord record = connection.player;
        connection.player = null;
        if (record == null) return Disconnect.NO_PLAYER;
        if (record.connection != connection) return Disconnect.NO_PLAYER; // already replaced by a newer connection
        record.connection = null;
        boolean cached = !record.blasterMac.isEmpty() && cacheByMac.get(record.blasterMac) == record;
        if (phase == SessionPhase.LOBBY || !cached) {
            removeRecordLocked(record);
            repo.removePlayer(record.playerId, phase == SessionPhase.RUNNING);
            return Disconnect.REMOVED;
        }
        return Disconnect.KEPT;
    }

    /**
     * The player behind [sender] was eliminated by [shooterId]. Counted during and after a game;
     * null (ignored) in the lobby.
     */
    public synchronized Elimination onEliminated(Connection sender, byte shooterId) {
        if (phase == SessionPhase.LOBBY) return null;
        PlayerRecord victim = sender.player;
        if (victim != null) victim.eliminated++;
        PlayerRecord shooter = byId.get(shooterId);
        int shooterPoints = 0;
        if (shooter != null) shooterPoints = ++shooter.points;
        return new Elimination(victim != null ? victim.playerId : 0, shooterId, shooterPoints);
    }

    /** Removes the player and closes nothing; returns its connection (send it SERVERCANCEL and close it). */
    public synchronized Connection kick(byte playerId) {
        PlayerRecord record = byId.get(playerId);
        Connection connection = null;
        if (record != null) {
            connection = record.connection;
            removeRecordLocked(record);
            if (connection != null) {
                connections.remove(connection.getConnectionId());
                connection.player = null;
            }
        }
        repo.removePlayer(playerId, false);
        return connection;
    }

    private void removeRecordLocked(PlayerRecord record) {
        if (byId.get(record.playerId) == record) byId.remove(record.playerId);
        if (!record.blasterMac.isEmpty() && cacheByMac.get(record.blasterMac) == record)
            cacheByMac.remove(record.blasterMac);
        record.connection = null;
    }

    // ---------------------------------------------------------------------------------------
    // Renumbering
    // ---------------------------------------------------------------------------------------

    /**
     * The server operator moved [oldId] to [newId]. Returns the player, or null if there is none
     * or it is not the lobby (ids are reserved by the game's roster).
     */
    public synchronized PlayerRecord movePlayer(byte oldId, byte newId) {
        if (phase != SessionPhase.LOBBY) return null;
        int team = Globals.getInstance().calcNetworkTeam(newId);
        PlayerRecord record = byId.get(oldId);
        if (record != null) {
            PlayerRecord other = byId.get(newId);
            if (other != null && other != record) return null;
            byId.remove(oldId);
            record.playerId = newId;
            record.team = team;
            byId.put(newId, record);
        }
        repo.reassignPlayer(oldId, newId);
        updateGpsTeam(newId, team);
        return record;
    }

    /**
     * The player behind [connection] asked to move to [targetTeam]. Returns the player with its
     * new id, or null outside the lobby or if there is no open slot on that team.
     */
    public synchronized PlayerRecord changeTeam(Connection connection, int targetTeam) {
        PlayerRecord record = connection.player;
        if (record == null || phase != SessionPhase.LOBBY) return null;
        Set<Byte> occupied = new HashSet<>(repo.getTeamIPMap().keySet());
        occupied.addAll(byId.keySet());
        occupied.remove(record.playerId);
        byte newId = repo.findOpenPlayerIDOnTeam(targetTeam, occupied);
        if (newId == 0) return null;
        byte oldId = record.playerId;
        byId.remove(oldId);
        record.playerId = newId;
        record.team = targetTeam;
        byId.put(newId, record);
        repo.reassignPlayer(oldId, newId);
        updateGpsTeam(newId, targetTeam);
        return record;
    }

    /**
     * Renumbers every player for the current game mode and team sizes, in id order or shuffled.
     * Players that do not fit are removed. Lobby only; elsewhere nothing changes.
     */
    public synchronized Rebalance rebalance(boolean randomize) {
        if (phase != SessionPhase.LOBBY)
            return new Rebalance(new ArrayList<PlayerRecord>(), new ArrayList<Connection>());
        Globals g = Globals.getInstance();
        int gameMode = g.mGameMode;
        int maxPlayers = Globals.MAX_PLAYER_ID;
        List<PlayerRecord> players = new ArrayList<>(byId.values());
        if (randomize) Collections.shuffle(players);

        List<PlayerRecord> assigned = new ArrayList<>();
        List<Connection> dropped = new ArrayList<>();
        Map<Byte, Byte> oldToNew = new LinkedHashMap<>();
        Map<Byte, Integer> newTeams = new HashMap<>();

        if (gameMode == Globals.GAME_MODE_FFA) {
            byte nextId = 1;
            for (PlayerRecord record : players) {
                byte newId = nextId <= maxPlayers ? nextId++ : 0;
                if (newId == 0) {
                    dropped.add(record.connection);
                    continue;
                }
                oldToNew.put(record.playerId, newId);
                newTeams.put(newId, (int) newId);
                assigned.add(record);
            }
        } else {
            Set<Byte> usedIds = new HashSet<>();
            int numTeams = (gameMode == Globals.GAME_MODE_4TEAMS) ? 4 : 2;
            int[] teamPointers = new int[numTeams + 1];
            int[] teamLimits = new int[numTeams + 1];
            int[] teamCounts = new int[numTeams + 1];
            for (int t = 1; t <= numTeams; t++) {
                teamPointers[t] = g.getTeamStartID(t);
                teamLimits[t] = g.getTeamEndID(t);
            }
            for (PlayerRecord record : players) {
                int targetTeam = findBestTeamForRebalance(numTeams, teamPointers, teamLimits, teamCounts);
                byte newId = 0;
                if (targetTeam != -1) {
                    newId = (byte) teamPointers[targetTeam];
                    teamPointers[targetTeam]++;
                    teamCounts[targetTeam]++;
                } else {
                    for (byte i = 1; i <= maxPlayers; i++) {
                        if (!usedIds.contains(i)) {
                            newId = i;
                            targetTeam = g.calcNetworkTeam(newId);
                            break;
                        }
                    }
                }
                if (newId == 0) {
                    dropped.add(record.connection);
                    continue;
                }
                usedIds.add(newId);
                oldToNew.put(record.playerId, newId);
                newTeams.put(newId, targetTeam);
                assigned.add(record);
            }
        }

        for (PlayerRecord record : players) {
            if (!assigned.contains(record)) removeRecordLocked(record);
        }
        for (Iterator<Connection> it = dropped.iterator(); it.hasNext(); ) {
            Connection connection = it.next();
            if (connection == null) {
                it.remove();
                continue;
            }
            connections.remove(connection.getConnectionId());
            connection.player = null;
        }
        byId.clear();
        for (PlayerRecord record : assigned) {
            byte newId = oldToNew.get(record.playerId);
            record.playerId = newId;
            record.team = newTeams.get(newId);
            byId.put(newId, record);
        }
        repo.remapPlayers(oldToNew);
        for (PlayerRecord record : assigned) updateGpsTeam(record.playerId, record.team);
        return new Rebalance(assigned, dropped);
    }

    private static int findBestTeamForRebalance(int numTeams, int[] teamPointers, int[] teamLimits, int[] teamCounts) {
        double minRatio = Double.MAX_VALUE;
        int bestTeam = -1;
        int[] sizes = Globals.getInstance().getTeamSizes();
        if (sizes == null || sizes.length < numTeams) {
            Globals.getInstance().resetDefaultTeamSizes();
            sizes = Globals.getInstance().getTeamSizes();
        }
        for (int t = 1; t <= numTeams; t++) {
            if (teamPointers[t] <= teamLimits[t]) {
                int capacity = sizes[t - 1];
                double ratio = (capacity > 0) ? (double) teamCounts[t] / capacity : 1.0;
                if (ratio < minRatio) {
                    minRatio = ratio;
                    bestTeam = t;
                }
            }
        }
        return bestTeam;
    }

    private void updateGpsTeam(byte playerId, int team) {
        Globals.GPSData gps = repo.getGpsData().get(playerId);
        if (gps != null) {
            gps.team = team;
            gps.hasUpdate = true;
        }
    }
}
