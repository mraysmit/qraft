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

package dev.mars.qraft.server.observability;

import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests enabled/disabled server telemetry and exporter cleanup after initialization fails.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
@ResourceLock("global-open-telemetry")
class TelemetryConfigTest {
    @Test
    void telemetryCanBeDisabledWithoutOpeningExporters() {
        AppConfig config = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"telemetry\":{\"enabled\":false}}}");
        assertDoesNotThrow(() -> TelemetryConfig.configure(config));
        assertDoesNotThrow(TelemetryConfig::new);
    }

    @Test
    void enabledTelemetryRegistersTheSdkAndClosesItsExporterWorker() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        int before = spanWorkers();
        AppConfig config = AppConfig.fromJson(
                "{\"version\":1,\"server\":{\"telemetry\":{\"prometheusPort\":0}}}");
        try (AutoCloseable telemetry = TelemetryConfig.configure(config)) {
            assertTrue(spanWorkers() > before, "the real batch exporter worker must start");
            assertNotEquals(OpenTelemetry.noop().getMeter("test").getClass(),
                    GlobalOpenTelemetry.getMeter("test").getClass(), "the SDK must be registered globally");
            assertEquals(0, TelemetryConfig.getPrometheusPort());
            assertEquals(config.getOtlpEndpoint(), TelemetryConfig.getOtlpEndpoint());
        } finally {
            OpenTelemetryAppender.install(OpenTelemetry.noop());
            GlobalOpenTelemetry.resetForTest();
        }
        awaitSpanWorkers(before);
    }

    @Test
    void aPrometheusBindFailureClosesTheAlreadyOpenedTraceExporter() throws Exception {
        int before = spanWorkers();
        // Hold the port throughout the attempt: contention is deliberate and has no allocation race.
        try (ServerSocket occupied = new ServerSocket(0)) {
            AppConfig config = AppConfig.fromJson(
                    "{\"version\":1,\"server\":{\"telemetry\":{\"prometheusPort\":" + occupied.getLocalPort() + "}}}");
            assertThrows(RuntimeException.class, () -> TelemetryConfig.configure(config));
            awaitSpanWorkers(before);
        }
    }

    @Test
    void globalRegistrationFailureClosesBothMetricsAndTraceResources() throws Exception {
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetry.noop());
        int workers = spanWorkers();
        int listeners = threadsWithPrefix("HTTP-Dispatcher");
        try {
            AppConfig config = AppConfig.fromJson(
                    "{\"version\":1,\"server\":{\"telemetry\":{\"prometheusPort\":0}}}");
            assertThrows(IllegalStateException.class, () -> TelemetryConfig.configure(config));
            awaitSpanWorkers(workers);
            assertEquals(listeners, threadsWithPrefix("HTTP-Dispatcher"),
                    "failed global registration must release the Prometheus listener");
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }

    private static int spanWorkers() {
        return threadsWithPrefix("BatchSpanProcessor_WorkerThread");
    }

    private static int threadsWithPrefix(String prefix) {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith(prefix)).count();
    }

    private static void awaitSpanWorkers(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (spanWorkers() != expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, spanWorkers(), "telemetry must not leak trace exporter workers");
    }
}
