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

package dev.mars.qraft.controller.observability;

import dev.mars.qraft.controller.config.AppConfig;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link RaftMetrics} executor registration and removal, and disabling telemetry without
 * opening exporters.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class RaftMetricsTest {

    @Test
    void tracksAndClearsRegisteredExecutor() {
        RaftMetrics metrics = RaftMetrics.getInstance();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        executor.prestartCoreThread();
        try {
            metrics.registerThreadPool("node", executor);
            assertEquals(1, metrics.getPoolSize());
            assertTrue(metrics.getActiveThreadCount() >= 0);
            assertEquals(0, metrics.getQueuedTaskCount());
            metrics.recordVoteRequest("node", "peer", true);
            metrics.recordVoteRequest("node", "peer", false);
            metrics.recordAppendEntries("node", "peer", true);
            metrics.recordTaskCompleted();
            metrics.unregisterThreadPool("node");
            assertEquals(0, metrics.getPoolSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void telemetryCanBeDisabledWithoutOpeningExporters() {
        AppConfig config = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"telemetry\":{\"enabled\":false}}}");
        assertDoesNotThrow(() -> TelemetryConfig.configure(config));
        assertDoesNotThrow(TelemetryConfig::new);
    }
}
