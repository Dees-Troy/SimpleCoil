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

package com.simplecoil.simplecoil.hardware.blaster

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.simplecoil.simplecoil.BluetoothLeService
import com.simplecoil.simplecoil.GattAttributes
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_GRENADE_DAMAGE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_HIT_BY_PLAYER_2
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_RELOAD_PRESSED
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_TRIGGER_PULLED
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.telemetry
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Exercises BlasterManager against mocked GATT objects to lock down which characteristic each
 * packet is written to, the write type, the rolling command sequence id, service discovery, and
 * how telemetry notifications are turned into blaster events.
 *
 * Runs on the plain JVM: BluetoothLeService / BluetoothGattService / BluetoothGattCharacteristic
 * are Mockito mocks, GATT events are fed through the manager's GattEventSink input, the
 * BlasterEvent stream is recorded as compact strings and protocol delays run in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlasterManagerGattTest {

    private val scope = TestScope()
    private lateinit var manager: BlasterManager
    private lateinit var service: BluetoothLeService
    private lateinit var telemetryChar: BluetoothGattCharacteristic
    private lateinit var commandChar: BluetoothGattCharacteristic
    private lateinit var configChar: BluetoothGattCharacteristic
    private lateinit var idChar: BluetoothGattCharacteristic
    private val events = mutableListOf<String>()
    private val serviceEvents = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)

    @Before
    fun setUp() {
        manager = BlasterManager(scope)
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            manager.events.collect { events += compact(it) }
        }
        service = mock(BluetoothLeService::class.java)
        `when`(service.events).thenReturn(serviceEvents)
        telemetryChar = mock(BluetoothGattCharacteristic::class.java)
        commandChar = mock(BluetoothGattCharacteristic::class.java)
        configChar = mock(BluetoothGattCharacteristic::class.java)
        idChar = mock(BluetoothGattCharacteristic::class.java)
    }

    private fun compact(e: BlasterEvent): String = when (e) {
        is BlasterEvent.Connected -> "connected:${e.address}"
        BlasterEvent.Disconnected -> "disconnected"
        BlasterEvent.ServicesDiscovered -> "servicesDiscovered"
        is BlasterEvent.TypeDiscovered -> "type:${e.blasterType}"
        BlasterEvent.WriteFinished -> "writeFinished"
        is BlasterEvent.Telemetry -> "telemetry"
        is BlasterEvent.PlayerIdChanged -> "playerId:${e.playerId}"
        is BlasterEvent.TriggerPulled -> "trigger:${e.shotsRemaining}"
        BlasterEvent.ReloadPressed -> "reload"
        BlasterEvent.ThumbPressed -> "thumb"
        is BlasterEvent.RecoilToggled -> "recoil:${e.enabled}"
        is BlasterEvent.BatteryLevel -> "battery:${e.average}/${e.blasterType}"
        is BlasterEvent.Hit -> "hit:${e.shooter}/${e.shotId}" + if (e.secondary) "/secondary" else "/primary"
    }

    private fun count(e: String) = events.count { it == e }
    private fun countPrefix(prefix: String) = events.count { it.startsWith(prefix) }
    private fun countSuffix(suffix: String) = events.count { it.endsWith(suffix) }
    private fun last(prefix: String) = events.lastOrNull { it.startsWith(prefix) }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    private fun recoilService(serviceUuid: String): BluetoothGattService {
        val gattService = mock(BluetoothGattService::class.java)
        `when`(gattService.uuid).thenReturn(UUID.fromString(serviceUuid))
        `when`(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID))).thenReturn(telemetryChar)
        `when`(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID))).thenReturn(commandChar)
        `when`(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID))).thenReturn(configChar)
        `when`(gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_ID_UUID))).thenReturn(idChar)
        return gattService
    }

    /** Wires the mocked service with a discovered Recoil GATT service. */
    private fun connect() {
        val gattService = recoilService(GattAttributes.RECOIL_MAIN_SERVICE)
        `when`(service.supportedGattServices).thenReturn(listOf(gattService))
        manager.setBluetoothLeService(service)
    }

    // Mockito matchers return null, which Kotlin rejects for BluetoothLeService's non-null
    // parameters; these register the matcher and hand Kotlin a value it accepts.
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyOf(type: Class<T>): T {
        any(type)
        return null as T
    }

    private fun <T> eqOf(value: T): T {
        eq(value)
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> capture(captor: ArgumentCaptor<T>): T {
        captor.capture()
        return null as T
    }

    private fun verifyNoWrites() {
        verify(service, never()).writeCharacteristic(anyOf(BluetoothGattCharacteristic::class.java), anyOf(ByteArray::class.java), anyInt())
    }

    private fun captureWrites(expectedChar: BluetoothGattCharacteristic, count: Int): List<ByteArray> {
        val captor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(service, times(count)).writeCharacteristic(eqOf(expectedChar), capture<ByteArray>(captor), eq(WRITE_TYPE_DEFAULT))
        return captor.allValues
    }

    private fun captureSingleWrite(expectedChar: BluetoothGattCharacteristic): ByteArray = captureWrites(expectedChar, 1)[0]

    private fun deliverTelemetry(data: ByteArray?) = manager.onTelemetry(data)

    private fun deliverBlasterType(type: Byte) = manager.onBlasterId(type)

    // ----------------------------------------------------------------------------------------
    // Service discovery
    // ----------------------------------------------------------------------------------------

    @Test
    fun discovery_enablesTelemetryNotificationsAndReadsIdCharacteristic() {
        connect()
        verify(service).setCharacteristicNotification(telemetryChar, true)
        verify(service).readCharacteristic(idChar)
        // Discovery itself must not write to command/config; the recoil write is deferred.
        verifyNoWrites()
    }

    @Test
    fun discovery_writesCurrentRecoilSettingAfterExactly500ms() {
        manager.setRecoil(false) // dropped (not connected), but remembered
        connect()
        advance(499)
        verifyNoWrites()
        advance(1)
        assertEquals("10 00 02 02 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(configChar)))
    }

    @Test
    fun discovery_deferredRecoilWriteUsesSettingAtFireTime() {
        connect()
        manager.setRecoil(false)
        advance(500)
        val writes = captureWrites(configChar, 2)
        assertEquals("10 00 02 02 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(writes[1]))
    }

    @Test
    fun discovery_matchesServiceUuidCaseInsensitively() {
        val gattService = recoilService(GattAttributes.RECOIL_MAIN_SERVICE.uppercase())
        `when`(service.supportedGattServices).thenReturn(listOf(gattService))
        manager.setBluetoothLeService(service)
        verify(service).setCharacteristicNotification(telemetryChar, true)
    }

    @Test
    fun discovery_ignoresOtherServices() {
        // e.g. the standard Battery Service (0x180F) must not be mistaken for the Recoil service
        val gattService = recoilService("0000180f-0000-1000-8000-00805f9b34fb")
        `when`(service.supportedGattServices).thenReturn(listOf(gattService))
        manager.setBluetoothLeService(service)
        verify(service, never()).setCharacteristicNotification(anyOf(BluetoothGattCharacteristic::class.java), anyBoolean())
        verify(service, never()).readCharacteristic(anyOf(BluetoothGattCharacteristic::class.java))

        manager.setTeam(1)
        verifyNoWrites()
    }

    @Test
    fun discovery_withNoServicesDoesNothing() {
        `when`(service.supportedGattServices).thenReturn(null)
        manager.setBluetoothLeService(service)
        manager.setTeam(1)
        manager.setRecoil(false)
        verifyNoWrites()
    }

    @Test
    fun servicesDiscoveredEvent_discoversCharacteristicsBeforeForwarding() {
        `when`(service.supportedGattServices).thenReturn(null)
        manager.setBluetoothLeService(service)
        val recoil = recoilService(GattAttributes.RECOIL_MAIN_SERVICE)
        `when`(service.supportedGattServices).thenReturn(listOf(recoil))
        manager.onEvent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
        verify(service).setCharacteristicNotification(telemetryChar, true)
        assertEquals(listOf("servicesDiscovered"), events)
        // Characteristics are ready by the time the event is consumed.
        manager.startReload(1)
        captureSingleWrite(commandChar)
    }

    // ----------------------------------------------------------------------------------------
    // Outbound routing: characteristic + write type + exact bytes
    // ----------------------------------------------------------------------------------------

    @Test
    fun setTeam_writesToCommandCharacteristic() {
        connect()
        manager.setTeam(5)
        assertEquals("00 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(commandChar)))
    }

    @Test
    fun startReload_writesToCommandCharacteristic() {
        connect()
        manager.startReload(7)
        assertEquals("00 00 02 00 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(commandChar)))
    }

    @Test
    fun finishReload_writesToCommandCharacteristicWithProfile0() {
        connect()
        manager.finishReload(7, 30)
        assertEquals("00 00 04 00 07 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(commandChar)))
    }

    @Test
    fun setShotMode_writesToConfigCharacteristic() {
        connect()
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_FULL_AUTO, GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE)
        assertEquals("00 00 09 FE 01 FF C8 FF FF 80 02 34 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(configChar)))
    }

    @Test
    fun setRecoil_writesToConfigCharacteristic() {
        connect()
        manager.setRecoil(false)
        manager.setRecoil(true)
        val writes = captureWrites(configChar, 2)
        assertEquals("10 00 02 02 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(writes[0]))
        assertEquals("10 00 02 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(writes[1]))
    }

    @Test
    fun commandSequenceId_incrementsBy0x10AndWrapsAfter0xF0() {
        connect()
        // 17 commands: ids 00 10 20 ... F0 then wraps to 00
        for (i in 0 until 17) {
            when (i % 3) {
                0 -> manager.setTeam(1)
                1 -> manager.startReload(1)
                else -> manager.finishReload(1, 10)
            }
        }
        val writes = captureWrites(commandChar, 17)
        for (i in 0 until 17) {
            assertEquals("command #$i", ((i % 16) shl 4).toByte(), writes[i][0])
        }
    }

    @Test
    fun configWrites_doNotConsumeCommandSequenceIds() {
        connect()
        manager.setTeam(1)
        manager.setRecoil(true)
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_SINGLE, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)
        manager.startReload(1)
        val commands = captureWrites(commandChar, 2)
        assertEquals(0x00.toByte(), commands[0][0])
        assertEquals(0x10.toByte(), commands[1][0])
    }

    @Test
    fun setTeam_rejectsPlayerIdBelow1WithoutConsumingSequenceId() {
        connect()
        manager.setTeam(0)
        // Bytes are signed: ids >= 0x80 are negative and therefore also rejected.
        manager.setTeam(0x80.toByte())
        manager.setTeam(0xFF.toByte())
        verifyNoWrites()

        manager.setTeam(0x7F)
        assertEquals("00 00 80 00 7F 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(commandChar)))
    }

    @Test
    fun commands_areDroppedWhenNotConnected() {
        manager.setTeam(1)
        manager.startReload(1)
        manager.finishReload(1, 30)
        manager.setShotMode(GameSettingsRepository.SHOT_MODE_SINGLE, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)
        manager.setRecoil(false)
        verifyNoWrites()
        // Recoil preference is remembered even when the write could not be sent.
        assertFalse(manager.isRecoilEnabled)

        // Dropped commands must not have advanced the sequence id.
        connect()
        manager.setTeam(1)
        assertEquals(0x00.toByte(), captureSingleWrite(commandChar)[0])
    }

    // ----------------------------------------------------------------------------------------
    // ID characteristic -> blaster type
    // ----------------------------------------------------------------------------------------

    @Test
    fun blasterType_defaultsToPistol() {
        assertEquals(GattProtocolHandler.BLASTER_TYPE_PISTOL, manager.blasterType)
    }

    @Test
    fun blasterType_rifleReportedByIdCharacteristicChangesBurstRecoil() {
        connect()
        deliverBlasterType(GattProtocolHandler.BLASTER_TYPE_RIFLE)
        assertEquals(GattProtocolHandler.BLASTER_TYPE_RIFLE, manager.blasterType)
        assertEquals(listOf("type:1"), events)

        manager.setShotMode(GameSettingsRepository.SHOT_MODE_BURST, GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)
        assertEquals("00 00 09 03 03 FF 00 FF FF 78 02 34 00 00 00 00 00 00 00 00", toHex(captureSingleWrite(configChar)))
    }

    @Test
    fun missingBlasterTypeDefaultsToPistol() {
        manager.onBlasterId(GattProtocolHandler.BLASTER_TYPE_RIFLE)
        manager.onBlasterId(null)
        assertEquals(GattProtocolHandler.BLASTER_TYPE_PISTOL, manager.blasterType)
        assertEquals(listOf("type:1", "type:2"), events)
    }

    @Test
    fun characteristicWriteFinished_isForwarded() {
        manager.onEvent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
        assertEquals(listOf("writeFinished"), events)
    }

    @Test
    fun connectionEvents_areForwardedWithAddress() {
        manager.deviceAddress = "AA:BB:CC:DD:EE:FF"
        manager.onEvent(BluetoothLeService.ACTION_GATT_CONNECTED)
        assertTrue(manager.isConnected)
        manager.onEvent(BluetoothLeService.ACTION_GATT_DISCONNECTED)
        assertFalse(manager.isConnected)
        assertEquals(listOf("connected:AA:BB:CC:DD:EE:FF", "disconnected"), events)
    }

    // ----------------------------------------------------------------------------------------
    // Consuming the service's in-process events (D8)
    // ----------------------------------------------------------------------------------------

    @Test
    fun serviceEvents_areConsumedInOrderOnceAttached() {
        connect()
        advance(0)
        serviceEvents.tryEmit(GattEvent.Action(BluetoothLeService.ACTION_GATT_CONNECTED))
        serviceEvents.tryEmit(GattEvent.BlasterId(GattProtocolHandler.BLASTER_TYPE_RIFLE))
        serviceEvents.tryEmit(GattEvent.Telemetry(GOLDEN_HIT_BY_PLAYER_2))
        serviceEvents.tryEmit(GattEvent.Action(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED))
        advance(0)
        assertEquals(listOf("connected:null", "type:1", "telemetry", "playerId:1", "battery:15/1",
            "hit:8/5/primary", "writeFinished"), events)
    }

    @Test
    fun serviceEvents_stopWhenDetached() {
        connect()
        advance(0)
        manager.setBluetoothLeService(null)
        advance(0)
        serviceEvents.tryEmit(GattEvent.Action(BluetoothLeService.ACTION_GATT_CONNECTED))
        advance(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun unknownEventsAreIgnored() {
        manager.onEvent("some.other.ACTION")
        manager.onEvent(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED)
        assertTrue(events.isEmpty())
    }

    // ----------------------------------------------------------------------------------------
    // Telemetry -> events
    // ----------------------------------------------------------------------------------------

    @Test
    fun telemetry_idleFirstPacketReportsPlayerIdAndBattery() {
        deliverTelemetry(GOLDEN_IDLE)
        // Player id 1 differs from the initial 0; battery is (seed 16 + 16) / 2.
        assertEquals(listOf("telemetry", "playerId:1", "battery:16/2"), events)
    }

    @Test
    fun telemetry_firstPacketOfConnectionRebaselinesCountersWithoutEdges() {
        // D3: like the activity, counters are copied from the first packet of each connection
        // instead of being compared against 0, so connecting raises no trigger/reload/thumb/power
        // edges and doesn't flip the recoil flag.
        val p = GOLDEN_IDLE.clone()
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x35
        p[GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER] = 4
        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 3
        deliverTelemetry(p)
        assertEquals(0, countPrefix("trigger") + countPrefix("reload") + countPrefix("thumb") + countPrefix("recoil"))
        assertTrue(manager.isRecoilEnabled)

        // Reconnect: the blaster's counters may have moved while disconnected.
        manager.onEvent(BluetoothLeService.ACTION_GATT_CONNECTED)
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x77
        p[GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER] = 9
        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 8
        deliverTelemetry(p)
        assertEquals(0, countPrefix("trigger") + countPrefix("reload") + countPrefix("thumb") + countPrefix("recoil"))

        // The next change is an edge again.
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x78
        deliverTelemetry(p)
        assertEquals(1, countPrefix("trigger"))
    }

    @Test
    fun telemetry_triggerCounterEdgeRaisesTriggerPulledOnce() {
        deliverTelemetry(GOLDEN_IDLE)
        events.clear()
        deliverTelemetry(GOLDEN_TRIGGER_PULLED)
        deliverTelemetry(GOLDEN_TRIGGER_PULLED) // same counter: no second event
        assertEquals(1, count("trigger:29"))
        assertEquals(0, countPrefix("reload"))
    }

    @Test
    fun telemetry_reloadCounterEdgeRaisesReloadRequested() {
        deliverTelemetry(GOLDEN_TRIGGER_PULLED)
        events.clear()
        deliverTelemetry(GOLDEN_RELOAD_PRESSED)
        assertEquals(1, count("reload"))
        assertEquals(0, countPrefix("trigger"))
    }

    @Test
    fun telemetry_thumbCounterEdgeRaisesShotModeChange() {
        deliverTelemetry(GOLDEN_IDLE)
        events.clear()
        val p = GOLDEN_IDLE.clone()
        p[GattProtocolHandler.RECOIL_OFFSET_THUMB_COUNTER] = 1
        deliverTelemetry(p)
        deliverTelemetry(p)
        assertEquals(1, count("thumb"))
    }

    @Test
    fun telemetry_powerCounterEdgeTogglesRecoilStateWithoutWritingToBlaster() {
        connect()
        deliverTelemetry(GOLDEN_IDLE)
        events.clear()
        val p = GOLDEN_IDLE.clone()
        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 1
        deliverTelemetry(p)
        assertEquals(1, count("recoil:false"))
        assertFalse(manager.isRecoilEnabled)
        // CHARACTERIZATION: BlasterManager only flips its flag; it is up to the consumer to
        // send the recoil config packet.
        verifyNoWrites()

        p[GattProtocolHandler.RECOIL_OFFSET_POWER_COUNTER] = 2
        deliverTelemetry(p)
        assertEquals(1, count("recoil:true"))
        assertTrue(manager.isRecoilEnabled)
    }

    @Test
    fun telemetry_counterWrapAroundIsStillAnEdge() {
        val p = GOLDEN_IDLE.clone()
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x0F
        deliverTelemetry(p)
        events.clear()
        p[GattProtocolHandler.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 0x00 // 4-bit counter wrapped
        deliverTelemetry(p)
        assertEquals(1, countPrefix("trigger"))
    }

    @Test
    fun telemetry_singleHitReportedAsPrimary() {
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2)
        assertEquals(1, count("hit:8/5/primary"))
        assertEquals(0, countPrefix("hit:0"))
    }

    @Test
    fun telemetry_doubleHitReportsBothSlots() {
        deliverTelemetry(GOLDEN_DOUBLE_HIT)
        val primary = events.indexOf("hit:4/3/primary")
        val secondary = events.indexOf("hit:12/6/secondary")
        assertTrue(primary >= 0)
        assertTrue("primary is reported before secondary", secondary > primary)
    }

    @Test
    fun telemetry_grenadeHitReportsUnsignedId167() {
        deliverTelemetry(GOLDEN_GRENADE_DAMAGE)
        assertEquals(1, count("hit:167/1/primary"))
    }

    @Test
    fun telemetry_hitsAreReportedOnEveryPacketWithoutDedup() {
        // CHARACTERIZATION: the blaster repeats the same hit in consecutive notifications.
        // BlasterManager forwards every one; de-duplication by (player, shot id) is the
        // consumer's job (HitResolver).
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2)
        deliverTelemetry(GOLDEN_HIT_BY_PLAYER_2)
        assertEquals(2, count("hit:8/5/primary"))
    }

    @Test
    fun telemetry_secondarySlotReportedEvenIfPrimaryEmpty() {
        // CHARACTERIZATION: master only looked at slot 2 when slot 1 was non-zero; that rule
        // lives in HitResolver, not here.
        deliverTelemetry(telemetry(
            GattProtocolHandler.RECOIL_OFFSET_HIT_BY2, 0x10,
            GattProtocolHandler.RECOIL_OFFSET_HIT_BY2_SHOTID, 0x02))
        assertEquals(1, count("hit:16/2/secondary"))
        assertEquals(0, countSuffix("/primary"))
    }

    @Test
    fun telemetry_batteryIsRollingAverageSeededWith16() {
        // D3: seeded with one 16 sample, as the activity does. Before D3 the manager was unseeded
        // and reported (16 + 13) / 2 = 14 here.
        val p = GOLDEN_IDLE.clone()
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 16
        deliverTelemetry(p)
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 13
        deliverTelemetry(p)
        assertEquals("(16 seed + 16 + 13) / 3 truncated", "battery:15/2", last("battery"))

        // After 100 more samples of 10 the seed and the two early samples have rolled out of the window.
        p[GattProtocolHandler.RECOIL_OFFSET_BATTERY_LEVEL] = 10
        repeat(100) { deliverTelemetry(p) }
        assertEquals("battery:10/2", last("battery"))
    }

    @Test
    fun telemetry_batteryReportCarriesBlasterType() {
        deliverBlasterType(GattProtocolHandler.BLASTER_TYPE_RIFLE)
        deliverTelemetry(GOLDEN_IDLE)
        assertEquals("battery:16/1", last("battery"))
    }

    @Test
    fun telemetry_truncatedPacketIsIgnoredSafely() {
        deliverTelemetry(ByteArray(15))
        deliverTelemetry(ByteArray(0))
        assertTrue(events.isEmpty())
    }

    @Test
    fun telemetry_missingExtraFallsBackToCharacteristicValue() {
        connect()
        `when`(telemetryChar.value).thenReturn(GOLDEN_HIT_BY_PLAYER_2)
        deliverTelemetry(null)
        assertEquals(1, count("hit:8/5/primary"))
    }

    @Test
    fun telemetry_missingExtraAndNoCharacteristicIsIgnored() {
        deliverTelemetry(null)
        assertTrue(events.isEmpty())
    }

    companion object {
        /** BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT (write with response). */
        private const val WRITE_TYPE_DEFAULT = 2
    }
}
