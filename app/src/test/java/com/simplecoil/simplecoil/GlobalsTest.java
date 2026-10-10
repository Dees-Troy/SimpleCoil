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

import static org.junit.Assert.assertEquals;

import android.graphics.Color;

import org.junit.Test;

public class GlobalsTest {

    @Test
    public void testGetTeamColor() {
        assertEquals(Globals.TEAM_1_COLOR, Globals.getTeamColor(1));
        assertEquals(Globals.TEAM_2_COLOR, Globals.getTeamColor(2));
        assertEquals(Globals.TEAM_3_COLOR, Globals.getTeamColor(3));
        assertEquals(Globals.TEAM_4_COLOR, Globals.getTeamColor(4));
        assertEquals(Color.TRANSPARENT, Globals.getTeamColor(0));
        assertEquals(Color.TRANSPARENT, Globals.getTeamColor(5));
    }
}
