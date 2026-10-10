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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

public class PlayerRepositoryTest {

    private PlayerRepository playerRepo;
    private GameSettingsRepository settingsRepo;

    @Before
    public void setUp() {
        playerRepo = PlayerRepository.getInstance();
        settingsRepo = GameSettingsRepository.getInstance();

        playerRepo.setPlayerID((byte) 1);
        playerRepo.setPlayerName("TestPlayer");
        playerRepo.getTeamIPMap().clear();
        playerRepo.getTeamPlayerNameMap().clear();
    }

    @Test
    public void testPlayerDetails() {
        assertEquals((byte) 1, playerRepo.getPlayerID());
        assertEquals("TestPlayer", playerRepo.getPlayerName());
    }

    @Test
    public void testCalcNetworkTeam2Teams() {
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);

        assertEquals(1, playerRepo.calcNetworkTeam((byte) 1));
        assertEquals(1, playerRepo.calcNetworkTeam((byte) 16));
        assertEquals(2, playerRepo.calcNetworkTeam((byte) 17));
        assertEquals(2, playerRepo.calcNetworkTeam((byte) 32));
    }

    @Test
    public void testCalcNetworkTeam4Teams() {
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_4TEAMS);

        assertEquals(1, playerRepo.calcNetworkTeam((byte) 1));
        assertEquals(1, playerRepo.calcNetworkTeam((byte) 8));
        assertEquals(2, playerRepo.calcNetworkTeam((byte) 9));
        assertEquals(2, playerRepo.calcNetworkTeam((byte) 16));
        assertEquals(3, playerRepo.calcNetworkTeam((byte) 17));
        assertEquals(3, playerRepo.calcNetworkTeam((byte) 24));
        assertEquals(4, playerRepo.calcNetworkTeam((byte) 25));
        assertEquals(4, playerRepo.calcNetworkTeam((byte) 32));
    }

    @Test
    public void testCalcNetworkTeamFFA() {
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_FFA);

        assertEquals(5, playerRepo.calcNetworkTeam((byte) 5));
        assertEquals(12, playerRepo.calcNetworkTeam((byte) 12));
    }

    @Test
    public void testGrenadePairings() {
        playerRepo.clearGrenadePairings();
        int[] pairings = playerRepo.getGrenadePairings();
        assertNotNull(pairings);
        assertEquals(PlayerRepository.MAX_GRENADE_IDS, pairings.length);
        assertEquals(PlayerRepository.INVALID_PLAYER_ID, pairings[0]);

        playerRepo.setGrenadePairing(0, 5);
        pairings = playerRepo.getGrenadePairings();
        assertEquals(5, pairings[0]);
    }

    @Test
    public void testAutoSortPlayerIDDedicatedServer() throws Exception {
        // On a dedicated server, host playerID is 0
        playerRepo.setPlayerID((byte) 0);
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);

        // Player 1 joins -> should receive Team 1 ID (e.g. ID 1)
        byte player1ID = playerRepo.findAutoSortPlayerID();
        assertEquals((byte) 1, player1ID);
        playerRepo.getTeamIPMap().put(player1ID, InetAddress.getByName("192.168.1.101"));

        // Player 2 joins -> should receive Team 2 ID (e.g. ID 17) because team 1 has 1 player and team 2 has 0
        byte player2ID = playerRepo.findAutoSortPlayerID();
        assertEquals((byte) 17, player2ID);
        playerRepo.getTeamIPMap().put(player2ID, InetAddress.getByName("192.168.1.102"));

        // Verify Player 1 and Player 2 have distinct player IDs and teams
        assert(player1ID != player2ID);
        assertEquals(1, playerRepo.calcNetworkTeam(player1ID));
        assertEquals(2, playerRepo.calcNetworkTeam(player2ID));
    }

    @Test
    public void testAutoSortPlayerIDWithAdditionalOccupiedIDs() {
        playerRepo.setPlayerID((byte) 0);
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);

        Set<Byte> additional = new HashSet<>();
        additional.add((byte) 1);
        additional.add((byte) 17);

        byte newID = playerRepo.findAutoSortPlayerID(additional);
        Assert.assertNotEquals((byte) 1, newID);
        Assert.assertNotEquals((byte) 17, newID);
        Assert.assertTrue(newID > 0 && newID <= GameSettingsRepository.MAX_PLAYER_ID);
    }

    @Test
    public void testNoDuplicatePlayerIDsInSequence() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        settingsRepo.setGameMode(GameSettingsRepository.GAME_MODE_2TEAMS);

        Set<Byte> assignedIDs = new HashSet<>();
        for (int i = 0; i < GameSettingsRepository.MAX_PLAYER_ID; i++) {
            byte id = playerRepo.findAutoSortPlayerID();
            Assert.assertFalse("ID " + id + " was assigned twice!", assignedIDs.contains(id));
            Assert.assertTrue("ID " + id + " is invalid!", id > 0 && id <= GameSettingsRepository.MAX_PLAYER_ID);
            assignedIDs.add(id);
            playerRepo.getTeamIPMap().put(id, InetAddress.getByName("192.168.1." + (100 + i)));
        }
        assertEquals(GameSettingsRepository.MAX_PLAYER_ID, assignedIDs.size());
    }

    @Test
    public void testConcurrentPlayerRegistrationAndGrenadePairings() throws InterruptedException {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();
        playerRepo.clearGrenadePairings();

        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    byte assignedID = playerRepo.findAutoSortPlayerID();
                    if (assignedID > 0) {
                        playerRepo.addOrUpdatePlayer(assignedID, InetAddress.getByName("192.168.1." + (100 + index)), "Player" + index);
                    }
                    playerRepo.setGrenadePairing(index % PlayerRepository.MAX_GRENADE_IDS, index + 1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        Assert.assertTrue(playerRepo.getPlayerCount(true) > 1);
    }

    @Test
    public void testPlayerReconnectionUpdateIPAndName() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();

        byte playerId = 2;
        InetAddress initialIp = InetAddress.getByName("192.168.1.100");
        playerRepo.addOrUpdatePlayer(playerId, initialIp, "OldName");

        assertEquals(1, playerRepo.getPlayerCount(true));
        assertEquals("OldName", playerRepo.getPlayerName(playerId));
        assertEquals(initialIp, playerRepo.getTeamIPMap().get(playerId));

        // Reconnect with new IP and new name
        InetAddress newIp = InetAddress.getByName("192.168.1.150");
        playerRepo.addOrUpdatePlayer(playerId, newIp, "NewName");

        // Player count should still be 1 (not incremented)
        assertEquals(1, playerRepo.getPlayerCount(true));
        assertEquals("NewName", playerRepo.getPlayerName(playerId));
        assertEquals(newIp, playerRepo.getTeamIPMap().get(playerId));
        assertEquals((Byte) playerId, playerRepo.getIpTeamMap().get(newIp));
    }

    @Test
    public void testPlayerMacAddressMappingAndLookup() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();

        byte playerId = 5;
        String testMac = "AA:BB:CC:DD:EE:FF";
        InetAddress ip = InetAddress.getByName("192.168.1.105");

        playerRepo.addOrUpdatePlayer(playerId, ip, "MacPlayer", testMac);

        assertEquals((Byte) playerId, playerRepo.getPlayerIdByMac(testMac));
        assertEquals(testMac, playerRepo.getPlayerMacMap().get(playerId));

        // Reconnect with new IP and new name but same MAC
        InetAddress newIp = InetAddress.getByName("192.168.1.120");
        playerRepo.addOrUpdatePlayer(playerId, newIp, "MacPlayerUpdated", testMac);

        assertEquals((Byte) playerId, playerRepo.getPlayerIdByMac(testMac));
        assertEquals("MacPlayerUpdated", playerRepo.getPlayerName(playerId));
        assertEquals(newIp, playerRepo.getTeamIPMap().get(playerId));
    }

    @Test
    public void testLobbyPhasePlayerRemovalCleansMacAndName() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();

        byte playerId = 7;
        String testMac = "11:22:33:44:55:66";
        InetAddress ip = InetAddress.getByName("192.168.1.107");

        playerRepo.addOrUpdatePlayer(playerId, ip, "LobbyPlayer", testMac);
        assertEquals((Byte) playerId, playerRepo.getPlayerIdByMac(testMac));

        // Simulate player disconnecting in lobby mode (isGameRunning = false)
        playerRepo.removePlayer(playerId, false);

        // In lobby mode, MAC mapping and player name should be purged
        Assert.assertNull(playerRepo.getPlayerIdByMac(testMac));
        assertEquals("", playerRepo.getPlayerName(playerId));
    }

    @Test
    public void testPlayerReassignmentPreservesName() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();

        byte oldId = 1;
        byte newId = 17;
        InetAddress ip = InetAddress.getByName("192.168.1.101");
        String testName = "Alice";
        String testMac = "00:11:22:33:44:55";

        playerRepo.addOrUpdatePlayer(oldId, ip, testName, testMac);
        assertEquals(testName, playerRepo.getPlayerName(oldId));

        playerRepo.reassignPlayer(oldId, newId);

        assertEquals("", playerRepo.getPlayerName(oldId));
        assertEquals(testName, playerRepo.getPlayerName(newId));
        assertEquals((Byte) newId, playerRepo.getPlayerIdByMac(testMac));
    }

    @Test
    public void testIsNameUniqueExcludesCurrentPlayer() throws Exception {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();

        byte p1 = 1;
        byte p2 = 2;
        playerRepo.addOrUpdatePlayer(p1, InetAddress.getByName("192.168.1.101"), "Alice");
        playerRepo.addOrUpdatePlayer(p2, InetAddress.getByName("192.168.1.102"), "Bob");

        // Alice checking her own name "Alice" (excluding p1) -> should be UNIQUE
        Assert.assertTrue(playerRepo.isNameUnique("Alice", p1));

        // Bob checking "Alice" (excluding p2) -> should NOT be unique
        Assert.assertFalse(playerRepo.isNameUnique("Alice", p2));

        // New player checking new name "Charlie" -> should be UNIQUE
        Assert.assertTrue(playerRepo.isNameUnique("Charlie", (byte) 0));
    }

    @Test
    public void testActiveGameMacSnapshotAndReconnection() {
        playerRepo.setPlayerID((byte) 0);
        playerRepo.clearAllPlayers();
        playerRepo.clearActiveGameMacMap();

        String mac = "DE:AD:BE:EF:00:01";
        playerRepo.addActiveGamePlayer(mac, (byte) 5, 2, "GamePlayer5");

        PlayerRepository.ActiveGamePlayerInfo info = playerRepo.getActiveGamePlayerByMac(mac);
        assertNotNull(info);
        assertEquals((byte) 5, info.playerId);
        assertEquals(2, info.team);
        assertEquals("GamePlayer5", info.originalName);

        playerRepo.clearActiveGameMacMap();
        Assert.assertNull(playerRepo.getActiveGamePlayerByMac(mac));
    }
}


