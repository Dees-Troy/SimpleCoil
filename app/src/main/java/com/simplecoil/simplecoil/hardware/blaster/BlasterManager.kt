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
import android.util.Log
import com.simplecoil.simplecoil.BluetoothLeService
import com.simplecoil.simplecoil.GattAttributes
import com.simplecoil.simplecoil.domain.session.BatteryLevelPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the blaster's GATT characteristics: writes command/config packets (built only by
 * [GattProtocolHandler]) with the rolling command id, and turns GATT events into [BlasterEvent]s.
 *
 * One instance per process, owned by the application container. [scope] runs the protocol delay
 * after discovery; the app passes a main-thread scope, tests a virtual-time one.
 */
class BlasterManager(private val scope: CoroutineScope) : GattEventSink {

    private val _events = MutableSharedFlow<BlasterEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Ordered event stream. Not replayed: collect before the events you care about. */
    val events: SharedFlow<BlasterEvent> = _events.asSharedFlow()

    private var bluetoothLeService: BluetoothLeService? = null
    private var serviceEvents: Job? = null

    /** Address reported with [BlasterEvent.Connected]. */
    var deviceAddress: String? = null

    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var configCharacteristic: BluetoothGattCharacteristic? = null

    private var commandId: Byte = 0x00
    private val weaponProfile: Byte = 0x00

    /** Type reported by the ID characteristic. */
    var blasterType: Byte = GattProtocolHandler.BLASTER_TYPE_PISTOL
        private set

    /** Last recoil setting requested (or toggled), remembered even while disconnected. */
    var isRecoilEnabled = true
        private set

    var isConnected = false
        private set

    // Counters are re-baselined to the first packet of each connection, like the activity does,
    // so connecting never raises trigger/reload/thumb/power edges.
    private var needsBaseline = true
    private var lastTriggerCount: Byte = 0
    private var lastReloadButtonCount: Byte = 0
    private var lastThumbButtonCount: Byte = 0
    private var lastPowerButtonCount: Byte = 0
    private var lastTeam: Byte = 0

    private val battery = BatteryLevelPolicy.Averager()

