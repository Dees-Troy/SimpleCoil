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

import com.simplecoil.simplecoil.domain.session.BlasterCommands
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app's view of the blaster: one ordered [events] stream and the outbound commands. Packets
 * are only ever built by [GattProtocolHandler] (via [BlasterManager]); this class adds nothing to
 * the wire.
 */
class BleDeviceRepository(private val blasterManager: BlasterManager) : BlasterCommands {

    /** Every BlasterManager event, in order (§2.1.3). */
    val events: SharedFlow<BlasterEvent> get() = blasterManager.events

    val blasterType: Byte get() = blasterManager.blasterType

    override fun setTeam(playerId: Byte) = blasterManager.setTeam(playerId)

    override fun startReload(playerId: Byte) = blasterManager.startReload(playerId)

    override fun finishReload(playerId: Byte, fullReloadCount: Byte) = blasterManager.finishReload(playerId, fullReloadCount)

    override fun setShotMode(shotMode: Int, firingMode: Int) = blasterManager.setShotMode(shotMode, firingMode)

    override fun setRecoil(enabled: Boolean) = blasterManager.setRecoil(enabled)
}
