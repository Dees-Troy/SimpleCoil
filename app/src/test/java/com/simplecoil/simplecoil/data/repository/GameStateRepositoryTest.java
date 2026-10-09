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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class GameStateRepositoryTest {

    private GameStateRepository repository;
    private int stateChangedOldState = -1;
    private int stateChangedNewState = -1;
    private long updatedTimeRemaining = -1;
    private InetAddress updatedServerIP = null;

    @Before
    public void setUp() {
        repository = GameStateRepository.getInstance();
        repository.setGameState(GameStateRepository.GAME_STATE_NONE);
        repository.setServerGameTimeRemaining(0);
        stateChangedOldState = -1;
        stateChangedNewState = -1;
        updatedTimeRemaining = -1;
        updatedServerIP = null;
    }

    @Test
    public void testStateTransitionsAndListeners() throws UnknownHostException {
        GameStateRepository.GameStateChangeListener listener = new GameStateRepository.GameStateChangeListener() {
            @Override
            public void onGameStateChanged(int oldState, int newState) {
                stateChangedOldState = oldState;
                stateChangedNewState = newState;
            }

            @Override
            public void onTimeRemainingUpdated(long secondsRemaining) {
                updatedTimeRemaining = secondsRemaining;
            }

            @Override
            public void onServerIPChanged(InetAddress serverIP) {
                updatedServerIP = serverIP;
            }
        };

        repository.addListener(listener);

        assertFalse(repository.isGameRunning());
        assertFalse(repository.isGameStarted());

        repository.setGameState(GameStateRepository.GAME_STATE_RUNNING);
        assertEquals(GameStateRepository.GAME_STATE_NONE, stateChangedOldState);
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, stateChangedNewState);
        assertTrue(repository.isGameRunning());
        assertTrue(repository.isGameStarted());

        repository.setServerGameTimeRemaining(300);
        assertEquals(300, updatedTimeRemaining);

        InetAddress testIP = InetAddress.getByName("192.168.1.100");
        repository.setServerIP(testIP);
        assertEquals(testIP, updatedServerIP);

        repository.removeListener(listener);
    }

    @Test
    public void testConcurrentAddListener() throws InterruptedException {
        final AtomicInteger callbackCount = new AtomicInteger(0);
        GameStateRepository.GameStateChangeListener listener = new GameStateRepository.GameStateChangeListener() {
            @Override
            public void onGameStateChanged(int oldState, int newState) {
                callbackCount.incrementAndGet();
            }

            @Override
            public void onTimeRemainingUpdated(long secondsRemaining) {}

            @Override
            public void onServerIPChanged(InetAddress serverIP) {}
        };

        int numThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    repository.addListener(listener);
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        repository.setGameState(GameStateRepository.GAME_STATE_ELIMINATED);
        assertEquals(1, callbackCount.get());

        repository.removeListener(listener);
    }

    @Test
    public void testAtomicSnapshot() throws UnknownHostException {
        InetAddress testIP = InetAddress.getByName("10.0.0.1");
        repository.setGameState(GameStateRepository.GAME_STATE_RUNNING);
        repository.setServerGameTimeRemaining(120);
        repository.setServerIP(testIP);

        GameStateRepository.GameStateSnapshot snapshot = repository.getSnapshot();

        assertNotNull(snapshot);
        assertEquals(GameStateRepository.GAME_STATE_RUNNING, snapshot.getGameState());
        assertEquals(120, snapshot.getServerGameTimeRemaining());
        assertEquals(testIP, snapshot.getServerIP());
        assertTrue(snapshot.isGameRunning());
        assertTrue(snapshot.isGameStarted());
    }

    @Test
    public void testConcurrentStateUpdates() throws InterruptedException {
        int numThreads = 8;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        repository.setGameState(j % 3);
                        repository.setServerGameTimeRemaining(threadId * 100L + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Verify snapshot consistency
        GameStateRepository.GameStateSnapshot snapshot = repository.getSnapshot();
        assertNotNull(snapshot);
        assertEquals(repository.getGameState(), snapshot.getGameState());
        assertEquals(repository.getServerGameTimeRemaining(), snapshot.getServerGameTimeRemaining());
    }
}
