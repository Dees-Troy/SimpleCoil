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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Globals {
    private static Globals mInstance= null;

    // 1 for FFA, 2 for 2 Teams, and 4 for 4 Teams
    public volatile String mGameMode = "2";

    public volatile int mGameLimit = FullscreenActivity.GAME_LIMIT_NONE;
    public volatile int mTimeLimit = 0;
    public volatile int mScoreLimit = 0;
    public volatile int mLivesLimit = 0;

    public volatile byte mPlayerID = 0;
    public volatile String mPlayerName = "";
    public volatile Map<InetAddress, Byte> mIPTeamMap;
    public volatile Map<Byte, InetAddress> mTeamIPMap;
    public volatile Map<Byte, String> mTeamPlayerNameMap;

    public volatile InetAddress mServerIP = null;

    protected Globals(){}

    public static synchronized Globals getInstance(){
        if(mInstance == null){
            mInstance = new Globals();
            mInstance.mIPTeamMap = new HashMap<InetAddress, Byte>();
            mInstance.mTeamIPMap = new HashMap<Byte, InetAddress>();
            mInstance.mTeamPlayerNameMap = new HashMap<Byte, String>();
        }
        return mInstance;
    }

    public void setGameMode(int gameMode) {
        if (gameMode == FullscreenActivity.GAME_MODE_2TEAMS)
            getInstance().mGameMode = "2";
        else if (gameMode == FullscreenActivity.GAME_MODE_4TEAMS)
            getInstance().mGameMode = "4";
        else
            getInstance().mGameMode = "1";
    }

    public String getPlayerName(Byte playerID) {
        String ret = getInstance().mTeamPlayerNameMap.get(playerID);
        if (ret == null)
            ret = "";
        return ret;
    }

    /**
     * Get IP address from first non-localhost interface
     * @return  address or empty string
     */
    public static String getIPAddressStr() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return sAddr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "";
    }
    public static InetAddress getIPAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;

                        if (isIPv4) {
                            return addr;
                        } /*else {
                            // IPv6 stuff
                            int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                            return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                        }*/
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static int getPlayerCount() {
        if (getInstance().mTeamIPMap == null)
            return 1; // Guess it's just you
        return getInstance().mTeamIPMap.size() + 1; // All other players plus ourself
    }
}