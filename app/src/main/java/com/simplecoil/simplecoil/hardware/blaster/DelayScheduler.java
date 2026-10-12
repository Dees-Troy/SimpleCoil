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

package com.simplecoil.simplecoil.hardware.blaster;

import android.os.Handler;
import android.os.Looper;

/**
 * Runs a task after a delay. The BLE layer schedules its protocol delays (REFACTOR_DESIGN.md §1.6)
 * through this so tests can advance virtual time instead of relying on a main-thread Handler.
 */
public interface DelayScheduler {
    void postDelayed(Runnable task, long delayMillis);

    /** Posts to the main looper, which is what the BLE code did before this seam existed. */
    static DelayScheduler mainLooper() {
        return (task, delayMillis) -> new Handler(Looper.getMainLooper()).postDelayed(task, delayMillis);
    }
}
