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

package com.simplecoil.simplecoil.network.discovery;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.simplecoil.simplecoil.NetMsg;
import com.simplecoil.simplecoil.data.repository.PlayerRepository;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles LAN Server Discovery using lightweight UDP broadcast beacons.
 * UDP is used EXCLUSIVELY for server discovery in Option A architecture.
 */
public class ServerDiscoveryManager {
    private static final String TAG = "ServerDiscoveryMgr";

    public static final int DISCOVERY_PORT = 17500;
    private static final int DISCOVERY_TIMEOUT_MS = 1000;
    private static final int BEACON_INTERVAL_MS = 1000;
    private static final int RECEIVE_BUFFER_SIZE = 1024;

    public static final String BEACON_PREFIX = NetMsg.MESSAGE_PREFIX + "BEACON:";
    public static final String DISCOVER_MSG = NetMsg.MESSAGE_PREFIX + "DISCOVER";

    private final Context mContext;
    private WifiManager mWifiManager;
    private WifiManager.MulticastLock mMulticastLock;

    private DatagramSocket mServerSocket;
    private DatagramSocket mClientSocket;

    private volatile boolean mIsServerBroadcasting = false;
    private volatile boolean mIsClientDiscovering = false;

    private Thread mServerBeaconThread;
    private Thread mClientDiscoveryThread;

    public interface ServerDiscoveryListener {
        void onServerDiscovered(String serverIP, String serverName);
        void onDiscoveryFailed(String errorReason);
    }

