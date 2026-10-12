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
import android.content.Context
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.FakeGameConfig
import com.simplecoil.simplecoil.domain.session.Recorder
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository
import com.simplecoil.simplecoil.hardware.blaster.GattIo
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.toHex
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * Game transitions end to end: BlasterSessionEngine → BleDeviceRepository → BlasterManager →
 * BluetoothLeService → (fake) BluetoothGatt, and the engine's network sends through
 * NetworkSessionGateway → (mock) TcpClient. Asserts the exact bytes written to the blaster and
 * the exact TCP strings, with the §1.6 delays in virtual time.
 */
class SessionEndToEndTest {

    private val scope = TestScope()
    private val callbackGatt: BluetoothGatt = mock(BluetoothGatt::class.java)
    private lateinit var gatt: GattIo
    private lateinit var service: BluetoothLeService
    private lateinit var engine: BlasterSessionEngine
    private lateinit var client: TcpClient
    private val config = FakeGameConfig()

    private lateinit var telemetryChar: BluetoothGattCharacteristic
    private lateinit var commandChar: BluetoothGattCharacteristic
    private lateinit var configChar: BluetoothGattCharacteristic

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
        val idChar = characteristic(GattAttributes.RECOIL_ID_UUID)
        `when`(telemetryChar.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG)))
            .thenReturn(mock(BluetoothGattDescriptor::class.java))
        val recoil = mock(BluetoothGattService::class.java)
        `when`(recoil.uuid).thenReturn(UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE))
        for (c in listOf(telemetryChar, commandChar, configChar, idChar)) {
            `when`(recoil.getCharacteristic(c.uuid)).thenReturn(c)
        }
        `when`(gatt.services).thenReturn(emptyList())

        service = object : BluetoothLeService() {
            override fun startAsForeground(statusText: String) = Unit
        }
        service.attachForTest(gatt, null, scope)
        val manager = BlasterManager(scope)
        val repository = BleDeviceRepository(manager)
        manager.setBluetoothLeService(service)

        val net = NetworkSessionGateway(mock(Context::class.java), scope)
        client = mock(TcpClient::class.java)
        net.attachForTest(null, client, null)
        net.useNetwork = true

        val prefs = Recorder()
        engine = BlasterSessionEngine(repository, config, prefs, scope, clock = { scope.testScheduler.currentTime },
            wallClock = { 1_000_000 + scope.testScheduler.currentTime })
        engine.network = net
        engine.attach(repository.events)

        // Connect and discover, as BluetoothLeService reports it.
        service.getGattCallback().onConnectionStateChange(callbackGatt, BluetoothGatt.GATT_SUCCESS, 2)
        advance(BluetoothLeService.DISCOVER_SERVICES_DELAY_MILLIS)
        `when`(gatt.services).thenReturn(listOf(recoil))
        service.getGattCallback().onServicesDiscovered(callbackGatt, BluetoothGatt.GATT_SUCCESS)
        advance(BlasterManager.RECOIL_AFTER_DISCOVERY_MILLIS)
    }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    @Suppress("DEPRECATION")
    private fun writes(c: BluetoothGattCharacteristic): List<String> {
        val captor = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(c, org.mockito.Mockito.atLeast(0)).setValue(captor.capture())
        return captor.allValues.map { toHex(it) }
    }

    private fun sent(): List<String> {
        val strings = mutableListOf<String>()
        val single = ArgumentCaptor.forClass(String::class.java)
        verify(client, org.mockito.Mockito.atLeast(0)).sendTCPMessage(single.capture())
        strings += single.allValues
        val queued = ArgumentCaptor.forClass(String::class.java)
        verify(client, org.mockito.Mockito.atLeast(0)).sendTCPMessage(queued.capture(), org.mockito.ArgumentMatchers.eq(true))
        strings += queued.allValues.map { "$it (queued)" }
        return strings
    }

    private fun notify(packet: ByteArray) {
        service.getGattCallback().onCharacteristicChanged(callbackGatt, telemetryChar, packet)
        scope.testScheduler.runCurrent()
    }

    private fun writeFinished() {
        service.getGattCallback().onCharacteristicWrite(callbackGatt, commandChar, BluetoothGatt.GATT_SUCCESS)
        scope.testScheduler.runCurrent()
    }

    private fun packet(): ByteArray = RecoilProtocolFixtures.GOLDEN_IDLE.clone().also {
        it[1] = 5 // player 5
    }

    private val recoilOn = "10 00 02 03 FF 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
    private val singleOutdoor = "00 00 09 FE 00 FF 00 FF FF 80 02 34 00 00 00 00 00 00 00 00"

    @Test
    fun firstPacket_writesShotModeNow_teamAt250_recoilAt500() {
        assertEquals(listOf(recoilOn), writes(configChar)) // discovery's own recoil write
        notify(packet())
        assertEquals(listOf(recoilOn, singleOutdoor), writes(configChar))
        advance(249)
        assertEquals(emptyList<String>(), writes(commandChar))
        advance(1)
        assertEquals(listOf("00 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), writes(commandChar))
        advance(249)
        assertEquals(2, writes(configChar).size)
        advance(1)
        assertEquals(listOf(recoilOn, singleOutdoor, recoilOn), writes(configChar))
    }

    @Test
    fun startGame_spawn_reload_hit_elimination_bytesAndStrings() {
        config.fullHealth = 2
        notify(packet())
        advance(1_000)

        engine.startGame()
        scope.testScheduler.runCurrent()
        advance(10_000) // respawn countdown
        // Stage 2 of the spawn reload: CID 10 (team used 00), 30 shots.
        assertEquals(listOf(
            "00 00 80 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00",
            "10 00 04 00 05 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00",
        ), writes(commandChar))
        writeFinished()

        // Hit by player 2 (IR 0x08), then the lethal hit.
        notify(packet().also { it[9] = 0x08; it[8] = 0x3D })
        notify(packet())
        advance(401) // HIT/OUT messages are limited to one per 400 ms
        notify(packet().also { it[9] = 0x08; it[8] = 0x3E })
        assertEquals(listOf("SimpleCoil:06MESGHIT2", "SimpleCoil:06MESGOUT2", "SimpleCoil:06MESGELIMINATED2 (queued)"), sent())
        // Eliminated: stage 1 locks the blaster.
        assertEquals("20 00 02 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", writes(commandChar).last())
    }

    @Test
    fun reloadButton_twoStagesWithReloadTimeBetween() {
        notify(packet())
        advance(1_000)
        engine.startGame()
        scope.testScheduler.runCurrent()
        advance(10_000)
        writeFinished()
        notify(packet().also { it[14] = 12 })
        val before = writes(commandChar).size

        notify(packet().also { it[14] = 12; it[3] = 0x10 }) // reload button
        assertEquals("20 00 02 00 05 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00", writes(commandChar).last())
        writeFinished()
        advance(1_499)
        assertEquals(before + 1, writes(commandChar).size)
        advance(1)
        assertEquals("30 00 04 00 05 00 1E 00 00 00 00 00 00 00 00 00 00 00 00 00", writes(commandChar).last())
    }
}
