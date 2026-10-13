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

package com.simplecoil.simplecoil.hardware;

import com.simplecoil.simplecoil.data.model.GpsData;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;
import com.simplecoil.simplecoil.domain.team.TeamLayout;
import com.simplecoil.simplecoil.domain.team.TeamMode;
import com.simplecoil.simplecoil.hardware.location.MapLocationManager;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MapLocationManagerTest {

    private MapLocationManager locationManager;

    @Before
    public void setUp() {
        locationManager = MapLocationManager.getInstance();
    }

    @Test
    public void testIsBetterLocationNulls() {
        assertFalse(locationManager.isBetterLocation(null, null));
    }

    @Test
    public void testIsBetterLocationSignificantlyNewer() {
        long now = System.currentTimeMillis();
        long current = now - MapLocationManager.TWO_MINUTES - 1000;

        assertTrue(locationManager.isBetterLocation(now, 20.0f, "gps", current, 10.0f, "gps"));
    }

    @Test
    public void testIsBetterLocationSignificantlyOlder() {
        long now = System.currentTimeMillis();
        long older = now - MapLocationManager.TWO_MINUTES - 1000;

        assertFalse(locationManager.isBetterLocation(older, 5.0f, "gps", now, 10.0f, "gps"));
    }

    @Test
    public void testIsBetterLocationAccuracy() {
        long now = System.currentTimeMillis();

        assertTrue(locationManager.isBetterLocation(now + 100, 5.0f, "gps", now, 20.0f, "gps"));
    }

    private static GpsData playerOnTeam(int team) {
        GpsData data = new GpsData();
        data.team = team;
        return data;
    }

    @Test
    public void testTeammatesOnlyHidesEnemies() {
        TeamLayout layout = TeamLayout.defaultFor(TeamMode.TWO_TEAMS);
        byte me = 3; // team 1
        assertTrue(MapLocationManager.isVisible(layout, GameSettingsRepository.GPS_TEAMMATE, me, playerOnTeam(1)));
        assertFalse(MapLocationManager.isVisible(layout, GameSettingsRepository.GPS_TEAMMATE, me, playerOnTeam(2)));
        assertTrue(MapLocationManager.isVisible(layout, GameSettingsRepository.GPS_ALL, me, playerOnTeam(2)));
    }

    @Test
    public void testFreeForAllHasNoTeammates() {
        TeamLayout ffa = TeamLayout.defaultFor(TeamMode.FREE_FOR_ALL);
        byte me = 3;
        assertFalse(MapLocationManager.isVisible(ffa, GameSettingsRepository.GPS_TEAMMATE, me, playerOnTeam(3)));
        assertTrue(MapLocationManager.isVisible(ffa, GameSettingsRepository.GPS_ALL, me, playerOnTeam(3)));
    }

    @Test
    public void testFourTeamsUsesTheFourTeamLayout() {
        TeamLayout four = TeamLayout.defaultFor(TeamMode.FOUR_TEAMS);
        byte me = 9; // team 2 with four teams, team 1 with two
        assertTrue(MapLocationManager.isVisible(four, GameSettingsRepository.GPS_TEAMMATE, me, playerOnTeam(2)));
        assertFalse(MapLocationManager.isVisible(four, GameSettingsRepository.GPS_TEAMMATE, me, playerOnTeam(1)));
    }
}
