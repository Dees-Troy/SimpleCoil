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

package com.simplecoil.simplecoil

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository
import com.simplecoil.simplecoil.hardware.blaster.GattIo
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * The BLE stack wired as in the app (BluetoothLeService → in-process events → BlasterManager →
 * BleDeviceRepository) over a fake GattIo: GATT callbacks in, BlasterEvents and the exact bytes
 * handed to BluetoothGatt out, with the §1.6 delays in virtual time.
 *
 * The first-packet writes (shot mode now, team at +250 ms, recoil at +500 ms) are still issued by
 * FullscreenActivity and get their virtual-time test with the session engine (Phase 3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleStackEndToEndTest {

    private val scope = TestScope()
    private val callbackGatt: BluetoothGatt = mock(BluetoothGatt::class.java)
    private lateinit var gatt: GattIo
    private lateinit var service: BluetoothLeService
    private lateinit var repository: BleDeviceRepository
    private val events = mutableListOf<String>()

    private lateinit var telemetryChar: BluetoothGattCharacteristic
    private lateinit var commandChar: BluetoothGattCharacteristic
    private lateinit var configChar: BluetoothGattCharacteristic
    private lateinit var idChar: BluetoothGattCharacteristic
    private lateinit var cccd: BluetoothGattDescriptor
    private lateinit var recoilService: BluetoothGattService

    private fun characteristic(uuid: String): BluetoothGattCharacteristic {
        val c = mock(BluetoothGattCharacteristic::class.java)
        `when`(c.uuid).thenReturn(UUID.fromString(uuid))
        return c
    }

    @Before
    fun setUp() {
        gatt = mock(GattIo::class.java)
        telemetryChar = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        commandChar = characteristic(GattAttributes.RECOIL_COMMAND_UUID)
        configChar = characteristic(GattAttributes.RECOIL_CONFIG_UUID)
        idChar = characteristic(GattAttributes.RECOIL_ID_UUID)
        cccd = mock(BluetoothGattDescriptor::class.java)
        `when`(telemetryChar.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))).thenReturn(cccd)
        val recoil = mock(BluetoothGattService::class.java)
        `when`(recoil.uuid).thenReturn(UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE))
        for (c in listOf(telemetryChar, commandChar, configChar, idChar)) {
            `when`(recoil.getCharacteristic(c.uuid)).thenReturn(c)
        }
        recoilService = recoil
        // Like BluetoothGatt: no services until discovery has run.
        `when`(gatt.services).thenReturn(emptyList())

        service = object : BluetoothLeService() {
            override fun startAsForeground(statusText: String) = Unit
        }
        service.attachForTest(gatt, null, scope)
        val manager = BlasterManager(scope)
        repository = BleDeviceRepository(manager)
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            repository.events.collect { events += compact(it) }
        }
        manager.setBluetoothLeService(service)
        events.clear()
    }

    private fun compact(e: BlasterEvent): String = when (e) {
        is BlasterEvent.Connected -> "connected"
        BlasterEvent.Disconnected -> "disconnected"
        BlasterEvent.ServicesDiscovered -> "servicesDiscovered"
        is BlasterEvent.TypeDiscovered -> "type:${e.blasterType}"
        BlasterEvent.WriteFinished -> "writeFinished"
        is BlasterEvent.Telemetry -> "telemetry"
        is BlasterEvent.PlayerIdChanged -> "playerId:${e.playerId}"
        is BlasterEvent.TriggerPulled -> "trigger"
        BlasterEvent.ReloadPressed -> "reload"
        BlasterEvent.ThumbPressed -> "thumb"
        is BlasterEvent.RecoilToggled -> "recoil:${e.enabled}"
        is BlasterEvent.BatteryLevel -> "battery"
        is BlasterEvent.Hit -> "hit:${e.shooter}/${e.shotId}/${if (e.secondary) 2 else 1}"
    }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    /** Every value written to [c] through the pre-API-33 path (setValue, then writeCharacteristic). */
    @Suppress("DEPRECATION")
    private fun writes(c: BluetoothGattCharacteristic): List<String> {
        val captor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(c, org.mockito.Mockito.atLeast(0)).setValue(captor.capture())
        return captor.allValues.map { toHex(it) }
    }

    private fun servicesDiscovered() {
        `when`(gatt.services).thenReturn(listOf(recoilService))
        service.getGattCallback().onServicesDiscovered(callbackGatt, BluetoothGatt.GATT_SUCCESS)
    }

    private fun connectAndDiscover() {
        service.getGattCallback().onConnectionStateChange(callbackGatt, BluetoothGatt.GATT_SUCCESS, 2)
        advance(BluetoothLeService.DISCOVER_SERVICES_DELAY_MILLIS)
        servicesDiscovered()
        scope.testScheduler.runCurrent()
    }

    @Test
    fun connect_discover_enableTelemetry_readId_thenRecoilAt500ms() {
        service.getGattCallback().onConnectionStateChange(callbackGatt, BluetoothGatt.GATT_SUCCESS, 2)
        scope.testScheduler.runCurrent()
        advance(499)
        verify(gatt, never()).discoverServices()
        advance(1)
        verify(gatt).discoverServices()

        servicesDiscovered()
        scope.testScheduler.runCurrent()
        val order = inOrder(gatt)
        order.verify(gatt).setCharacteristicNotification(telemetryChar, false)
        order.verify(gatt).setCharacteristicNotification(telemetryChar, true)
        order.verify(gatt).writeDescriptor(cccd)
        order.verify(gatt).readCharacteristic(idChar)
        assertEquals(listOf("connected", "servicesDiscovered"), events)

        advance(499)
        assertEquals(emptyList<String>(), writes(configChar))
        advance(1)
        assertEquals(listOf("10 00 02 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), writes(configChar))
        verify(gatt).writeCharacteristic(configChar)
    }

    @Test
    fun rifleIdRead_thenBurstShotMode_writesReducedRecoil() {
        connectAndDiscover()
        val id = ByteArray(20).also { it[10] = 0x01 }
        service.getGattCallback().onCharacteristicRead(callbackGatt, idChar, id, BluetoothGatt.GATT_SUCCESS)
        scope.testScheduler.runCurrent()
        assertEquals("type:1", events.last())

        repository.setShotMode(GameSettingsRepository.SHOT_MODE_BURST, GameSettingsRepository.FIRING_MODE_OUTDOOR_WITH_CONE)
        assertEquals(listOf("00 00 09 03 03 FF C8 FF FF 78 02 34 00 00 00 00 00 00 00 00"), writes(configChar))
    }

    @Test
    fun goldenDoubleHit_comesOutAsOrderedEvents() {
        connectAndDiscover()
        events.clear()
        service.getGattCallback().onCharacteristicChanged(callbackGatt, telemetryChar, RecoilProtocolFixtures.GOLDEN_DOUBLE_HIT)
        scope.testScheduler.runCurrent()
        assertEquals(listOf("telemetry", "playerId:1", "battery", "hit:4/3/1", "hit:12/6/2"), events)
    }

    @Test
    fun reloadSequence_commandBytesAndWriteFinishedOrdering() {
        connectAndDiscover()
        events.clear()
        repository.setTeam(5)
        repository.startReload(5)
        service.getGattCallback().onCharacteristicWrite(callbackGatt, commandChar, BluetoothGatt.GATT_SUCCESS)
        scope.testScheduler.runCurrent()
        repository.finishReload(5, 30)
        service.getGattCallback().onCharacteristicWrite(callbackGatt, commandChar, BluetoothGatt.GATT_SUCCESS)
        scope.testScheduler.runCurrent()

        assertEquals(listOf(
            "00 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
            "10 00 02 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
            "20 00 04 00 05 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00",
        ), writes(commandChar))
        assertEquals(listOf("writeFinished", "writeFinished"), events)
    }

    @Test
    fun eventOrder_servicesDiscoveredThenTelemetryThenWriteFinished() {
        service.getGattCallback().onConnectionStateChange(callbackGatt, BluetoothGatt.GATT_SUCCESS, 2)
        servicesDiscovered()
        service.getGattCallback().onCharacteristicChanged(callbackGatt, telemetryChar, RecoilProtocolFixtures.GOLDEN_IDLE)
        service.getGattCallback().onCharacteristicWrite(callbackGatt, configChar, BluetoothGatt.GATT_SUCCESS)
        scope.testScheduler.runCurrent()
        assertEquals(listOf("connected", "servicesDiscovered", "telemetry", "playerId:1", "battery", "writeFinished"), events)
    }

    @Test
    fun shortTelemetry_isDroppedWithoutEvents() {
        // B3: the activity used to index into the raw array and crash on short packets.
        connectAndDiscover()
        events.clear()
        service.getGattCallback().onCharacteristicChanged(callbackGatt, telemetryChar, ByteArray(14))
        scope.testScheduler.runCurrent()
        assertEquals(emptyList<String>(), events)
    }
}
