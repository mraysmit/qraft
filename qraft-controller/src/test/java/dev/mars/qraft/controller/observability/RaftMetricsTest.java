package dev.mars.qraft.controller.observability;

import dev.mars.qraft.controller.config.AppConfig;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

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