    public ServerDiscoveryManager(Context context) {
        mContext = context.getApplicationContext();
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    private void acquireMulticastLock() {
        if (mWifiManager != null) {
            if (mMulticastLock == null) {
                mMulticastLock = mWifiManager.createMulticastLock("SimpleCoilDiscovery");
                mMulticastLock.setReferenceCounted(true);
            }
            if (!mMulticastLock.isHeld()) {
                mMulticastLock.acquire();
            }
        }
    }

    private void releaseMulticastLock() {
        if (mMulticastLock != null && mMulticastLock.isHeld()) {
            mMulticastLock.release();
        }
    }

    /**
     * The subnet broadcast from the Wi-Fi DHCP info, plus the limited broadcast 255.255.255.255.
     * On a Chromebook the app sits behind NAT and the DHCP info describes the container's internal
     * subnet, so the subnet broadcast may never reach the LAN.
     */
    private List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        InetAddress subnet = getSubnetBroadcastAddress();
        if (subnet != null) {
            addresses.add(subnet);
        }
        try {
            InetAddress limited = InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
            if (!addresses.contains(limited)) {
                addresses.add(limited);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to build limited broadcast address", e);
        }
        return addresses;
    }

    private InetAddress getSubnetBroadcastAddress() {
        if (mWifiManager == null) {
            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        }
        if (mWifiManager == null) {
            Log.e(TAG, "WifiManager unavailable");
            return null;
        }
        DhcpInfo dhcp = mWifiManager.getDhcpInfo();
        if (dhcp == null) {
            Log.e(TAG, "DhcpInfo unavailable");
            return null;
        }

        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
        byte[] quads = new byte[4];
        for (int k = 0; k < 4; k++) {
            quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
        }
        try {
            return InetAddress.getByAddress(quads);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute broadcast address", e);
            return null;
        }
    }

    /**
     * Starts broadcasting server discovery beacons on UDP port 17500.
     */
    public synchronized void startBroadcastingBeacon(final String serverName) {
        if (mIsServerBroadcasting) {
            Log.d(TAG, "Server beacon already running");
            return;
        }
        acquireMulticastLock();
        mIsServerBroadcasting = true;

        mServerBeaconThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerSocket = new DatagramSocket(null);
                    mServerSocket.setReuseAddress(true);
                    mServerSocket.setBroadcast(true);
                    mServerSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                    mServerSocket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

                    Log.i(TAG, "Server discovery beacon started on port " + DISCOVERY_PORT);
                    byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                    long lastBeaconTime = 0;

                    while (mIsServerBroadcasting) {
                        long now = System.currentTimeMillis();
                        if (now - lastBeaconTime >= BEACON_INTERVAL_MS) {
                            sendBeaconPacket(serverName);
                            lastBeaconTime = now;
                        }

                        try {
                            mServerSocket.receive(packet);
                            String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                            if (msg.startsWith(DISCOVER_MSG) || msg.startsWith(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_JOIN)) {
                                Log.d(TAG, "Received discovery query from " + packet.getAddress().getHostAddress());
                                sendBeaconPacketTo(packet.getAddress(), serverName);
                            }
                        } catch (SocketTimeoutException ignored) {
                            // Timeout expected to allow periodic beacon loop
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Server beacon thread error", e);
                } finally {
                    if (mServerSocket != null && !mServerSocket.isClosed()) {
                        mServerSocket.close();
                    }
                    releaseMulticastLock();
                    Log.i(TAG, "Server discovery beacon stopped");
                }
            }
        });
        mServerBeaconThread.start();
    }

    private void sendBeaconPacket(String serverName) {
        for (InetAddress broadcastAddr : getBroadcastAddresses()) {
            sendBeaconPacketTo(broadcastAddr, serverName);
        }
    }

    private void sendBeaconPacketTo(InetAddress destination, String serverName) {
        try {
            String payload = BEACON_PREFIX + (serverName != null ? serverName : "SimpleCoilServer");
            byte[] buf = payload.getBytes();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, destination, DISCOVERY_PORT);
            DatagramSocket socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.send(packet);
            socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error sending discovery beacon packet", e);
        }
    }

    /**
     * Stops server beacon thread.
     */
    public synchronized void stopBroadcastingBeacon() {
        mIsServerBroadcasting = false;
        if (mServerSocket != null) {
            mServerSocket.close();
        }
    }

    /**
     * Starts listening for server beacons on UDP port 17500 and broadcasts discovery queries.
     */
    public synchronized void startDiscovery(final ServerDiscoveryListener listener) {
        if (mIsClientDiscovering) {
            Log.d(TAG, "Client discovery already running");
            return;
        }
        acquireMulticastLock();
        mIsClientDiscovering = true;

        mClientDiscoveryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mClientSocket = new DatagramSocket(null);
                    mClientSocket.setReuseAddress(true);
                    mClientSocket.setBroadcast(true);
                    mClientSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                    mClientSocket.setSoTimeout(DISCOVERY_TIMEOUT_MS);

                    Log.i(TAG, "Client discovery listening on port " + DISCOVERY_PORT);
                    sendDiscoveryQuery();

                    byte[] recvBuf = new byte[RECEIVE_BUFFER_SIZE];
                    DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);

                    while (mIsClientDiscovering) {
                        try {
                            mClientSocket.receive(packet);
                            InetAddress myIp = PlayerRepository.getIPAddress();
                            if (packet.getAddress().equals(myIp)) {
                                continue; // Ignore self
                            }
                            String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                            if (msg.startsWith(BEACON_PREFIX) || msg.startsWith(NetMsg.MESSAGE_PREFIX + NetMsg.NETMSG_SERVERREPLY)) {
                                String serverIp = packet.getAddress().getHostAddress();
                                String serverName = "SimpleCoil Server";
                                if (msg.startsWith(BEACON_PREFIX)) {
                                    serverName = msg.substring(BEACON_PREFIX.length());
                                }
                                Log.i(TAG, "Discovered server: " + serverName + " at " + serverIp);
                                if (listener != null) {
                                    listener.onServerDiscovered(serverIp, serverName);
                                }
                            }
                        } catch (SocketTimeoutException ignored) {
                            // Send query again periodically
                            if (mIsClientDiscovering) {
                                sendDiscoveryQuery();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (mIsClientDiscovering) {
                        Log.e(TAG, "Client discovery thread error", e);
                        if (listener != null) {
                            listener.onDiscoveryFailed(e.getLocalizedMessage());
                        }
                    } else {
                        Log.d(TAG, "Client discovery socket closed normally");
                    }
                } finally {
                    if (mClientSocket != null && !mClientSocket.isClosed()) {
                        mClientSocket.close();
                    }
                    releaseMulticastLock();
                    Log.i(TAG, "Client discovery stopped");
                }
            }
        });
        mClientDiscoveryThread.start();
    }

    private void sendDiscoveryQuery() {
        for (InetAddress broadcastAddr : getBroadcastAddresses()) {
            try {
                byte[] buf = DISCOVER_MSG.getBytes();
                DatagramPacket packet = new DatagramPacket(buf, buf.length, broadcastAddr, DISCOVERY_PORT);
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.send(packet);
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error sending discovery query to " + broadcastAddr.getHostAddress(), e);
            }
        }
    }

    /**
     * Stops client discovery.
     */
    public synchronized void stopDiscovery() {
        mIsClientDiscovering = false;
        if (mClientSocket != null) {
            mClientSocket.close();
        }
    }
}
