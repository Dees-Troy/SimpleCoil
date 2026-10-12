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

package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import com.simplecoil.simplecoil.hardware.blaster.FakeScheduler;
import com.simplecoil.simplecoil.hardware.blaster.GattEventSink;
import com.simplecoil.simplecoil.hardware.blaster.GattIo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolates BluetoothLeService (the only class that touches BluetoothGatt directly) from the
 * Android framework so the GATT plumbing can be verified on the JVM:
 *
 *  - which notifications/reads are routed to which internal broadcast, and with what payload
 *  - the CCCD descriptor write that turns on telemetry notifications
 *  - how characteristic writes are handed to BluetoothGatt
 *
 * GATT calls go to a mocked GattIo, events to a recording GattEventSink and protocol delays to a
 * virtual-time scheduler, all injected through BluetoothLeService.attachForTest().
 */
public class BluetoothLeServiceGattTest {

    private static final int GATT_SUCCESS = 0;
    private static final int GATT_FAILURE = 0x101;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 2;
    private static final int WRITE_TYPE_DEFAULT = 2;

    /** Real framework values of BluetoothGattDescriptor.ENABLE/DISABLE_NOTIFICATION_VALUE. */
    private static final byte[] ENABLE_NOTIFICATION = {0x01, 0x00};
    private static final byte[] DISABLE_NOTIFICATION = {0x00, 0x00};

    private BluetoothLeService service;
    /** Passed to callbacks as the framework would; the service itself talks to {@link #gatt}. */
    private final BluetoothGatt callbackGatt = mock(BluetoothGatt.class);
    private GattIo gatt;
    private BluetoothGattCallback callback;
    private FakeScheduler scheduler;

    /** One event delivered to the sink: the action plus its payload, if any. */
    private static final class Event {
        final String action;
        final byte[] data;
        final Byte blasterType;

        Event(String action, byte[] data, Byte blasterType) {
            this.action = action;
            this.data = data;
            this.blasterType = blasterType;
        }
    }

    private final List<Event> events = new ArrayList<>();
    /** Action of every delivered event, in order. */
    private final List<String> actions = new ArrayList<>();

    private final GattEventSink sink = new GattEventSink() {
        @Override
        public void onEvent(String action) {
            record(new Event(action, null, null));
        }

        @Override
        public void onTelemetry(byte[] data) {
            record(new Event(BluetoothLeService.TELEMETRY_DATA_AVAILABLE, data, null));
        }

        @Override
        public void onBlasterId(Byte blasterType) {
            record(new Event(BluetoothLeService.ID_DATA_AVAILABLE, null, blasterType));
        }

        private void record(Event e) {
            events.add(e);
            actions.add(e.action);
        }
    };

    private Object savedEnable;
    private Object savedDisable;

    @Before
    public void setUp() throws Exception {
        service = new BluetoothLeService() {
            @Override
            public void startAsForeground(String statusText) {
                // Building the notification needs a real Context; record the call instead.
                actions.add("startForeground");
            }
        };
        gatt = mock(GattIo.class);
        scheduler = new FakeScheduler();
        service.attachForTest(gatt, sink, scheduler);
        callback = service.getGattCallback();

        // The android.jar stubs leave these arrays null; install the real values so the CCCD
        // payload can be asserted.
        savedEnable = StaticFieldWriter.get(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE");
        savedDisable = StaticFieldWriter.get(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE");
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE", ENABLE_NOTIFICATION);
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE", DISABLE_NOTIFICATION);
    }

    @After
    public void tearDown() throws Exception {
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE", savedEnable);
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE", savedDisable);
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        BluetoothGattCharacteristic c = mock(BluetoothGattCharacteristic.class);
        when(c.getUuid()).thenReturn(UUID.fromString(uuid));
        return c;
    }

    /** Returns the event delivered for the given action. */
    private Event eventFor(String action) {
        int index = actions.indexOf(action);
        assertTrue("expected event " + action + " but got " + actions, index >= 0);
        return events.get(index);
    }

    // ----------------------------------------------------------------------------------------
    // Notification / read routing
    // ----------------------------------------------------------------------------------------

    @Test
    public void telemetryNotification_isBroadcastWithFullPayload() {
        byte[] payload = new byte[20];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 13);
        callback.onCharacteristicChanged(callbackGatt, characteristic(GattAttributes.RECOIL_TELEMETRY_UUID), payload);

        assertEquals(1, actions.size());
        assertArrayEquals(payload, eventFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE).data);
    }