    /**
     * Attaches the bound service (or detaches, with null). The manager is the single consumer of
     * the service's GATT events; call this before [BluetoothLeService.connect] so none are missed.
     */
    fun setBluetoothLeService(service: BluetoothLeService?) {
        serviceEvents?.cancel()
        serviceEvents = null
        bluetoothLeService = service
        if (service != null) {
            // Undispatched: subscribed before this returns, whatever the scope's dispatcher.
            serviceEvents = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                service.events.collect { onGattEvent(it) }
            }
            discoverGattCharacteristics()
        }
    }

    private fun onGattEvent(event: GattEvent) {
        when (event) {
            is GattEvent.Action -> onEvent(event.action)
            is GattEvent.Telemetry -> onTelemetry(event.data)
            is GattEvent.BlasterId -> onBlasterId(event.blasterType)
        }
    }

    fun setTeam(playerId: Byte) {
        val service = bluetoothLeService ?: return
        val characteristic = commandCharacteristic ?: return
        if (playerId < 1) return
        val command = GattProtocolHandler.buildSetTeamCommand(commandId, playerId)
        commandId = (commandId + GattProtocolHandler.COMMAND_ID_INCREMENT).toByte()
        service.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun startReload(playerId: Byte) {
        val service = bluetoothLeService ?: return
        val characteristic = commandCharacteristic ?: return
        val command = GattProtocolHandler.buildStartReloadCommand(commandId, playerId)
        commandId = (commandId + GattProtocolHandler.COMMAND_ID_INCREMENT).toByte()
        service.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun finishReload(playerId: Byte, fullReloadCount: Byte) {
        val service = bluetoothLeService ?: return
        val characteristic = commandCharacteristic ?: return
        val command = GattProtocolHandler.buildFinishReloadCommand(commandId, playerId, weaponProfile, fullReloadCount)
        commandId = (commandId + GattProtocolHandler.COMMAND_ID_INCREMENT).toByte()
        service.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun setShotMode(shotMode: Int, firingMode: Int) {
        val service = bluetoothLeService ?: return
        val characteristic = configCharacteristic ?: return
        val config = GattProtocolHandler.buildShotModeConfig(weaponProfile, shotMode, blasterType, firingMode)
        service.writeCharacteristic(characteristic, config, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    fun setRecoil(enabled: Boolean) {
        isRecoilEnabled = enabled
        val service = bluetoothLeService ?: return
        val characteristic = configCharacteristic ?: return
        val config = GattProtocolHandler.buildRecoilConfig(enabled)
        service.writeCharacteristic(characteristic, config, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    override fun onEvent(action: String) {
        when (action) {
            BluetoothLeService.ACTION_GATT_CONNECTED -> {
                isConnected = true
                needsBaseline = true
                emit(BlasterEvent.Connected(deviceAddress))
            }
            BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                isConnected = false
                emit(BlasterEvent.Disconnected)
            }
            BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED -> {
                discoverGattCharacteristics()
                emit(BlasterEvent.ServicesDiscovered)
            }
            BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED -> emit(BlasterEvent.WriteFinished)
        }
    }

    override fun onTelemetry(data: ByteArray?) {
        val telemetryData = data ?: telemetryCharacteristic?.value
        if (telemetryData != null) {
            processTelemetryData(telemetryData)
        }
    }

    override fun onBlasterId(blasterType: Byte?) {
        this.blasterType = blasterType ?: GattProtocolHandler.BLASTER_TYPE_PISTOL
        emit(BlasterEvent.TypeDiscovered(this.blasterType))
    }

    fun discoverGattCharacteristics() {
        val service = bluetoothLeService ?: return
        val gattServices = service.supportedGattServices ?: return

        for (gattService in gattServices) {
            if (GattAttributes.RECOIL_MAIN_SERVICE.equals(gattService.uuid.toString(), ignoreCase = true)) {
                telemetryCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID))
                telemetryCharacteristic?.let { service.setCharacteristicNotification(it, true) }
                commandCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID))
                configCharacteristic = gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_CONFIG_UUID))

                gattService.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_ID_UUID))?.let {
                    service.readCharacteristic(it)
                }

                scope.launch {
                    delay(RECOIL_AFTER_DISCOVERY_MILLIS)
                    setRecoil(isRecoilEnabled)
                }
            }
        }
    }

    private fun processTelemetryData(data: ByteArray) {
        val telemetry = GattProtocolHandler.parseTelemetry(data) ?: return

        emit(BlasterEvent.Telemetry(telemetry))

        if (telemetry.playerId != lastTeam) {
            lastTeam = telemetry.playerId
            emit(BlasterEvent.PlayerIdChanged(telemetry.playerId))
        }

        if (needsBaseline) {
            needsBaseline = false
            lastTriggerCount = telemetry.triggerCounter
            lastReloadButtonCount = telemetry.reloadCounter
            lastThumbButtonCount = telemetry.thumbCounter
            lastPowerButtonCount = telemetry.powerCounter
        }

        if (telemetry.triggerCounter != lastTriggerCount) {
            lastTriggerCount = telemetry.triggerCounter
            emit(BlasterEvent.TriggerPulled(telemetry.shotsRemaining))
        }

        if (telemetry.reloadCounter != lastReloadButtonCount) {
            lastReloadButtonCount = telemetry.reloadCounter
            emit(BlasterEvent.ReloadPressed)
        }

        if (telemetry.thumbCounter != lastThumbButtonCount) {
            lastThumbButtonCount = telemetry.thumbCounter
            emit(BlasterEvent.ThumbPressed)
        }

        if (telemetry.powerCounter != lastPowerButtonCount) {
            lastPowerButtonCount = telemetry.powerCounter
            isRecoilEnabled = !isRecoilEnabled
            emit(BlasterEvent.RecoilToggled(isRecoilEnabled))
        }

        emit(BlasterEvent.BatteryLevel(battery.add(telemetry.batteryLevel), blasterType))

        if (telemetry.hitByPlayer1 != 0) {
            emit(BlasterEvent.Hit(telemetry.hitByPlayer1, telemetry.shotId1, secondary = false))
        }
        if (telemetry.hitByPlayer2 != 0) {
            emit(BlasterEvent.Hit(telemetry.hitByPlayer2, telemetry.shotId2, secondary = true))
        }
    }

    private fun emit(event: BlasterEvent) {
        if (!_events.tryEmit(event)) {
            Log.w(TAG, "Event buffer full, dropped $event")
        }
    }

    companion object {
        private const val TAG = "BlasterManager"

        /** About 25 s of telemetry; a full buffer means a stalled collector. */
        private const val EVENT_BUFFER = 256

        /** §1.6: characteristics discovered → recoil setting written after this delay. */
        const val RECOIL_AFTER_DISCOVERY_MILLIS = 500L
    }
}
