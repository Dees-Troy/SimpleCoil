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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
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
 * BluetoothGatt / BluetoothAdapter / characteristics are Mockito mocks injected via reflection.
 * Outgoing Intents are intercepted with Mockito construction mocking.
 */
public class BluetoothLeServiceGattTest {

    private static final int GATT_SUCCESS = 0;
    private static final int GATT_FAILURE = 0x101;
    private static final int STATE_DISCONNECTED = 0;
    private static final int WRITE_TYPE_DEFAULT = 2;

    /** Real framework values of BluetoothGattDescriptor.ENABLE/DISABLE_NOTIFICATION_VALUE. */
    private static final byte[] ENABLE_NOTIFICATION = {0x01, 0x00};
    private static final byte[] DISABLE_NOTIFICATION = {0x00, 0x00};

    private BluetoothLeService service;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;

    private MockedConstruction<Intent> intentConstruction;
    /** Action string of every Intent constructed with new Intent(String), and the mock itself. */
    private final List<String> actions = new ArrayList<>();
    private final List<Intent> actionIntents = new ArrayList<>();

    private Object savedEnable;
    private Object savedDisable;

    @Before
    public void setUp() throws Exception {
        service = new BluetoothLeService();
        gatt = mock(BluetoothGatt.class);
        setField(service, "mBluetoothAdapter", mock(BluetoothAdapter.class));
        setField(service, "mBluetoothGatt", gatt);
        callback = (BluetoothGattCallback) getField(service, "mGattCallback");

        intentConstruction = mockConstruction(Intent.class, (mock, context) -> {
            if (context.arguments().size() == 1 && context.arguments().get(0) instanceof String) {
                actions.add((String) context.arguments().get(0));
                actionIntents.add(mock);
            }
        });

        // The android.jar stubs leave these arrays null; install the real values so the CCCD
        // payload can be asserted.
        savedEnable = StaticFieldWriter.get(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE");
        savedDisable = StaticFieldWriter.get(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE");
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE", ENABLE_NOTIFICATION);
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE", DISABLE_NOTIFICATION);
    }

    @After
    public void tearDown() throws Exception {
        intentConstruction.close();
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "ENABLE_NOTIFICATION_VALUE", savedEnable);
        StaticFieldWriter.set(BluetoothGattDescriptor.class, "DISABLE_NOTIFICATION_VALUE", savedDisable);
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        BluetoothGattCharacteristic c = mock(BluetoothGattCharacteristic.class);
        when(c.getUuid()).thenReturn(UUID.fromString(uuid));
        return c;
    }

    /** Returns the Intent constructed for the given broadcast action. */
    private Intent intentFor(String action) {
        int index = actions.indexOf(action);
        assertTrue("expected broadcast " + action + " but got " + actions, index >= 0);
        return actionIntents.get(index);
    }

    // ----------------------------------------------------------------------------------------
    // Notification / read routing
    // ----------------------------------------------------------------------------------------

    @Test
    public void telemetryNotification_isBroadcastWithFullPayload() {
        byte[] payload = new byte[20];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 13);
        callback.onCharacteristicChanged(gatt, characteristic(GattAttributes.RECOIL_TELEMETRY_UUID), payload);

        assertEquals(1, actions.size());
        Intent intent = intentFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(intent).putExtra(eq(BluetoothLeService.EXTRA_DATA), captor.capture());
        assertArrayEquals(payload, captor.getValue());
    }

    @Test
    public void telemetryNotification_legacyCallbackUsesCharacteristicValue() {
        // Pre-API-33 devices call onCharacteristicChanged(gatt, characteristic) only.
        byte[] payload = {0x00, 0x05, 0x00, 0x11};
        BluetoothGattCharacteristic c = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        when(c.getValue()).thenReturn(payload);
        callback.onCharacteristicChanged(gatt, c);

        Intent intent = intentFor(BluetoothLeService.TELEMETRY_DATA_AVAILABLE);
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(intent).putExtra(eq(BluetoothLeService.EXTRA_DATA), captor.capture());
        assertArrayEquals(payload, captor.getValue());
    }

