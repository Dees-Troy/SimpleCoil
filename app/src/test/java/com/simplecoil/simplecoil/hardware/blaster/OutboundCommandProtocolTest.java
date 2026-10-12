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

package com.simplecoil.simplecoil.hardware.blaster;

import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

import org.junit.Test;

import static com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.PACKET_LENGTH;
import static com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.hex;
import static com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.toHex;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

/**
 * Byte-exact golden tests for every packet the app writes to a Recoil blaster.
 *
 * Command characteristic (e6f59d13): set team, start reload, finish reload.
 * Config characteristic  (e6f59d14): shot mode / firing cone, recoil on/off.
 *
 * Every expected value is the full 20-byte packet so that a stray byte anywhere (including the
 * zero padding) is caught. The golden values were cross-checked against the hand-written packet
 * code on branch "master" and the packet notes in its comments.
 */
public class OutboundCommandProtocolTest {

    private static void assertPacket(String expectedHex, byte[] actual) {
        assertEquals("packet length", PACKET_LENGTH, actual.length);
        assertEquals(expectedHex, toHex(actual));
    }

    // ----------------------------------------------------------------------------------------
    // Protocol constants
    // ----------------------------------------------------------------------------------------

    @Test
    public void blasterTypeCodes_matchIdCharacteristicByte10() {
        // Byte 10 of the ID characteristic read: 0x01 rifle, 0x02 pistol.
        assertEquals((byte) 0x01, GattProtocolHandler.BLASTER_TYPE_RIFLE);
        assertEquals((byte) 0x02, GattProtocolHandler.BLASTER_TYPE_PISTOL);
    }

    @Test
    public void commandIdIncrement_isHighNibbleStep() {
        // Command byte 0 is a rolling sequence number in the high nibble: 00, 10, 20, ... F0, 00.
        assertEquals((byte) 0x10, GattProtocolHandler.COMMAND_ID_INCREMENT);
    }

    // ----------------------------------------------------------------------------------------
    // SET TEAM / PLAYER ID  -> Command characteristic
    // Format: CID 00 80 00 PID + 15 x 00
    // ----------------------------------------------------------------------------------------

