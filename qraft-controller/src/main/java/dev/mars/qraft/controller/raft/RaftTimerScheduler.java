/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.controller.raft;

import java.util.function.Consumer;

/**
 * Timer boundary used by a Raft node so ordering tests can drive callbacks deterministically.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-17
 * @version 1.0
 */
interface RaftTimerScheduler {
    /** Monotonic time, shared with the timer source (and deterministic in tests). */
    default long nanoTime() { return System.nanoTime(); }
    long setTimer(long delayMs, Consumer<Long> action);
    long setPeriodic(long periodMs, Consumer<Long> action);
    boolean cancelTimer(long id);
}
