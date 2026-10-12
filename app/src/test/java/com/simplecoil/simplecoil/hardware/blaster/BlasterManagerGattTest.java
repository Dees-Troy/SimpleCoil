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

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Intent;

import com.simplecoil.simplecoil.BluetoothLeService;
import com.simplecoil.simplecoil.GattAttributes;
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises BlasterManager against mocked GATT objects to lock down which characteristic each
 * packet is written to, the write type, the rolling command sequence id, service discovery, and
 * how telemetry notifications are turned into blaster events.
 *
 * Runs on the plain JVM: BluetoothLeService / BluetoothGattService / BluetoothGattCharacteristic
 * are Mockito mocks and incoming notifications are injected as mocked Intents.
 */
public class BlasterManagerGattTest {

    /** BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT (write with response). */
    private static final int WRITE_TYPE_DEFAULT = 2;

    private BlasterManager manager;
    private BluetoothLeService service;
    private BluetoothGattCharacteristic telemetryChar;
    private BluetoothGattCharacteristic commandChar;
    private BluetoothGattCharacteristic configChar;
    private BluetoothGattCharacteristic idChar;
    private RecordingListener listener;

    @Before
    public void setUp() throws Exception {
        // BlasterManager is a singleton holding protocol state (command id, counters); start clean.
        Field instance = BlasterManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        manager = BlasterManager.getInstance();
        listener = new RecordingListener();
        manager.setListener(listener);

        service = mock(BluetoothLeService.class);
        telemetryChar = mock(BluetoothGattCharacteristic.class);
        commandChar = mock(BluetoothGattCharacteristic.class);
        configChar = mock(BluetoothGattCharacteristic.class);
        idChar = mock(BluetoothGattCharacteristic.class);
    }

