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

package com.simplecoil.simplecoil.network.server;

/**
 * One per player the server knows about. Survives disconnects: the score lives here, not on the
 * connection. Only {@link GameRoster} changes it, under the roster's lock; the fields are volatile
 * so the server can read them without the lock.
 */
public final class PlayerRecord {
    volatile byte playerId;
    volatile int team;
    volatile String name;
    /** "" if the client sent none. */
    final String blasterMac;
    volatile int points;
    volatile int eliminated;
    /** Null while disconnected. */
    volatile Connection connection;
    /** The highest reliable-message seq applied in the current game (§3.4). */
    volatile int lastAppliedSeq;

    PlayerRecord(byte playerId, int team, String name, String blasterMac) {
        this.playerId = playerId;
        this.team = team;
        this.name = name;
        this.blasterMac = blasterMac != null ? blasterMac : "";
    }

    public byte getPlayerId() {
        return playerId;
    }

    public int getTeam() {
        return team;
    }

    public String getName() {
        return name;
    }

    public String getBlasterMac() {
        return blasterMac;
    }

    public int getPoints() {
        return points;
    }

    public int getEliminated() {
        return eliminated;
    }

    public Connection getConnection() {
        return connection;
    }

    public boolean isConnected() {
        return connection != null;
    }

    public int getLastAppliedSeq() {
        return lastAppliedSeq;
    }
}
