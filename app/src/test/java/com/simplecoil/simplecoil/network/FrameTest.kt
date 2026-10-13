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

package com.simplecoil.simplecoil.network

import com.simplecoil.simplecoil.network.Frame.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Frame classifies every frame by its header and never throws (network design §5.1, N5). */
class FrameTest {

    private fun assertFrame(raw: String?, kind: Kind, body: String = "") {
        val frame = Frame.parse(raw)
        assertEquals("kind of '$raw'", kind, frame.kind)
        assertEquals("body of '$raw'", body, frame.body)
    }

    @Test
    fun mesg_bodyIsTheCommand() {
        assertFrame("SimpleCoil:07MESGELIMINATED7", Kind.MESG, "ELIMINATED7")
        assertFrame("SimpleCoil:07MESG", Kind.MESG, "")
    }

    @Test
    fun json_bodyIsTheJsonText() {
        assertFrame("SimpleCoil:07JSON{\"playerID\":3}", Kind.JSON, "{\"playerID\":3}")
        assertFrame("SimpleCoil:07JSON", Kind.JSON, "")
    }

    @Test
    fun bareFrames() {
        assertFrame("ping", Kind.PING)
        assertFrame("pong", Kind.PONG)
        assertFrame("VERSIONERROR", Kind.VERSION_ERROR)
    }

    @Test
    fun anotherProtocolVersion() {
        assertFrame("SimpleCoil:06MESGLEAVE", Kind.OTHER_VERSION)
        assertFrame("SimpleCoil:", Kind.OTHER_VERSION)
    }

    @Test
    fun shortOrGarbageFrames_areUnknown() {
        assertFrame(null, Kind.UNKNOWN)
        assertFrame("", Kind.UNKNOWN)
        assertFrame("SimpleCoil:07", Kind.UNKNOWN)
        assertFrame("SimpleCoil:07JS", Kind.UNKNOWN)
        assertFrame("SimpleCoil:07X", Kind.UNKNOWN)
        assertFrame("SimpleCoil:07XXXXELIMINATED7", Kind.UNKNOWN)
        assertFrame("hello", Kind.UNKNOWN)
        assertFrame("Pong", Kind.UNKNOWN)
    }

    @Test
    fun nonAscii_isKeptInTheBody() {
        assertFrame("SimpleCoil:07JSON{\"playername\":\"Zoë ✓ 名前\"}", Kind.JSON, "{\"playername\":\"Zoë ✓ 名前\"}")
        assertFrame("Ü", Kind.UNKNOWN)
    }
}
