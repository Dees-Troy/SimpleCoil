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

import java.util.ArrayList;
import java.util.List;

/** Virtual-time DelayScheduler: tasks run only when the test advances the clock past them. */
public final class FakeScheduler implements DelayScheduler {
    private static final class Task {
        final long at;
        final long seq;
        final Runnable runnable;

        Task(long at, long seq, Runnable runnable) {
            this.at = at;
            this.seq = seq;
            this.runnable = runnable;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private long now = 0;
    private long seq = 0;

    @Override
    public void postDelayed(Runnable task, long delayMillis) {
        tasks.add(new Task(now + delayMillis, seq++, task));
    }

    /** Runs, in time then posting order, every task due within the next {@code millis}. */
    public void advanceBy(long millis) {
        long target = now + millis;
        while (true) {
            Task next = null;
            for (Task t : tasks) {
                if (t.at <= target && (next == null || t.at < next.at || (t.at == next.at && t.seq < next.seq))) {
                    next = t;
                }
            }
            if (next == null) break;
            tasks.remove(next);
            now = next.at;
            next.runnable.run();
        }
        now = target;
    }

    public int pendingCount() {
        return tasks.size();
    }
}