    private BluetoothGattService recoilService(String serviceUuid) {
        BluetoothGattService gattService = mock(BluetoothGattService.class);
        when(gattService.getUuid()).thenReturn(UUID.fromString(serviceUuid));
        when(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID))).thenReturn(telemetryChar);
        when(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID))).thenReturn(commandChar);
        when(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID))).thenReturn(configChar);
        when(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_ID_UUID))).thenReturn(idChar);
        return gattService;
    }

    /** Wires the mocked service with a discovered Recoil GATT service. */
    private void connect() {
        BluetoothGattService gattService = recoilService(GattAttributes.RECOIL_MAIN_SERVICE);
        when(service.getSupportedGattServices()).thenReturn(Collections.singletonList(gattService));
        manager.setBluetoothLeService(service);
    }

    private byte[] captureSingleWrite(BluetoothGattCharacteristic expectedChar) {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(service).writeCharacteristic(eq(expectedChar), captor.capture(), eq(WRITE_TYPE_DEFAULT));
        return captor.getValue();
    }

    private List<byte[]> captureWrites(BluetoothGattCharacteristic expectedChar, int count) {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(service, times(count)).writeCharacteristic(eq(expectedChar), captor.capture(), eq(WRITE_TYPE_DEFAULT));
        return captor.getAllValues();
    }

    private void deliver(Intent intent) throws Exception {
        Method m = BlasterManager.class.getDeclaredMethod("handleGattIntent", Intent.class);
        m.setAccessible(true);
        m.invoke(manager, intent);
    }

    private void deliverTelemetry(byte[] data) throws Exception {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
        when(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA)).thenReturn(data);
        deliver(intent);
    }

    private void deliverBlasterType(byte type) throws Exception {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(BluetoothLeService.ID_DATA_AVAILABLE);
        when(intent.getByteExtra(eq(BluetoothLeService.EXTRA_DATA), anyByte())).thenReturn(type);
        deliver(intent);
    }

    // ----------------------------------------------------------------------------------------
    // Service discovery
    // ----------------------------------------------------------------------------------------

    @Test
    public void discovery_enablesTelemetryNotificationsAndReadsIdCharacteristic() {
        connect();
        verify(service).setCharacteristicNotification(telemetryChar, true);
        verify(service).readCharacteristic(idChar);
        // Discovery itself must not write to command/config (recoil write is deferred via Handler).
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());
    }

    @Test
    public void discovery_matchesServiceUuidCaseInsensitively() {
        BluetoothGattService gattService = recoilService(GattAttributes.RECOIL_MAIN_SERVICE.toUpperCase());
        when(service.getSupportedGattServices()).thenReturn(Collections.singletonList(gattService));
        manager.setBluetoothLeService(service);
        verify(service).setCharacteristicNotification(telemetryChar, true);
    }

    @Test
    public void discovery_ignoresOtherServices() {
        // e.g. the standard Battery Service (0x180F) must not be mistaken for the Recoil service
        BluetoothGattService gattService = recoilService("0000180f-0000-1000-8000-00805f9b34fb");
        when(service.getSupportedGattServices()).thenReturn(Collections.singletonList(gattService));
        manager.setBluetoothLeService(service);
        verify(service, never()).setCharacteristicNotification(any(BluetoothGattCharacteristic.class), anyBoolean());
        verify(service, never()).readCharacteristic(any(BluetoothGattCharacteristic.class));

        manager.setTeam((byte) 1);
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());
    }

    @Test
    public void discovery_withNoServicesDoesNothing() {
        when(service.getSupportedGattServices()).thenReturn(null);
        manager.setBluetoothLeService(service);
        manager.setTeam((byte) 1);
        manager.setRecoil(false);
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());
    }

    // ----------------------------------------------------------------------------------------
    // Outbound routing: characteristic + write type + exact bytes
    // ----------------------------------------------------------------------------------------

    @Test
    public void setTeam_writesToCommandCharacteristic() {
        connect();
        manager.setTeam((byte) 5);
        assertEquals("00 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(commandChar)));
    }

    @Test
    public void startReload_writesToCommandCharacteristic() {
        connect();
        manager.startReload((byte) 7);
        assertEquals("00 00 02 00 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(commandChar)));
    }

    @Test
    public void finishReload_writesToCommandCharacteristicWithProfile0() {
        connect();
        manager.finishReload((byte) 7, (byte) 30);
        assertEquals("00 00 04 00 07 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(commandChar)));
    }

    @Test
    public void setShotMode_writesToConfigCharacteristic() {
        connect();
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_FULL_AUTO, GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE);
        assertEquals("00 00 09 FE 01 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(configChar)));
    }

    @Test
    public void setRecoil_writesToConfigCharacteristic() {
        connect();
        manager.setRecoil(false);
        manager.setRecoil(true);
        List<byte[]> writes = captureWrites(configChar, 2);
        assertEquals("10 00 02 02 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(writes.get(0)));
        assertEquals("10 00 02 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(writes.get(1)));
    }

    @Test
    public void commandSequenceId_incrementsBy0x10AndWrapsAfter0xF0() {
        connect();
        // 17 commands: ids 00 10 20 ... F0 then wraps to 00
        for (int i = 0; i < 17; i++) {
            switch (i % 3) {
                case 0: manager.setTeam((byte) 1); break;
                case 1: manager.startReload((byte) 1); break;
                default: manager.finishReload((byte) 1, (byte) 10); break;
            }
        }
        List<byte[]> writes = captureWrites(commandChar, 17);
        for (int i = 0; i < 17; i++) {
            assertEquals("command #" + i, (byte) ((i % 16) << 4), writes.get(i)[0]);
        }
    }

    @Test
    public void configWrites_doNotConsumeCommandSequenceIds() {
        connect();
        manager.setTeam((byte) 1);
        manager.setRecoil(true);
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_SINGLE, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE);
        manager.startReload((byte) 1);
        List<byte[]> commands = captureWrites(commandChar, 2);
        assertEquals((byte) 0x00, commands.get(0)[0]);
        assertEquals((byte) 0x10, commands.get(1)[0]);
    }

    @Test
    public void setTeam_rejectsPlayerIdBelow1WithoutConsumingSequenceId() {
        connect();
        manager.setTeam((byte) 0);
        // Java bytes are signed: ids >= 0x80 are negative and therefore also rejected.
        manager.setTeam((byte) 0x80);
        manager.setTeam((byte) 0xFF);
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());

        manager.setTeam((byte) 0x7F);
        assertEquals("00 00 80 00 7F 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(commandChar)));
    }

    @Test
    public void commands_areDroppedWhenNotConnected() {
        manager.setTeam((byte) 1);
        manager.startReload((byte) 1);
        manager.finishReload((byte) 1, (byte) 30);
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_SINGLE, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE);
        manager.setRecoil(false);
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());
        // Recoil preference is remembered even when the write could not be sent.
        assertFalse(manager.isRecoilEnabled());

        // Dropped commands must not have advanced the sequence id.
        connect();
        manager.setTeam((byte) 1);
        assertEquals((byte) 0x00, captureSingleWrite(commandChar)[0]);
    }

    // ----------------------------------------------------------------------------------------
    // ID characteristic -> blaster type
    // ----------------------------------------------------------------------------------------

    @Test
    public void blasterType_defaultsToPistol() {
        assertEquals(GattProtocolHandler.BLASTER_TYPE_PISTOL, manager.getBlasterType());
    }

    @Test
    public void blasterType_rifleReportedByIdCharacteristicChangesBurstRecoil() throws Exception {
        connect();
        deliverBlasterType(GattProtocolHandler.BLASTER_TYPE_RIFLE);
        assertEquals(GattProtocolHandler.BLASTER_TYPE_RIFLE, manager.getBlasterType());
        assertEquals(Collections.singletonList("type:1"), listener.events);

        manager.setShotMode(GameSettingsRepository.SHOT_MODE_BURST, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE);
        assertEquals("00 00 09 03 03 FF 00 FF FF 78 02 34 00 00 00 00 00 00 00 00",
                toHex(captureSingleWrite(configChar)));
    }

    @Test
    public void characteristicWriteFinished_isForwarded() throws Exception {
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED);
        deliver(intent);
        assertEquals(Collections.singletonList("writeFinished"), listener.events);
    }

    // ----------------------------------------------------------------------------------------
    // Telemetry -> events
    // ----------------------------------------------------------------------------------------

    @Test
    public void telemetry_idleFirstPacketReportsPlayerIdAndBattery() throws Exception {
        deliverTelemetry(GOLDEN_IDLE);
        // CHARACTERIZATION: unlike master (which re-baselined counters on the first packet of a
        // connection), BlasterManager starts all "last" counters at 0, so only fields that differ
        // from 0 raise edge events. Player id 1 != 0 here.
        assertEquals(java.util.Arrays.asList("telemetry", "playerId:1", "battery:16/2"), listener.events);
    }

    @Test
    public void telemetry_triggerCounterEdgeRaisesTriggerPulledOnce() throws Exception {
        deliverTelemetry(GOLDEN_IDLE);
        listener.events.clear();
        deliverTelemetry(GOLDEN_TRIGGER_PULLED);
        deliverTelemetry(GOLDEN_TRIGGER_PULLED); // same counter: no second event
        assertEquals(1, listener.count("trigger:29"));
        assertEquals(0, listener.countPrefix("reload"));
    }

    @Test
    public void telemetry_reloadCounterEdgeRaisesReloadRequested() throws Exception {
        deliverTelemetry(GOLDEN_TRIGGER_PULLED);
        listener.events.clear();
        deliverTelemetry(GOLDEN_RELOAD_PRESSED);
        assertEquals(1, listener.count("reload"));
        assertEquals(0, listener.countPrefix("trigger"));
    }

    @Test
    public void telemetry_thumbCounterEdgeRaisesShotModeChange() throws Exception {
        deliverTelemetry(GOLDEN_IDLE);
        listener.events.clear();
        byte[] p = GOLDEN_IDLE.clone();
        p[GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER] = 1;
        deliverTelemetry(p);
        deliverTelemetry(p);
        assertEquals(1, listener.count("shotMode:0"));
    }

    @Test
    public void telemetry_powerCounterEdgeTogglesRecoilStateWithoutWritingToBlaster() throws Exception {
        connect();
        deliverTelemetry(GOLDEN_IDLE);
        listener.events.clear();
        byte[] p = GOLDEN_IDLE.clone();
        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 1;
        deliverTelemetry(p);
        assertEquals(1, listener.count("recoil:false"));
        assertFalse(manager.isRecoilEnabled());
        // CHARACTERIZATION: BlasterManager only flips its flag; it is up to the listener to
        // send the recoil config packet.
        verify(service, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class), any(byte[].class), anyInt());

        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 2;
        deliverTelemetry(p);
        assertEquals(1, listener.count("recoil:true"));
        assertTrue(manager.isRecoilEnabled());
    }

    @Test
    public void telemetry_counterWrapAroundIsStillAnEdge() throws Exception {
        byte[] p = GOLDEN_IDLE.clone();
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x0F;
        deliverTelemetry(p);
        listener.events.clear();
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x00; // 4-bit counter wrapped
        deliverTelemetry(p);
        assertEquals(1, listener.countPrefix("trigger"));
    }

    @Test
    public void telemetry_singleHitReportedAsPrimary() throws Exception {
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2);
        assertEquals(1, listener.count("hit:8/5/primary"));
        assertEquals(0, listener.countPrefix("hit:0"));
    }

    @Test
    public void telemetry_doubleHitReportsBothSlots() throws Exception {
        deliverTelemetry(GOLDEN_DOUBLE_HIT);
        int primary = listener.events.indexOf("hit:4/3/primary");
        int secondary = listener.events.indexOf("hit:12/6/secondary");
        assertTrue(primary >= 0);
        assertTrue("primary is reported before secondary", secondary > primary);
    }

    @Test
    public void telemetry_grenadeHitReportsUnsignedId167() throws Exception {
        deliverTelemetry(GOLDEN_GRENADE_DAMAGE);
        assertEquals(1, listener.count("hit:167/1/primary"));
    }

    @Test
    public void telemetry_hitsAreReportedOnEveryPacketWithoutDedup() throws Exception {
        // CHARACTERIZATION: the blaster repeats the same hit in consecutive notifications.
        // BlasterManager forwards every one; de-duplication by (player, shot id) is the
        // consumer's job (FullscreenActivity mLastHitData1/2).
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2);
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2);
        assertEquals(2, listener.count("hit:8/5/primary"));
    }

    @Test
    public void telemetry_secondarySlotReportedEvenIfPrimaryEmpty() throws Exception {
        // CHARACTERIZATION: master only looked at slot 2 when slot 1 was non-zero.
        deliverTelemetry(telemetry(
                GattProtocolHandler.RECOIL_OFFSET_HIT_BY2, 0x10,
                GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID, 0x02));
        assertEquals(1, listener.count("hit:16/2/secondary"));
        assertEquals(0, listener.countSuffix("/primary"));
    }

    @Test
    public void telemetry_batteryIsRollingIntegerAverageOfLast100Samples() throws Exception {
        byte[] p = GOLDEN_IDLE.clone();
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 16;
        deliverTelemetry(p);
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 13;
        deliverTelemetry(p);
        assertEquals("(16+13)/2 truncated", "battery:14/2", listener.last("battery"));

        // After 100 more samples of 10 the two early samples have rolled out of the window.
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 10;
        for (int i = 0; i < 100; i++) deliverTelemetry(p);
        assertEquals("battery:10/2", listener.last("battery"));
    }

    @Test
    public void telemetry_batteryReportCarriesBlasterType() throws Exception {
        deliverBlasterType(GattProtocolHandler.BLASTER_TYPE_RIFLE);
        deliverTelemetry(GOLDEN_IDLE);
        assertEquals("battery:16/1", listener.last("battery"));
    }

    @Test
    public void telemetry_truncatedPacketIsIgnoredSafely() throws Exception {
        deliverTelemetry(new byte[15]);
        deliverTelemetry(new byte[0]);
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void telemetry_missingExtraFallsBackToCharacteristicValue() throws Exception {
        connect();
        when(telemetryChar.getValue()).thenReturn(GOLDEN_HIT_BY_PLAYER_2);
        deliverTelemetry(null);
        assertEquals(1, listener.count("hit:8/5/primary"));
    }

    @Test
    public void telemetry_missingExtraAndNoCharacteristicIsIgnored() throws Exception {
        deliverTelemetry(null);
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void unknownOrNullIntentsAreIgnored() throws Exception {
        deliver(null);
        Intent noAction = mock(Intent.class);
        deliver(noAction);
        Intent other = mock(Intent.class);
        when(other.getAction()).thenReturn("some.other.ACTION");
        deliver(other);
        assertTrue(listener.events.isEmpty());
    }

    // ----------------------------------------------------------------------------------------

    /** Records listener callbacks as compact strings so event order can be asserted. */
    private static class RecordingListener implements BlasterEventListener {
        final List<String> events = new ArrayList<>();

        @Override public void onBlasterTypeDiscovered(byte blasterType) { events.add("type:" + blasterType); }
        @Override public void onTelemetryReceived(GattProtocolHandler.TelemetryData t) { events.add("telemetry"); }
        @Override public void onTriggerPulled(byte shotsRemaining) { events.add("trigger:" + shotsRemaining); }
        @Override public void onReloadRequested() { events.add("reload"); }
        @Override public void onShotModeChanged(int newShotMode) { events.add("shotMode:" + newShotMode); }
        @Override public void onRecoilToggled(boolean enabled) { events.add("recoil:" + enabled); }
        @Override public void onBatteryLevelUpdated(long avg, byte type) { events.add("battery:" + avg + "/" + type); }
        @Override public void onHitReceived(int shooter, byte shotId, boolean secondary) {
            events.add("hit:" + shooter + "/" + shotId + (secondary ? "/secondary" : "/primary"));
        }
        @Override public void onPlayerIdChanged(byte newPlayerId) { events.add("playerId:" + newPlayerId); }
        @Override public void onCharacteristicWriteFinished() { events.add("writeFinished"); }

        int count(String e) { return Collections.frequency(events, e); }

        int countPrefix(String prefix) {
            int n = 0;
            for (String e : events) if (e.startsWith(prefix)) n++;
            return n;
        }

        int countSuffix(String suffix) {
            int n = 0;
            for (String e : events) if (e.endsWith(suffix)) n++;
            return n;
        }

        String last(String prefix) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).startsWith(prefix)) return events.get(i);
            }
            return null;
        }
    }
}
