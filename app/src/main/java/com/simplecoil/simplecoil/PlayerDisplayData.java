/*
 * Copyright (C) 2018 Ethan Yonker
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

public class PlayerDisplayData {
    public byte playerID;
    public String playerName;
    public int points;
    public int eliminated;
    public boolean overrideLives;
    public int lives;
    public boolean isConnected;
    /** GattProtocolHandler.BLASTER_TYPE_*; 0 when the player hasn't reported one. */
    public byte blasterType;
    public boolean hasGrenade;
}
