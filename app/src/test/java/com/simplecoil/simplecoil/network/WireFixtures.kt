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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File

/**
 * The golden wire fixtures under test resources `wire/` (network design §7.2): every message
 * family as the v07 builders produce it. A fixture is never edited to make a change pass.
 *
 * A JSON fixture is the frame header on its first line and the JSON after it; frames are
 * compared semantically (same keys, values and types, key order ignored, array order kept). A
 * commands fixture holds one raw MESG frame per line, compared byte for byte.
 *
 * A missing fixture fails the test and writes what was produced to `build/wire-candidates/`, so
 * a new fixture can be reviewed and copied in.
 */
object WireFixtures {
    private const val JSON_HEADER = WireProtocol.TCPMESSAGE_PREFIX + WireProtocol.TCPPREFIX_JSON

    /** The fixture's text, or null if there is none. */
    fun text(name: String): String? =
        WireFixtures::class.java.getResource("/wire/$name")?.readText()?.trimEnd('\n')

    /** The frame a JSON fixture holds, as one line (header + compact JSON). */
    fun frame(name: String): String {
        val text = text(name) ?: throw AssertionError("No wire fixture $name")
        val header = text.substringBefore('\n')
        return header + JSONObject(text.substringAfter('\n'))
    }

    /** The JSON a JSON fixture holds. */
    fun json(name: String): JSONObject = JSONObject(frame(name).removePrefix(JSON_HEADER))

    /** [actual] is the JSON frame the fixture [name] holds. */
    fun assertFrame(name: String, actual: String) {
        val expected = text(name)
        if (expected == null) {
            val header = if (actual.startsWith(JSON_HEADER)) JSON_HEADER else actual
            val body = if (actual.startsWith(JSON_HEADER)) JSONObject(actual.removePrefix(JSON_HEADER)).toString(2) else ""
            missing(name, header + "\n" + body)
        }
        val header = expected!!.substringBefore('\n')
        if (!actual.startsWith(header)) fail("$name: frame '$actual' doesn't start with '$header'")
        assertJsonEquals(name, JSONObject(expected.substringAfter('\n')), JSONObject(actual.removePrefix(header)))
    }

    /** The commands fixture [name], one frame per line. */
    fun commands(name: String): List<String> = (text(name) ?: throw AssertionError("No wire fixture $name")).split('\n')

    /** [actual] are exactly the frames the commands fixture [name] lists, in order. */
    fun assertCommands(name: String, actual: List<String>) {
        if (text(name) == null) missing(name, actual.joinToString("\n"))
        assertEquals(name, commands(name), actual)
    }

    private fun missing(name: String, candidate: String) {
        val file = File("build/wire-candidates/$name")
        file.parentFile.mkdirs()
        file.writeText(candidate + "\n")
        fail("No wire fixture $name; wrote what was produced to ${file.absolutePath}")
    }

    /** Same keys, values and types; numbers by value; key order ignored, array order kept. */
    fun assertJsonEquals(path: String, expected: Any?, actual: Any?) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                val keys = expected.keys().asSequence().toSortedSet()
                assertEquals("$path: keys", keys, actual.keys().asSequence().toSortedSet())
                for (key in keys) assertJsonEquals("$path.$key", expected.get(key), actual.get(key))
            }
            expected is JSONArray && actual is JSONArray -> {
                assertEquals("$path: length", expected.length(), actual.length())
                for (i in 0 until expected.length()) assertJsonEquals("$path[$i]", expected.get(i), actual.get(i))
            }
            expected is Number && actual is Number ->
                assertEquals("$path: $expected vs $actual", expected.toDouble(), actual.toDouble(), 0.0)
            else -> {
                if (expected != null && actual != null && expected.javaClass != actual.javaClass)
                    fail("$path: expected ${expected.javaClass.simpleName} $expected, got ${actual.javaClass.simpleName} $actual")
                assertEquals(path, expected, actual)
            }
        }
    }
}