    @Test
    public void telemetryNotification_legacyCallbackUsesCharacteristicValue() {
        // Pre-API-33 devices call onCharacteristicChanged(gatt, characteristic) only.
        byte[] payload = {0x00, 0x05, 0x00, 0x11};
        BluetoothGattCharacteristic c = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        when(c.getValue()).thenReturn(payload);
        callback.onCharacteristicChanged(callbackGatt, c);

        assertArrayEquals(payload, eventFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE).data);
    }

    @Test
    public void idCharacteristicRead_broadcastsByte10AsBlasterType() {
        // ID characteristic: bytes 0-1 firmware version (big endian), byte 10 blaster type.
        byte[] id = new byte[20];
        id[0] = 0x01;
        id[1] = 0x23;
        id[10] = 0x01; // rifle
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), id, GATT_SUCCESS);

        assertEquals(Byte.valueOf((byte) 0x01), eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType);
    }

    @Test
    public void idCharacteristicRead_legacyCallbackUsesCharacteristicValue() {
        byte[] id = new byte[11];
        id[10] = 0x02; // pistol
        BluetoothGattCharacteristic c = characteristic(GattAttributes.RECOIL_ID_UUID);
        when(c.getValue()).thenReturn(id);
        callback.onCharacteristicRead(callbackGatt, c, GATT_SUCCESS);

        assertEquals(Byte.valueOf((byte) 0x02), eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType);
    }

    @Test
    public void idCharacteristicRead_shortPayloadBroadcastsWithoutType() {
        // <= 10 bytes: no type extra, so the receiver falls back to its pistol default.
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), new byte[10], GATT_SUCCESS);

        assertNull(eventFor(BluetoothLeService.ID_DATA_AVAILABLE).blasterType);
    }

    @Test
    public void failedRead_isNotBroadcast() {
        callback.onCharacteristicRead(callbackGatt, characteristic(GattAttributes.RECOIL_ID_UUID), new byte[20], GATT_FAILURE);
        assertTrue(actions.isEmpty());
    }

    @Test
    public void notificationFromUnknownCharacteristic_isDropped() {
        callback.onCharacteristicChanged(callbackGatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), new byte[20]);
        callback.onCharacteristicChanged(callbackGatt, characteristic("00002a19-0000-1000-8000-00805f9b34fb"), new byte[1]);
        assertTrue(actions.isEmpty());
    }

    // ----------------------------------------------------------------------------------------
    // Write / descriptor / discovery completion callbacks
    // ----------------------------------------------------------------------------------------

    @Test
    public void characteristicWrite_alwaysBroadcastsWriteFinished() {
        // Reload sequencing in the app is driven by this signal, so it fires on failure too.
        callback.onCharacteristicWrite(callbackGatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), GATT_SUCCESS);
        callback.onCharacteristicWrite(callbackGatt, characteristic(GattAttributes.RECOIL_CONFIG_UUID), GATT_FAILURE);
        assertEquals(2, actions.size());
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, actions.get(0));
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, actions.get(1));
    }

    @Test
    public void descriptorWrite_alwaysBroadcastsDescriptorWriteFinished() {
        callback.onDescriptorWrite(callbackGatt, mock(BluetoothGattDescriptor.class), GATT_SUCCESS);
        callback.onDescriptorWrite(callbackGatt, mock(BluetoothGattDescriptor.class), GATT_FAILURE);
        assertEquals(2, actions.size());
        assertEquals(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, actions.get(0));
        assertEquals(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, actions.get(1));
    }

    @Test
    public void servicesDiscovered_broadcastOnlyOnSuccess() {
        callback.onServicesDiscovered(callbackGatt, GATT_FAILURE);
        assertTrue(actions.isEmpty());
        callback.onServicesDiscovered(callbackGatt, GATT_SUCCESS);
        assertEquals(1, actions.size());
        assertEquals(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED, actions.get(0));
    }

    @Test
    public void disconnect_broadcastsGattDisconnected() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_DISCONNECTED);
        assertEquals(1, actions.size());
        assertEquals(BluetoothLeService.ACTION_GATT_DISCONNECTED, actions.get(0));
    }

    // ----------------------------------------------------------------------------------------
    // Enabling telemetry notifications (CCCD)
    // ----------------------------------------------------------------------------------------

    @Test
    public void enableTelemetryNotifications_resetsThenEnablesAndWritesCccd() {
        BluetoothGattCharacteristic telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        BluetoothGattDescriptor cccd = mock(BluetoothGattDescriptor.class);
        when(telemetry.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))).thenReturn(cccd);

        service.setCharacteristicNotification(telemetry, true);

        InOrder order = inOrder(gatt, cccd);
        order.verify(gatt).setCharacteristicNotification(telemetry, false);
        order.verify(gatt).setCharacteristicNotification(telemetry, true);
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        order.verify(cccd).setValue(value.capture());
        order.verify(gatt).writeDescriptor(cccd);
        assertArrayEquals("CCCD enable notifications = 01 00", ENABLE_NOTIFICATION, value.getValue());
    }

    @Test
    public void disableTelemetryNotifications_writesCccdDisableValue() {
        BluetoothGattCharacteristic telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        BluetoothGattDescriptor cccd = mock(BluetoothGattDescriptor.class);
        when(telemetry.getDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG))).thenReturn(cccd);

        service.setCharacteristicNotification(telemetry, false);

        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(cccd).setValue(value.capture());
        assertArrayEquals("CCCD disable notifications = 00 00", DISABLE_NOTIFICATION, value.getValue());
        verify(gatt).writeDescriptor(cccd);
    }

    @Test
    public void notificationsOnNonTelemetryCharacteristic_doNotTouchCccd() {
        BluetoothGattCharacteristic id = characteristic(GattAttributes.RECOIL_ID_UUID);
        service.setCharacteristicNotification(id, true);
        verify(gatt).setCharacteristicNotification(id, true);
        verify(id, never()).getDescriptor(any(UUID.class));
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor.class));
    }

    // ----------------------------------------------------------------------------------------
    // Characteristic writes / reads
    // ----------------------------------------------------------------------------------------

    @Test
    public void writeCharacteristic_preApi33PathSetsValueAndWriteType() {
        // Build.VERSION.SDK_INT is 0 on the JVM, which exercises the legacy (< TIRAMISU) branch.
        BluetoothGattCharacteristic command = characteristic(GattAttributes.RECOIL_COMMAND_UUID);
        byte[] packet = {0x10, 0x00, (byte) 0x80, 0x00, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        service.writeCharacteristic(command, packet, WRITE_TYPE_DEFAULT);

        InOrder order = inOrder(command, gatt);
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        order.verify(command).setValue(value.capture());
        order.verify(command).setWriteType(WRITE_TYPE_DEFAULT);
        order.verify(gatt).writeCharacteristic(command);
        assertArrayEquals(packet, value.getValue());
    }

    @Test
    public void writeAndRead_areNoOpsWithoutGatt() {
        service.attachForTest(null, sink, scheduler);
        BluetoothGattCharacteristic command = characteristic(GattAttributes.RECOIL_COMMAND_UUID);
        service.writeCharacteristic(command, new byte[20], WRITE_TYPE_DEFAULT);
        service.readCharacteristic(command);
        service.setCharacteristicNotification(command, true);
        verify(command, never()).setValue(any(byte[].class));
    }

    @Test
    public void writeAndRead_ignoreNullCharacteristic() {
        service.writeCharacteristic(null, new byte[20], WRITE_TYPE_DEFAULT);
        service.writeCharacteristic(null);
        service.readCharacteristic(null);
        service.setCharacteristicNotification(null, true);
        verify(gatt, never()).writeCharacteristic(any(BluetoothGattCharacteristic.class));
        verify(gatt, never()).readCharacteristic(any(BluetoothGattCharacteristic.class));
    }

    // ----------------------------------------------------------------------------------------
    // Protocol timing (REFACTOR_DESIGN.md §1.6)
    // ----------------------------------------------------------------------------------------

    @Test
    public void connected_discoversServicesAfterExactly500ms() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_CONNECTED);
        assertEquals(java.util.Arrays.asList(BluetoothLeService.ACTION_GATT_CONNECTED, "startForeground"), actions);

        scheduler.advanceBy(499);
        verify(gatt, never()).discoverServices();
        scheduler.advanceBy(1);
        verify(gatt).discoverServices();
    }

    @Test
    public void connected_thenClosedBefore500ms_doesNotDiscover() {
        callback.onConnectionStateChange(callbackGatt, GATT_SUCCESS, STATE_CONNECTED);
        service.close();
        scheduler.advanceBy(500);
        verify(gatt, never()).discoverServices();
    }

    @Test
    public void missingCccd_isRetriedOnceAfterExactly300ms() {
        BluetoothGattCharacteristic telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        BluetoothGattDescriptor cccd = mock(BluetoothGattDescriptor.class);
        UUID cccdUuid = UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
        when(telemetry.getDescriptor(cccdUuid)).thenReturn(null, cccd);

        service.setCharacteristicNotification(telemetry, true);
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor.class));

        scheduler.advanceBy(299);
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor.class));
        scheduler.advanceBy(1);
        ArgumentCaptor<byte[]> value = ArgumentCaptor.forClass(byte[].class);
        verify(cccd).setValue(value.capture());
        verify(gatt).writeDescriptor(cccd);
        assertArrayEquals(ENABLE_NOTIFICATION, value.getValue());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void missingCccd_stillMissingAfterRetry_givesUp() {
        BluetoothGattCharacteristic telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        UUID cccdUuid = UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG);
        when(telemetry.getDescriptor(cccdUuid)).thenReturn(null);

        service.setCharacteristicNotification(telemetry, true);
        scheduler.advanceBy(10_000);
        verify(telemetry, times(2)).getDescriptor(cccdUuid);
        verify(gatt, never()).writeDescriptor(any(BluetoothGattDescriptor.class));
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    public void readCharacteristic_delegatesToGatt() {
        BluetoothGattCharacteristic id = characteristic(GattAttributes.RECOIL_ID_UUID);
        service.readCharacteristic(id);
        verify(gatt).readCharacteristic(id);
    }

    // ----------------------------------------------------------------------------------------

    /**
     * Writes a public static final reference field. Used only to replace the null placeholders
     * that the android.jar test stubs provide for BluetoothGattDescriptor's notification values.
     */
    private static final class StaticFieldWriter {
        // sun.misc.Unsafe is not on the android.jar compile classpath, so it is reached reflectively.
        static Object get(Class<?> owner, String name) throws Exception {
            return owner.getDeclaredField(name).get(null);
        }

        static void set(Class<?> owner, String name, Object value) throws Exception {
            Field target = owner.getDeclaredField(name);
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, target);
            long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, target);
            unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, value);
        }
    }
}