    @Test
    public void idCharacteristicRead_broadcastsByte10AsBlasterType() {
        // ID characteristic: bytes 0-1 firmware version (big endian), byte 10 blaster type.
        byte[] id = new byte[20];
        id[0] = 0x01;
        id[1] = 0x23;
        id[10] = 0x01; // rifle
        callback.onCharacteristicRead(gatt, characteristic(GattAttributes.RECOIL_ID_UUID), id, GATT_SUCCESS);

        Intent intent = intentFor(BluetoothLeService.ID_DATA_AVAILABLE);
        verify(intent).putExtra(BluetoothLeService.EXTRA_DATA, (byte) 0x01);
    }

    @Test
    public void idCharacteristicRead_legacyCallbackUsesCharacteristicValue() {
        byte[] id = new byte[11];
        id[10] = 0x02; // pistol
        BluetoothGattCharacteristic c = characteristic(GattAttributes.RECOIL_ID_UUID);
        when(c.getValue()).thenReturn(id);
        callback.onCharacteristicRead(gatt, c, GATT_SUCCESS);

        Intent intent = intentFor(BluetoothLeService.ID_DATA_AVAILABLE);
        verify(intent).putExtra(BluetoothLeService.EXTRA_DATA, (byte) 0x02);
    }

    @Test
    public void idCharacteristicRead_shortPayloadBroadcastsWithoutType() {
        // <= 10 bytes: no type extra, so the receiver falls back to its pistol default.
        callback.onCharacteristicRead(gatt, characteristic(GattAttributes.RECOIL_ID_UUID), new byte[10], GATT_SUCCESS);

        Intent intent = intentFor(BluetoothLeService.ID_DATA_AVAILABLE);
        verify(intent, never()).putExtra(anyString(), anyByte());
    }

    @Test
    public void failedRead_isNotBroadcast() {
        callback.onCharacteristicRead(gatt, characteristic(GattAttributes.RECOIL_ID_UUID), new byte[20], GATT_FAILURE);
        assertTrue(actions.isEmpty());
    }

    @Test
    public void notificationFromUnknownCharacteristic_isDropped() {
        callback.onCharacteristicChanged(gatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), new byte[20]);
        callback.onCharacteristicChanged(gatt, characteristic("00002a19-0000-1000-8000-00805f9b34fb"), new byte[1]);
        assertTrue(actions.isEmpty());
    }

    // ----------------------------------------------------------------------------------------
    // Write / descriptor / discovery completion callbacks
    // ----------------------------------------------------------------------------------------

    @Test
    public void characteristicWrite_alwaysBroadcastsWriteFinished() {
        // Reload sequencing in the app is driven by this signal, so it fires on failure too.
        callback.onCharacteristicWrite(gatt, characteristic(GattAttributes.RECOIL_COMMAND_UUID), GATT_SUCCESS);
        callback.onCharacteristicWrite(gatt, characteristic(GattAttributes.RECOIL_CONFIG_UUID), GATT_FAILURE);
        assertEquals(2, actions.size());
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, actions.get(0));
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, actions.get(1));
    }

    @Test
    public void descriptorWrite_alwaysBroadcastsDescriptorWriteFinished() {
        callback.onDescriptorWrite(gatt, mock(BluetoothGattDescriptor.class), GATT_SUCCESS);
        callback.onDescriptorWrite(gatt, mock(BluetoothGattDescriptor.class), GATT_FAILURE);
        assertEquals(2, actions.size());
        assertEquals(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, actions.get(0));
        assertEquals(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, actions.get(1));
    }

    @Test
    public void servicesDiscovered_broadcastOnlyOnSuccess() {
        callback.onServicesDiscovered(gatt, GATT_FAILURE);
        assertTrue(actions.isEmpty());
        callback.onServicesDiscovered(gatt, GATT_SUCCESS);
        assertEquals(1, actions.size());
        assertEquals(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED, actions.get(0));
    }

    @Test
    public void disconnect_broadcastsGattDisconnected() {
        callback.onConnectionStateChange(gatt, GATT_SUCCESS, STATE_DISCONNECTED);
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
    public void writeAndRead_areNoOpsWithoutGatt() throws Exception {
        setField(service, "mBluetoothGatt", null);
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

    @Test
    public void readCharacteristic_delegatesToGatt() {
        BluetoothGattCharacteristic id = characteristic(GattAttributes.RECOIL_ID_UUID);
        service.readCharacteristic(id);
        verify(gatt).readCharacteristic(id);
    }

    // ----------------------------------------------------------------------------------------

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

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
