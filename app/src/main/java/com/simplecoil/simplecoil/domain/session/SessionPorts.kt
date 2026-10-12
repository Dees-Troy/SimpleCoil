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
package com.simplecoil.simplecoil.domain.session

/** Outbound blaster commands. Implemented by BleDeviceRepository; packets are built elsewhere. */
interface BlasterCommands {
    fun setTeam(playerId: Byte)
    fun startReload(playerId: Byte)
    fun finishReload(playerId: Byte, fullReloadCount: Byte)
    fun setShotMode(shotMode: Int, firingMode: Int)
    fun setRecoil(enabled: Boolean)
}

/**
 * The network session as the engine needs it. Implementations send the same TCP messages the
 * activity sent and keep its null checks (a message to a missing client is dropped).
 */
interface SessionNetwork {
    val useNetwork: Boolean
    fun sendHit(playerId: Byte)
    fun sendOut(playerId: Byte)
    fun sendShotFired()
    fun sendEliminated(playerId: Byte)

    /** Grenade paired: tells a dedicated server (only when networking and the server is dedicated). */
    fun sendPlayerGrenade()

    /** Host only: publishes the host player's eliminations (with the current score). */
    fun setHostEliminations(eliminations: Int)

    /** Game time ran out: a hosting server ends the game for everyone. */
    fun serverEndGame()
}

/** Persists the player's shot mode and recoil choice (the "SimpleCoil" preferences). */
interface SessionPreferences {
    fun saveShotMode(shotMode: Int)
    fun saveRecoilEnabled(enabled: Boolean)
}
