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
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.simplecoil.simplecoil.hardware.blaster.GattEventSink
import com.simplecoil.simplecoil.hardware.blaster.GattIo
import com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures
import com.simplecoil.simplecoil.hardware.blaster.GattEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.lang.reflect.Field
import java.util.UUID

/**
 * Isolates BluetoothLeService (the only class that touches BluetoothGatt directly) from the
 * Android framework so the GATT plumbing can be verified on the JVM:
 *
 *  - which notifications/reads are reported as which event, and with what payload
 *  - the CCCD descriptor write that turns on telemetry notifications
 *  - how characteristic writes are handed to BluetoothGatt
 *  - the protocol delays (§1.6) it owns
 *
 * GATT calls go to a mocked GattIo, events to a recording GattEventSink and protocol delays to a
 * virtual-time TestScope, all injected through BluetoothLeService.attachForTest().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothLeServiceGattTest {

    /** One event delivered to the sink: the action plus its payload, if any. */
    private class Event(val action: String, val data: ByteArray? = null, val blasterType: Byte? = null)

    private val events = mutableListOf<Event>()
    private val actions get() = events.map { it.action }

    private val sink = object : GattEventSink {
        override fun onEvent(action: String) {
            events += Event(action)
        }

        override fun onTelemetry(data: ByteArray?) {
            events += Event(BluetoothLeService.TELEMETRY_DATA_AVAILABLE, data = data)
        }

        override fun onBlasterId(blasterType: Byte?) {
            events += Event(BluetoothLeService.ID_DATA_AVAILABLE, blasterType = blasterType)
        }
    }

    private lateinit var service: BluetoothLeService

    /** Passed to callbacks as the framework would; the service itself talks to [gatt]. */
    private val callbackGatt: BluetoothGatt = mock(BluetoothGatt::class.java)
    private lateinit var gatt: GattIo
    private lateinit var callback: BluetoothGattCallback
    private val scope = TestScope()

    private var savedEnable: Any? = null
    private var savedDisable: Any? = null

    @Before
    fun setUp() {
        service = object : BluetoothLeService() {
            override fun startAsForeground(statusText: String) {
                // Building the notification needs a real Context; record the call instead.
                this@BluetoothLeServiceGattTest.events += Event(START_FOREGROUND)
            }
        }
        gatt = mock(GattIo::class.java)
        service.attachForTest(gatt, sink, scope)
        callback = service.getGattCallback()

        // The android.jar stubs leave these arrays null; install the real values so the CCCD
        // payload can be asserted.
        savedEnable = StaticFieldWriter.get(BluetoothGattDescriptor::class.java, "ENABLE_NOTIFICATION_VALUE")
        savedDisable = StaticFieldWriter.get(BluetoothGattDescriptor::class.java, "DISABLE_NOTIFICATION_VALUE")
        StaticFieldWriter.set(BluetoothGattDescriptor::class.java, "ENABLE_NOTIFICATION_VALUE", ENABLE_NOTIFICATION)
        StaticFieldWriter.set(BluetoothGattDescriptor::class.java, "DISABLE_NOTIFICATION_VALUE", DISABLE_NOTIFICATION)
    }

    @After
    fun tearDown() {
        StaticFieldWriter.set(BluetoothGattDescriptor::class.java, "ENABLE_NOTIFICATION_VALUE", savedEnable)
        StaticFieldWriter.set(BluetoothGattDescriptor::class.java, "DISABLE_NOTIFICATION_VALUE", savedDisable)
    }

    private fun characteristic(uuid: String): BluetoothGattCharacteristic {
        val c = mock(BluetoothGattCharacteristic::class.java)
        `when`(c.uuid).thenReturn(UUID.fromString(uuid))
        return c
    }

    /** Returns the event delivered for the given action. */
    private fun eventFor(action: String): Event {
        val event = events.firstOrNull { it.action == action }
        assertTrue("expected event $action but got $actions", event != null)
        return event!!
    }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    // ----------------------------------------------------------------------------------------
    // Notification / read routing
    // ----------------------------------------------------------------------------------------

    @Test
    fun telemetryNotification_isBroadcastWithFullPayload() {
        val payload = ByteArray(20) { (it * 13).toByte() }
        callback.onCharacteristicChanged(callbackGatt, characteristic(GattAttributes.RECOIL_TELEMETRY_UUID), payload)

        assertEquals(1, events.size)
        assertArrayEquals(payload, eventFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE).data)
    }

    @Test
    @Suppress("DEPRECATION")
    fun telemetryNotification_legacyCallbackUsesCharacteristicValue() {
        // Pre-API-33 devices call onCharacteristicChanged(gatt, characteristic) only.
        val payload = byteArrayOf(0x00, 0x05, 0x00, 0x11)
        val c = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        `when`(c.value).thenReturn(payload)
        callback.onCharacteristicChanged(callbackGatt, c)

        assertArrayEquals(payload, eventFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE).data)
    }

    @Test
    fun idCharacteristicRead_broadcastsByte10AsBlasterType() {
        // ID characteristic: bytes 0-1 firmware version (big endian), byte 10 blaster type.
        val id = ByteArray(20)
        id[0] = 0x01
        id[1] = 0x23
        id[10] = 0x01 // rifle
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), id, GATT_SUCCESS)

        assertEquals(0x01.toByte(), eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType)
    }

    @Test
    @Suppress("DEPRECATION")
    fun idCharacteristicRead_legacyCallbackUsesCharacteristicValue() {
        val id = ByteArray(11)
        id[10] = 0x02 // pistol
        val c = characteristic(GattAttributes.RECOIL_ID_UUID)
        `when`(c.value).thenReturn(id)
        callback.onCharacteristicRead(callbackGatt, c, GATT_SUCCESS)

        assertEquals(0x02.toByte(), eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType)
    }

    @Test
    fun idCharacteristicRead_shortPayloadBroadcastsWithoutType() {
        // <= 10 bytes: no type, so the receiver falls back to its pistol default.
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), ByteArray(10), GATT_SUCCESS)

        assertNull(eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType)
    }

    @Test
    fun failedRead_isNotBroadcast() {
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), ByteArray(20), GATT_FAILURE)
        assertTrue(events.isEmpty())
    }

    @Test
    fun notificationFromUnknownCharacteristic_isDropped() {
        callback.onCharacteristicChanged(callbackGatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), ByteArray(20))
        callback.onCharacteristicChanged(callbackGatt, characteristic("00002a19-0000-1000-8000-00805f9b34fb"), ByteArray(1))
        assertTrue(events.isEmpty())
    }

    // ----------------------------------------------------------------------------------------
    // Write / descriptor / discovery completion callbacks
    // ----------------------------------------------------------------------------------------

    @Test
    fun characteristicWrite_alwaysBroadcastsWriteFinished() {
        // Reload sequencing in the app is driven by this signal, so it fires on failure too.
        callback.onCharacteristicWrite(callbackGatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), GATT_SUCCESS)
        callback.onCharacteristicWrite(callbackGatt, characteristic(GattAttributes.RECOIL_CONFIG_UUID), GATT_FAILURE)
        assertEquals(listOf(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED), actions)
    }

    @Test
    fun descriptorWrite_alwaysBroadcastsDescriptorWriteFinished() {
        callback.onDescriptorWrite(callbackGatt, mock(BluetoothGattDescriptor::class.java), GATT_SUCCESS)
        callback.onDescriptorWrite(callbackGatt, mock(BluetoothGattDescriptor::class.java), GATT_FAILURE)
        assertEquals(listOf(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, BluetoothLeService.DESCRIPTOR_WRITE_FINISHED), actions)
    }

    @Test
    fun servicesDiscovered_broadcastOnlyOnSuccess() {
        callback.onServicesDiscovered(callbackGatt, GATT_FAILURE)
        assertTrue(events.isEmpty())
        callback.onServicesDiscovered(callbackGatt, GATT_SUCCESS)
        assertEquals(listOf(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED), actions)
    }

    @Test
    fun disconnect_broadcastsGattDisconnected() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_DISCONNECTED)
        assertEquals(listOf(BluetoothLeService.ACTION_GATT_DISCONNECTED), actions)
    }

    // ----------------------------------------------------------------------------------------
    // Enabling telemetry notifications (CCCD)
    // ----------------------------------------------------------------------------------------

    @Test
    @Suppress("DEPRECATION")
    fun enableTelemetryNotifications_resetsThenEnablesAndWritesCccd() {
        val telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        val cccd = mock(BluetoothGattDescriptor::class.java)
        `when`(telemetry.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))).thenReturn(cccd)

        service.setCharacteristicNotification(telemetry, true)

        val order = inOrder(gatt, cccd)
        order.verify(gatt).setCharacteristicNotification(telemetry, false)
        order.verify(gatt).setCharacteristicNotification(telemetry, true)
        val value = ArgumentCaptor.forClass(ByteArray::class.java)
        order.verify(cccd).setValue(value.capture())
        order.verify(gatt).writeDescriptor(cccd)
        assertArrayEquals("CCCD enable notifications = 01 00", ENABLE_NOTIFICATION, value.value)
    }

    @Test
    @Suppress("DEPRECATION")
    fun disableTelemetryNotifications_writesCccdDisableValue() {
        val telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        val cccd = mock(BluetoothGattDescriptor::class.java)
        `when`(telemetry.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))).thenReturn(cccd)

        service.setCharacteristicNotification(telemetry, false)

        val value = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(cccd).setValue(value.capture())
        assertArrayEquals("CCCD disable notifications = 00 00", DISABLE_NOTIFICATION, value.value)
        verify(gatt).writeDescriptor(cccd)
    }

    @Test
    fun notificationsOnNonTelemetryCharacteristic_doNotTouchCccd() {
        val id = characteristic(GattAttributes.RECOIL_ID_UUID)
        service.setCharacteristicNotification(id, true)
        verify(gatt).setCharacteristicNotification(id, true)
        verify(id, never()).getDescriptor(any(UUID::class.java))
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor::class.java))
    }

    // ----------------------------------------------------------------------------------------
    // Characteristic writes / reads
    // ----------------------------------------------------------------------------------------

    @Test
    @Suppress("DEPRECATION")
    fun writeCharacteristic_preApi33PathSetsValueAndWriteType() {
        // Build.VERSION.SDK_INT is 0 on the JVM, which exercises the legacy (< TIRAMISU) branch.
        val command = characteristic(GattAttributes.RECOIL_COMMAND_UUID)
        val packet = byteArrayOf(0x10, 0x00, 0x80.toByte(), 0x00, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        service.writeCharacteristic(command, packet, WRITE_TYPE_DEFAULT)

        val order = inOrder(command, gatt)
        val value = ArgumentCaptor.forClass(ByteArray::class.java)
        order.verify(command).setValue(value.capture())
        order.verify(command).writeType = WRITE_TYPE_DEFAULT
        order.verify(gatt).writeCharacteristic(command)
        assertArrayEquals(packet, value.value)
    }

    @Test
    @Suppress("DEPRECATION")
    fun writeAndRead_areNoOpsWithoutGatt() {
        service.attachForTest(null, sink, scope)
        val command = characteristic(GattAttributes.RECOIL_COMMAND_UUID)
        service.writeCharacteristic(command, ByteArray(20), WRITE_TYPE_DEFAULT)
        service.readCharacteristic(command)
        service.setCharacteristicNotification(command, true)
        verify(command, never()).setValue(any(ByteArray::class.java))
    }

    @Test
    @Suppress("DEPRECATION")
    fun writeAndRead_ignoreNullCharacteristic() {
        service.writeCharacteristic(null, ByteArray(20), WRITE_TYPE_DEFAULT)
        service.writeCharacteristic(null)
        service.readCharacteristic(null)
        service.setCharacteristicNotification(null, true)
        verify(gatt, never()).writeCharacteristic(any(BluetoothGattCharacteristic::class.java))
        verify(gatt, never()).readCharacteristic(any(BluetoothGattCharacteristic::class.java))
    }

    @Test
    fun readCharacteristic_delegatesToGatt() {
        val id = characteristic(GattAttributes.RECOIL_ID_UUID)
        service.readCharacteristic(id)
        verify(gatt).readCharacteristic(id)
    }

    // ----------------------------------------------------------------------------------------
    // In-process event transport (D8)
    // ----------------------------------------------------------------------------------------

    @Test
    fun events_arePublishedInCallbackOrderWithPayloads() {
        val flowService = BluetoothLeService()
        flowService.attachForTest(gatt, null, scope)
        val received = mutableListOf<GattEvent>()
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            flowService.events.collect { received += it }
        }
        val cb = flowService.getGattCallback()
        val telemetry = RecoilProtocolFixtures.GOLDEN_IDLE.clone()
        val id = ByteArray(20).also { it[10] = 0x01 }

        cb.onServicesDiscovered(callbackGatt, GATT_SUCCESS)
        cb.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), id, GATT_SUCCESS)
        cb.onCharacteristicChanged(callbackGatt, characteristic(GattAttributes.RECOIL_TELEMETRY_UUID), telemetry)
        cb.onCharacteristicWrite(callbackGatt, characteristic(GattAttributes.RECOIL_CONFIG_UUID), GATT_SUCCESS)
        cb.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_DISCONNECTED)

        assertEquals(5, received.size)
        assertEquals(GattEvent.Action(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED), received[0])
        assertEquals(GattEvent.BlasterId(0x01), received[1])
        assertArrayEquals(telemetry, (received[2] as GattEvent.Telemetry).data)
        assertEquals(GattEvent.Action(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED), received[3])
        assertEquals(GattEvent.Action(BluetoothLeService.ACTION_GATT_DISCONNECTED), received[4])
    }

    // ----------------------------------------------------------------------------------------
    // Protocol timing (REFACTOR_DESIGN.md §1.6)
    // ----------------------------------------------------------------------------------------

    @Test
    fun connected_discoversServicesAfterExactly500ms() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_CONNECTED)
        assertEquals(listOf(BluetoothLeService.ACTION_GATT_CONNECTED, START_FOREGROUND), actions)

        advance(499)
        verify(gatt, never()).discoverServices()
        advance(1)
        verify(gatt).discoverServices()
    }

    @Test
    fun startCommand_entersForegroundBeforeAnyGattCall() {
        // B5: startForegroundService() must be answered by startForeground() without waiting
        // for the GATT connection.
        service.onStartCommand(null, 0, 1)
        assertEquals(listOf(START_FOREGROUND), actions)
        verifyNoInteractions(gatt)
    }

    @Test
    fun connected_thenClosedBefore500ms_doesNotDiscover() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_CONNECTED)
        service.close()
        advance(500)
        verify(gatt, never()).discoverServices()
    }

    @Test
    @Suppress("DEPRECATION")
    fun missingCccd_isRetriedOnceAfterExactly300ms() {
        val telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        val cccd = mock(BluetoothGattDescriptor::class.java)
        val cccdUuid = UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG)
        `when`(telemetry.getDescriptor(cccdUuid)).thenReturn(null, cccd)

        service.setCharacteristicNotification(telemetry, true)
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor::class.java))

        advance(299)
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor::class.java))
        advance(1)
        val value = ArgumentCaptor.forClass(ByteArray::class.java)
        verify(cccd).setValue(value.capture())
        verify(gatt).writeDescriptor(cccd)
        assertArrayEquals(ENABLE_NOTIFICATION, value.value)
        advance(10_000)
        verify(telemetry, times(2)).getDescriptor(cccdUuid)
    }

    @Test
    fun missingCccd_stillMissingAfterRetry_givesUp() {
        val telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID)
        val cccdUuid = UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG)
        `when`(telemetry.getDescriptor(cccdUuid)).thenReturn(null)

        service.setCharacteristicNotification(telemetry, true)
        advance(10_000)
        verify(telemetry, times(2)).getDescriptor(cccdUuid)
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor::class.java))
    }

    /**
     * Writes a public static final reference field. Used only to replace the null placeholders
     * that the android.jar test stubs provide for BluetoothGattDescriptor's notification values.
     */
    private object StaticFieldWriter {
        // sun.misc.Unsafe is not on the android.jar compile classpath, so it is reached reflectively.
        fun get(owner: Class<*>, name: String): Any? = owner.getDeclaredField(name).get(null)

        fun set(owner: Class<*>, name: String, value: Any?) {
            val target = owner.getDeclaredField(name)
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe: Field = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe = theUnsafe.get(null)
            val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, target)
            val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, target) as Long
            unsafeClass.getMethod("putObjectVolatile", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, base, offset, value)
        }
    }

    companion object {
        private const val GATT_SUCCESS = 0
        private const val GATT_FAILURE = 0x101
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2
        private const val WRITE_TYPE_DEFAULT = 2
        private const val START_FOREGROUND = "startForeground"

        /** Real framework values of BluetoothGattDescriptor.ENABLE/DISABLE_NOTIFICATION_VALUE. */
        private val ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
        private val DISABLE_NOTIFICATION = byteArrayOf(0x00, 0x00)
    }
}
