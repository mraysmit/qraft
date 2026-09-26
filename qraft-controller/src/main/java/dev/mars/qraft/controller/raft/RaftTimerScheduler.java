/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
    long setTimer(long delayMs, Consumer<Long> action);
    long setPeriodic(long periodMs, Consumer<Long> action);
    boolean cancelTimer(long id);
}