    @Test
    public void setTeam_goldenPacket() {
        // Documented on master: "10 00 80 00 PLAYER# then 15 more 00's sets the player ID".
        assertPacket("10 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildSetTeamCommand((byte) 0x10, (byte) 5));
    }

    @Test
    public void setTeam_minAndMaxPlayerIds() {
        assertPacket("00 00 80 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildSetTeamCommand((byte) 0x00, (byte) 1));
        // GameSettingsRepository.MAX_PLAYER_ID == 0x20 (32)
        assertPacket("F0 00 80 00 20 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildSetTeamCommand((byte) 0xF0, GameSettingsRepository.MAX_PLAYER_ID));
    }

    @Test
    public void setTeam_passesRawBytesThroughWithoutValidation() {
        // The builder does no range checking; guards live in the callers.
        assertPacket("FF 00 80 00 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildSetTeamCommand((byte) 0xFF, (byte) 0xFF));
        assertPacket("00 00 80 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildSetTeamCommand((byte) 0x00, (byte) 0x00));
    }

    // ----------------------------------------------------------------------------------------
    // START RELOAD (stage 1, blaster stops firing)  -> Command characteristic
    // Format: CID 00 02 00 PID + 15 x 00   (byte 5 / weapon profile intentionally NOT set)
    // ----------------------------------------------------------------------------------------

    @Test
    public void startReload_goldenPacket() {
        // Documented on master: "F0 00 02 00 PLAYER_ID and then 0 filled to the end".
        assertPacket("F0 00 02 00 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildStartReloadCommand((byte) 0xF0, (byte) 7));
    }

    @Test
    public void startReload_boundaries() {
        assertPacket("00 00 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildStartReloadCommand((byte) 0x00, (byte) 0));
        assertPacket("80 00 02 00 20 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildStartReloadCommand((byte) 0x80, (byte) 0x20));
    }

    // ----------------------------------------------------------------------------------------
    // FINISH RELOAD (stage 2, load N shots)  -> Command characteristic
    // Format: CID 00 04 00 PID PROFILE SHOTS + 13 x 00
    // ----------------------------------------------------------------------------------------

    @Test
    public void finishReload_goldenPacket() {
        // Documented on master: "00 00 04 00 PLAYER_ID 00 SHOT_COUNT and then 0 filled".
        assertPacket("00 00 04 00 07 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildFinishReloadCommand((byte) 0x00, (byte) 7, (byte) 0x00, (byte) 30));
    }

    @Test
    public void finishReload_boundaries() {
        // Zero shots (eliminated / empty reload)
        assertPacket("20 00 04 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildFinishReloadCommand((byte) 0x20, (byte) 1, (byte) 0, (byte) 0));
        // Max unsigned shot count and non-zero weapon profile go to bytes 6 and 5
        assertPacket("30 00 04 00 20 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildFinishReloadCommand((byte) 0x30, (byte) 0x20, (byte) 0x03, (byte) 0xFF));
        // Signed byte 127/128 boundary for shot count
        assertPacket("40 00 04 00 02 00 7F 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildFinishReloadCommand((byte) 0x40, (byte) 2, (byte) 0, (byte) 127));
        assertPacket("40 00 04 00 02 00 80 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildFinishReloadCommand((byte) 0x40, (byte) 2, (byte) 0, (byte) 128));
    }

    @Test
    public void commandPackets_allSixteenSequenceIdsAreWrittenToByte0() {
        for (int i = 0; i < 16; i++) {
            byte cid = (byte) (i << 4);
            assertEquals(cid, GattProtocolHandler.buildSetTeamCommand(cid, (byte) 1)[0]);
            assertEquals(cid, GattProtocolHandler.buildStartReloadCommand(cid, (byte) 1)[0]);
            assertEquals(cid, GattProtocolHandler.buildFinishReloadCommand(cid, (byte) 1, (byte) 0, (byte) 1)[0]);
        }
    }

    @Test
    public void commandPackets_opcodeByte2IsDistinctPerCommand() {
        // Byte 2 is the opcode: 0x80 set team, 0x02 start reload, 0x04 finish reload.
        assertEquals((byte) 0x80, GattProtocolHandler.buildSetTeamCommand((byte) 0, (byte) 1)[2]);
        assertEquals((byte) 0x02, GattProtocolHandler.buildStartReloadCommand((byte) 0, (byte) 1)[2]);
        assertEquals((byte) 0x04, GattProtocolHandler.buildFinishReloadCommand((byte) 0, (byte) 1, (byte) 0, (byte) 1)[2]);
    }

    // ----------------------------------------------------------------------------------------
    // SHOT MODE / FIRING CONE  -> Config characteristic
    // Format: PROFILE 00 09 SHOTS ROF CONE_A CONE_B FF FF RECOIL 02 34 + 8 x 00
    //   SHOTS ROF: FE 00 single, 03 03 burst-3, FE 01 full auto
    //   CONE_A CONE_B: FF 00 outdoor/no cone, FF C8 outdoor/cone, 19 00 indoor/no cone
    //   RECOIL: 0x80, except 0x78 for burst on a rifle
    // ----------------------------------------------------------------------------------------

    private static final String[][] SHOT_MODE_MATRIX = {
            {"PISTOL", "SINGLE", "OUTDOOR_NO_CONE", "00 00 09 FE 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "SINGLE", "OUTDOOR_WITH_CONE", "00 00 09 FE 00 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "SINGLE", "INDOOR_NO_CONE", "00 00 09 FE 00 19 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "BURST", "OUTDOOR_NO_CONE", "00 00 09 03 03 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "BURST", "OUTDOOR_WITH_CONE", "00 00 09 03 03 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "BURST", "INDOOR_NO_CONE", "00 00 09 03 03 19 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "FULL_AUTO", "OUTDOOR_NO_CONE", "00 00 09 FE 01 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "FULL_AUTO", "OUTDOOR_WITH_CONE", "00 00 09 FE 01 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"PISTOL", "FULL_AUTO", "INDOOR_NO_CONE", "00 00 09 FE 01 19 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "SINGLE", "OUTDOOR_NO_CONE", "00 00 09 FE 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "SINGLE", "OUTDOOR_WITH_CONE", "00 00 09 FE 00 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "SINGLE", "INDOOR_NO_CONE", "00 00 09 FE 00 19 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "BURST", "OUTDOOR_NO_CONE", "00 00 09 03 03 FF 00 FF FF 78 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "BURST", "OUTDOOR_WITH_CONE", "00 00 09 03 03 FF C8 FF FF 78 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "BURST", "INDOOR_NO_CONE", "00 00 09 03 03 19 00 FF FF 78 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "FULL_AUTO", "OUTDOOR_NO_CONE", "00 00 09 FE 01 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "FULL_AUTO", "OUTDOOR_WITH_CONE", "00 00 09 FE 01 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
            {"RIFLE", "FULL_AUTO", "INDOOR_NO_CONE", "00 00 09 FE 01 19 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"},
    };

    private static byte blasterType(String name) {
        return "RIFLE".equals(name) ? GattProtocolHandler.BLASTER_TYPE_RIFLE : GattProtocolHandler.BLASTER_TYPE_PISTOL;
    }

    private static int shotMode(String name) {
        switch (name) {
            case "SINGLE": return GameSettingsRepository.SHOT_MODE_SINGLE;
            case "BURST": return GameSettingsRepository.SHOT_MODE_BURST;
            case "FULL_AUTO": return GameSettingsRepository.SHOT_MODE_FULL_AUTO;
            default: throw new IllegalArgumentException(name);
        }
    }

    private static int firingMode(String name) {
        switch (name) {
            case "OUTDOOR_NO_CONE": return GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE;
            case "OUTDOOR_WITH_CONE": return GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE;
            case "INDOOR_NO_CONE": return GameSettingsRepository.FIRING_MODE_INDOOR_NO_CONE;
            default: throw new IllegalArgumentException(name);
        }
    }

    @Test
    public void shotModeConfig_fullMatrixGoldenPackets() {
        for (String[] row : SHOT_MODE_MATRIX) {
            byte[] actual = GattProtocolHandler.buildShotModeConfig(
                    (byte) 0x00, shotMode(row[1]), blasterType(row[0]), firingMode(row[2]));
            assertEquals(row[0] + "/" + row[1] + "/" + row[2], row[3], toHex(actual));
            assertEquals(PACKET_LENGTH, actual.length);
        }
    }

    @Test
    public void shotModeConfig_weaponProfileGoesToByte0() {
        assertPacket("05 00 09 FE 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0x05,
                        GameSettingsRepository.SHOT_MODE_SINGLE, GattProtocolHandler.BLASTER_TYPE_PISTOL,
                        GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE));
    }

    @Test
    public void shotModeConfig_unknownShotModeLeavesShotBytesZero() {
        // Characterization: an unrecognised shot mode is not rejected, bytes 3-4 are left 00 00.
        assertPacket("00 00 09 00 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, 0, GattProtocolHandler.BLASTER_TYPE_PISTOL,
                        GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE));
        assertPacket("00 00 09 00 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, 3, GattProtocolHandler.BLASTER_TYPE_RIFLE,
                        GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE));
    }

    @Test
    public void shotModeConfig_unknownFiringModeLeavesConeBytesZero() {
        // Characterization: an unrecognised firing mode leaves bytes 5-6 at 00 00.
        assertPacket("00 00 09 FE 01 00 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_FULL_AUTO,
                        GattProtocolHandler.BLASTER_TYPE_PISTOL, 99));
        assertPacket("00 00 09 FE 01 00 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_FULL_AUTO,
                        GattProtocolHandler.BLASTER_TYPE_PISTOL, -1));
    }

    @Test
    public void shotModeConfig_unknownBlasterTypeIsTreatedLikePistol() {
        // Only BLASTER_TYPE_RIFLE reduces burst recoil; any other type byte keeps 0x80.
        assertPacket("00 00 09 03 03 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_BURST,
                        (byte) 0x00, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE));
        assertPacket("00 00 09 03 03 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_BURST,
                        (byte) 0x03, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE));
    }

    // ----------------------------------------------------------------------------------------
    // RECOIL ON/OFF  -> Config characteristic
    // Format: 10 00 02 MODE FF + 15 x 00   (MODE 03 = enabled, 02 = disabled)
    // ----------------------------------------------------------------------------------------

    @Test
    public void recoilConfig_enabledGoldenPacket() {
        // Documented on master: "Config 10 00 02 03 ff and 15 sets of 00 enables recoil".
        assertPacket("10 00 02 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildRecoilConfig(true));
    }

    @Test
    public void recoilConfig_disabledGoldenPacket() {
        // Documented on master: "Config 10 00 02 02 ff and 15 sets of 00 disables recoil".
        assertPacket("10 00 02 02 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                GattProtocolHandler.buildRecoilConfig(false));
    }

    // ----------------------------------------------------------------------------------------
    // Buffer hygiene
    // ----------------------------------------------------------------------------------------

    @Test
    public void builders_returnFreshBuffersEachCall() {
        // A refactor that caches/reuses a packet buffer could corrupt queued writes.
        byte[] a = GattProtocolHandler.buildRecoilConfig(true);
        byte[] b = GattProtocolHandler.buildRecoilConfig(true);
        assertNotSame(a, b);
        a[3] = 0x55;
        assertEquals((byte) 0x03, GattProtocolHandler.buildRecoilConfig(true)[3]);

        byte[] c = GattProtocolHandler.buildSetTeamCommand((byte) 0, (byte) 1);
        c[2] = 0x00;
        assertEquals((byte) 0x80, GattProtocolHandler.buildSetTeamCommand((byte) 0, (byte) 1)[2]);

        byte[] d = GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_SINGLE,
                GattProtocolHandler.BLASTER_TYPE_PISTOL, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE);
        d[11] = 0x00;
        assertEquals((byte) 0x34, GattProtocolHandler.buildShotModeConfig((byte) 0, GameSettingsRepository.SHOT_MODE_SINGLE,
                GattProtocolHandler.BLASTER_TYPE_PISTOL, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)[11]);
    }

    @Test
    public void hexFixture_roundTrips() {
        // Sanity check for the fixture used by every golden assertion above.
        assertEquals("10 00 80 FF", toHex(hex("10 00 80 ff")));
    }
}
