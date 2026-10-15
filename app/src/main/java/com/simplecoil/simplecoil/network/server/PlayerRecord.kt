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

package com.simplecoil.simplecoil.network.server

/**
 * One per player the server knows about. Survives disconnects: the score lives here, not on the
 * connection. Only [GameRoster] changes it, under the roster's lock; the fields are volatile
 * so the server can read them without the lock.
 */
class PlayerRecord internal constructor(playerId: Byte, team: Int, name: String?, blasterMac: String?) {
    @Volatile
    var playerId: Byte = playerId
        internal set

    @Volatile
    var team: Int = team
        internal set

    @Volatile
    var name: String? = name
        internal set

    /** "" if the client sent none. */
    val blasterMac: String = blasterMac ?: ""

    @Volatile
    var points: Int = 0
        internal set

    @Volatile
    var eliminated: Int = 0
        internal set

    /** The blaster the client reported (GattProtocolHandler.BLASTER_TYPE_*); null until it does. */
    @Volatile
    var blasterType: Int? = null
        internal set

    /** Null while disconnected. */
    @Volatile
    var connection: Connection? = null
        internal set

    /** The highest reliable-message seq applied in the current game (§3.4). */
    @Volatile
    var lastAppliedSeq: Int = 0
        internal set

    val isConnected: Boolean get() = connection != null
}
