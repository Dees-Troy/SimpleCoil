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
 * The dedicated server's session: LOBBY → (Start Game) → RUNNING → (End Game) → POST_GAME →
 * (Reset) → LOBBY. The game roster is filled at the start, kept after the end, and cleared by the
 * reset (reconnect design §3.1).
 */
public enum SessionPhase {
    LOBBY(0),
    RUNNING(1),
    POST_GAME(2);

    /** The value sent as "sessionphase". */
    public final int wireValue;

    SessionPhase(int wireValue) {
        this.wireValue = wireValue;
    }

    /** The phase for a "sessionphase" value; LOBBY for anything unknown. */
    public static SessionPhase fromWire(int value) {
        for (SessionPhase phase : values()) {
            if (phase.wireValue == value) return phase;
        }
        return LOBBY;
    }
}
