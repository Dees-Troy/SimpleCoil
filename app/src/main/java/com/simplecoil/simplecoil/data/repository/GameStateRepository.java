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

package com.simplecoil.simplecoil.data.repository;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameStateRepository {
    public static final int GAME_STATE_NONE = 0; // Game not started
    public static final int GAME_STATE_RUNNING = 1; // Game running and player active
    public static final int GAME_STATE_ELIMINATED = 2; // Player eliminated / waiting for respawn

    private volatile int gameState = GAME_STATE_NONE;
    private volatile long serverGameTimeRemaining = 0; // in seconds
    private volatile InetAddress serverIP = null;

    private final CopyOnWriteArrayList<GameStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    public interface GameStateChangeListener {
        void onGameStateChanged(int oldState, int newState);
        void onTimeRemainingUpdated(long secondsRemaining);
        void onServerIPChanged(InetAddress serverIP);
    }

    public static class GameStateSnapshot {
        private final int gameState;
        private final long serverGameTimeRemaining;
        private final InetAddress serverIP;

        public GameStateSnapshot(int gameState, long serverGameTimeRemaining, InetAddress serverIP) {
            this.gameState = gameState;
            this.serverGameTimeRemaining = serverGameTimeRemaining;
            this.serverIP = serverIP;
        }

        public int getGameState() {
            return gameState;
        }

        public long getServerGameTimeRemaining() {
            return serverGameTimeRemaining;
        }

        public InetAddress getServerIP() {
            return serverIP;
        }

        public boolean isGameRunning() {
            return gameState == GAME_STATE_RUNNING;
        }

        public boolean isGameStarted() {
            return gameState != GAME_STATE_NONE;
        }
    }

    public GameStateRepository() {}

    public void addListener(GameStateChangeListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(GameStateChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public int getGameState() {
        return gameState;
    }

    public void setGameState(int newState) {
        int oldState;
        synchronized (this) {
            if (this.gameState == newState) {
                return;
            }
            oldState = this.gameState;
            this.gameState = newState;

            for (GameStateChangeListener listener : listeners) {
                listener.onGameStateChanged(oldState, newState);
            }
        }
    }

    public long getServerGameTimeRemaining() {
        return serverGameTimeRemaining;
    }

    public void setServerGameTimeRemaining(long seconds) {
        synchronized (this) {
            if (this.serverGameTimeRemaining == seconds) {
                return;
            }
            this.serverGameTimeRemaining = seconds;

            for (GameStateChangeListener listener : listeners) {
                listener.onTimeRemainingUpdated(seconds);
            }
        }
    }

    public InetAddress getServerIP() {
        return serverIP;
    }

    public void setServerIP(InetAddress ip) {
        synchronized (this) {
            if (Objects.equals(this.serverIP, ip)) {
                return;
            }
            this.serverIP = ip;

            for (GameStateChangeListener listener : listeners) {
                listener.onServerIPChanged(ip);
            }
        }
    }

    public synchronized GameStateSnapshot getSnapshot() {
        return new GameStateSnapshot(gameState, serverGameTimeRemaining, serverIP);
    }

    public boolean isGameRunning() {
        return gameState == GAME_STATE_RUNNING;
    }

    public boolean isGameStarted() {
        return gameState != GAME_STATE_NONE;
    }
}
