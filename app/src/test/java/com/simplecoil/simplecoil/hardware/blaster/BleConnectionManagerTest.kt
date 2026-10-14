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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.ServiceConnection
import com.simplecoil.simplecoil.BluetoothLeService
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Scan / bind / give-up behavior moved from FullscreenActivity: unfiltered scan, ScanMatcher
 * rules, the 30 s give-up (§1.6) in virtual time, and the service hand-off to BlasterManager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleConnectionManagerTest {

    private val scope = TestScope()
    private lateinit var blasterManager: BlasterManager
    private lateinit var manager: BleConnectionManager
    private lateinit var adapter: BluetoothAdapter
    private lateinit var scanner: BluetoothLeScanner
    private lateinit var service: BluetoothLeService
    private val bound = mutableListOf<ServiceConnection>()
    private val unbound = mutableListOf<ServiceConnection>()
    private val saved = mutableListOf<String>()
    private val events = mutableListOf<BleConnectionEvent>()
    private var permission = true

    private var starts = 0
    private var stops = 0

    private val binder = object : BleConnectionManager.ServiceBinder {
        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }

        override fun bind(connection: ServiceConnection) {
            bound += connection
        }

        override fun unbind(connection: ServiceConnection) {
            unbound += connection
        }
    }

    @Before
    fun setUp() {
        blasterManager = BlasterManager(scope)
        manager = BleConnectionManager(mock(Context::class.java), blasterManager, scope, binder, { permission }, { saved += it })
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            manager.events.collect { events += it }
        }
        scope.testScheduler.runCurrent() // start the manager's BlasterEvent subscription
        adapter = mock(BluetoothAdapter::class.java)
        scanner = mock(BluetoothLeScanner::class.java)
        `when`(adapter.bluetoothLeScanner).thenReturn(scanner)
        service = mock(BluetoothLeService::class.java)
        `when`(service.events).thenReturn(MutableSharedFlow())
        `when`(service.initialize()).thenReturn(true)
    }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    private fun device(name: String?, address: String): BluetoothDevice {
        val d = mock(BluetoothDevice::class.java)
        `when`(d.name).thenReturn(name)
        `when`(d.address).thenReturn(address)
        return d
    }

    private fun scanResult(device: BluetoothDevice): ScanResult {
        val r = mock(ScanResult::class.java)
        `when`(r.device).thenReturn(device)
        return r
    }

    private fun onServiceConnected() {
        val localBinder = mock(BluetoothLeService.LocalBinder::class.java)
        `when`(localBinder.getService()).thenReturn(service)
        bound.last().onServiceConnected(null, localBinder)
    }

    @Test
    fun startScan_scansUnfilteredWithOneCallback() {
        assertEquals(BleConnectionManager.ScanStart.STARTED, manager.startScan(adapter, ""))
        verify(scanner).startScan(manager.scanCallback)
        assertTrue(manager.isScanning)
        assertEquals(BleConnectionState.Scanning, manager.connectionState.value)
    }

    @Test
    fun startScan_securityException_reportsPermissionDeniedAndDoesNotArmTimeout() {
        `when`(scanner.startScan(any(ScanCallback::class.java))).thenThrow(SecurityException("no"))
        assertEquals(BleConnectionManager.ScanStart.PERMISSION_DENIED, manager.startScan(adapter, ""))
        assertFalse(manager.isScanning)
        advance(60_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun noScannerStillRunsTheGiveUp() {
        `when`(adapter.bluetoothLeScanner).thenReturn(null)
        manager.startScan(adapter, "")
        assertTrue(manager.isScanning)
        advance(30_000)
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.ScanTimedOut), events)
    }

    @Test
    fun noBlasterFound_givesUpAfterExactly30s() {
        manager.startScan(adapter, "")
        advance(29_999)
        assertTrue(events.isEmpty())
        verify(scanner, never()).stopScan(any(ScanCallback::class.java))
        advance(1)
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.ScanTimedOut), events)
        verify(scanner).stopScan(manager.scanCallback)
        assertFalse(manager.isScanning)
        assertEquals(BleConnectionState.Disconnected(BleConnectionState.Disconnected.Reason.ScanTimeout),
            manager.connectionState.value)
    }

    @Test
    fun cancelledGiveUp_neverFires() {
        manager.startScan(adapter, "")
        manager.cancelScanTimeout()
        advance(60_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun srg1Device_withNoSavedAddress_isBoundConnectedAndSaved() {
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("Phone", "11:11:11:11:11:11")))
        assertTrue(bound.isEmpty())

        manager.scanCallback.onScanResult(0, scanResult(device("SRG1-1234", "AA:BB:CC:DD:EE:FF")))
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.ScanMatched("AA:BB:CC:DD:EE:FF")), events)
        verify(scanner).stopScan(manager.scanCallback)
        assertFalse(manager.isScanning)
        assertEquals(1, bound.size)
        assertEquals(BleConnectionState.Connecting("AA:BB:CC:DD:EE:FF"), manager.connectionState.value)

        onServiceConnected()
        verify(service).connect("AA:BB:CC:DD:EE:FF")
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), saved)
        assertTrue(manager.isConnected)

        // Bound in time: the give-up does nothing.
        advance(30_000)
        assertEquals(1, events.size)
    }

    @Test
    fun serviceIsAttachedToBlasterManagerBeforeConnecting() {
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        val order = org.mockito.Mockito.inOrder(service)
        onServiceConnected()
        order.verify(service).events
        order.verify(service).connect("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun savedAddress_matchesOnlyThatDevice() {
        manager.startScan(adapter, "AA:BB:CC:DD:EE:FF")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "11:22:33:44:55:66")))
        assertTrue(bound.isEmpty())
        manager.scanCallback.onScanResult(0, scanResult(device("Other", "AA:BB:CC:DD:EE:FF")))
        assertEquals(1, bound.size)
    }

    @Test
    fun withoutConnectPermission_nothingMatches() {
        permission = false
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        assertTrue(bound.isEmpty())
    }

    @Test
    fun batchResults_stopAtFirstMatch() {
        manager.startScan(adapter, "")
        manager.scanCallback.onBatchScanResults(listOf(
            scanResult(device("Phone", "11:11:11:11:11:11")),
            scanResult(device("SRG1", "AA:AA:AA:AA:AA:AA")),
            scanResult(device("SRG1", "BB:BB:BB:BB:BB:BB")),
        ))
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.ScanMatched("AA:AA:AA:AA:AA:AA")), events)
        assertEquals(1, bound.size)
    }

    @Test
    fun initializeFailure_isReportedAndConnectStillAttempted() {
        `when`(service.initialize()).thenReturn(false)
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        onServiceConnected()
        assertEquals(BleConnectionEvent.InitializeFailed, events.last())
        verify(service).connect("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun gattConnected_marksConnectedSoGiveUpDoesNotFire() {
        manager.startScan(adapter, "")
        blasterManager.onEvent(BluetoothLeService.ACTION_GATT_CONNECTED)
        scope.testScheduler.runCurrent()
        assertTrue(manager.isConnected)
        advance(30_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun reset_disconnectsClosesUnbindsAndStops() {
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        onServiceConnected()
        manager.reset()
        verify(service).disconnect()
        verify(service).close()
        assertEquals(bound, unbound)
        // Stopped, or its "Connected to Blaster" notification outlives the connection.
        assertEquals(1, stops)
        assertFalse(manager.isConnected)
        assertEquals(BleConnectionState.Idle, manager.connectionState.value)
        manager.reconnect("AA:BB:CC:DD:EE:FF")
        verify(service).connect("AA:BB:CC:DD:EE:FF") // only the initial connect
    }

    @Test
    fun reconnect_usesBoundServiceAndIgnoresEmptyAddress() {
        manager.reconnect("AA:BB:CC:DD:EE:FF") // nothing bound: no-op
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        onServiceConnected()
        manager.reconnect("")
        manager.reconnect(null)
        manager.reconnect("AA:BB:CC:DD:EE:FF")
        verify(service, org.mockito.Mockito.times(2)).connect("AA:BB:CC:DD:EE:FF")
    }

    @Test
    fun rescanWhileBound_reusesTheBindingAndConnectsTheNewMatch() {
        // B4: a reconnect after a lost link used to bind again on every scan match without
        // unbinding, leaving one extra binding per reconnect.
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        onServiceConnected()
        manager.startScan(adapter, "AA:BB:CC:DD:EE:FF")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        assertEquals(1, bound.size)
        assertEquals(2, starts)
        verify(service, org.mockito.Mockito.times(2)).connect("AA:BB:CC:DD:EE:FF")
        verify(service, org.mockito.Mockito.times(1)).initialize()
        assertEquals(2, saved.size)

        manager.reset()
        assertEquals(bound, unbound)
    }

    @Test
    fun matchWhileBindingIsPending_connectsLatestAddressOnBind() {
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:AA:AA:AA:AA:AA")))
        manager.startScan(adapter, "BB:BB:BB:BB:BB:BB")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "BB:BB:BB:BB:BB:BB")))
        assertEquals(1, bound.size)
        onServiceConnected()
        verify(service).connect("BB:BB:BB:BB:BB:BB")
        verify(service, never()).connect("AA:AA:AA:AA:AA:AA")
    }

    // ----------------------------------------------------------------------------------------
    // Telemetry watchdog (§1.6: 5 s tick; B2)
    // ----------------------------------------------------------------------------------------

    private fun gattConnected() {
        blasterManager.onEvent(BluetoothLeService.ACTION_GATT_CONNECTED)
        scope.testScheduler.runCurrent()
    }

    private fun telemetry() {
        blasterManager.onTelemetry(RecoilProtocolFixtures.GOLDEN_IDLE)
        scope.testScheduler.runCurrent()
    }

    @Test
    fun watchdog_waitsForFirstTelemetry() {
        gattConnected()
        advance(60_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun watchdog_firesOnFirstTickWithoutTelemetry() {
        gattConnected()
        advance(1_000)
        telemetry() // communicating from here
        advance(4_000) // tick at 5 s: packet seen, reset
        assertTrue(events.isEmpty())
        advance(4_999)
        assertTrue(events.isEmpty())
        advance(1) // tick at 10 s: nothing since 5 s
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.WatchdogTimedOut), events)
        assertEquals(BleConnectionState.Disconnected(BleConnectionState.Disconnected.Reason.WatchdogTimeout),
            manager.connectionState.value)
        advance(60_000)
        assertEquals(1, events.size)
    }

    @Test
    fun watchdog_keepsQuietWhileTelemetryFlows_evenWithNoUiCollecting() {
        // B2: telemetry reaches the watchdog straight from BlasterManager, so a paused activity
        // (no UI collector at all here) can't starve it into a false disconnect.
        gattConnected()
        repeat(60) {
            telemetry()
            advance(1_000)
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun watchdog_stopsWhenConnectionIsReset() {
        gattConnected()
        telemetry()
        manager.reset()
        advance(60_000)
        assertTrue(events.isEmpty())
    }

    @Test
    fun watchdog_restartsOnReconnectWithoutStackingLoops() {
        gattConnected()
        telemetry()
        advance(2_000)
        gattConnected() // reconnect: one watchdog, re-armed, waiting for telemetry again
        advance(60_000)
        assertTrue(events.isEmpty())
        telemetry()
        advance(10_000)
        assertEquals(listOf<BleConnectionEvent>(BleConnectionEvent.WatchdogTimedOut), events)
    }

    @Test
    fun connectionState_followsDiscoveryTelemetryAndLinkLoss() {
        manager.startScan(adapter, "")
        manager.scanCallback.onScanResult(0, scanResult(device("SRG1", "AA:BB:CC:DD:EE:FF")))
        onServiceConnected()
        blasterManager.onEvent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
        scope.testScheduler.runCurrent()
        assertEquals(BleConnectionState.ServicesDiscovered("AA:BB:CC:DD:EE:FF"), manager.connectionState.value)
        blasterManager.onBlasterId(GattProtocolHandler.BLASTER_TYPE_RIFLE)
        blasterManager.onTelemetry(RecoilProtocolFixtures.GOLDEN_IDLE)
        scope.testScheduler.runCurrent()
        assertEquals(BleConnectionState.Communicating("AA:BB:CC:DD:EE:FF", GattProtocolHandler.BLASTER_TYPE_RIFLE),
            manager.connectionState.value)
        blasterManager.onEvent(BluetoothLeService.ACTION_GATT_DISCONNECTED)
        scope.testScheduler.runCurrent()
        assertEquals(BleConnectionState.Disconnected(BleConnectionState.Disconnected.Reason.LinkLost),
            manager.connectionState.value)
    }
}
