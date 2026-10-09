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

package dev.mars.qraft.server;

import dev.mars.qraft.common.QraftVersion;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.observability.TelemetryConfig;
import dev.mars.qraft.common.async.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Launches and stops the server for the runtime's server mode, bootstrapping the Java 27 runtime and
 * server services.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-26
 * @version 2.0 
 */
public class QraftServerApplication {

    private static final class Logging {
        private static final Logger LOGGER = LoggerFactory.getLogger(QraftServerApplication.class);
    }

    public static RunningServer launch(Path configPath) {
        ServerResources resources = launch(configPath, ServerResources::open,
                ServerResources::start, ServerResources::shutdown);
        return new RunningServer(resources);
    }

    static <T> T launch(Path configPath,
                        Function<AppConfig, T> resourceFactory,
                        Function<T, CompletableFuture<?>> starter,
                        Function<T, CompletableFuture<?>> shutdown) {
        AppConfig config = AppConfig.fromFile(configPath);
        System.setProperty("qraft.log.mode", "server");
        System.setProperty("qraft.log.dir", config.getLoggingDirectory());
        Logging.LOGGER.info("Qraft {} starting in server mode", QraftVersion.current());
        AppConfig.install(config);
        T resource = resourceFactory.apply(config);
        try {
            starter.apply(resource).join();
            return resource;
        } catch (RuntimeException | Error startupFailure) {
            try {
                shutdown.apply(resource).join();
            } catch (Throwable cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
    }

    public static final class RunningServer implements AutoCloseable {
        private final ServerResources resources;

        private RunningServer(ServerResources resources) {
            this.resources = resources;
        }

        public CompletableFuture<Void> closeAsync() {
            return resources.shutdown();
        }

        /** The ports the server's listeners bound; see {@link QraftServerService#boundPorts()}. */
        public java.util.Map<String, Integer> boundPorts() {
            return resources.server.boundPorts();
        }

        @Override
        public void close() {
            closeAsync().join();
        }
    }

    private static final class ServerResources {
        private final QraftServerService server;
        private final JavaRuntime runtime;
        private final AutoCloseable telemetry;
        private CompletableFuture<Void> shutdown;

        private ServerResources(QraftServerService server, JavaRuntime runtime,
                                    AutoCloseable telemetry) {
            this.server = server;
            this.runtime = runtime;
            this.telemetry = telemetry;
        }

        static ServerResources open(AppConfig config) {
            System.setProperty("qraft.log.dir", config.getLoggingDirectory());
            configureJulToSlf4jBridge();
            Logging.LOGGER.info("Initializing Qraft Server with OpenTelemetry (Java 27 runtime)...");
            AutoCloseable telemetry = TelemetryConfig.configure();
            JavaRuntime runtime = null;
            try {
                runtime = JavaRuntime.create();
                QraftServerService server = new QraftServerService(runtime);
                if (config.isTelemetryEnabled()) {
                    Logging.LOGGER.info("OpenTelemetry tracing enabled - OTLP endpoint: {}, Prometheus metrics port: {}",
                            config.getRedactedOtlpEndpoint(), TelemetryConfig.getPrometheusPort());
                }
                return new ServerResources(server, runtime, telemetry);
            } catch (RuntimeException | Error failure) {
                JavaRuntime opened = runtime;
                closePartiallyOpened(opened == null ? null : () -> opened.shutdown().toCompletionStage(),
                        telemetry, failure);
                throw failure;
            }
        }

        CompletableFuture<Void> start() {
            return server.start().toCompletionStage().toCompletableFuture()
                    .thenRun(() -> Logging.LOGGER.info("Qraft server started successfully"));
        }

        synchronized CompletableFuture<Void> shutdown() {
            if (shutdown == null) {
                shutdown = releaseAfter(server.stop().toCompletionStage(),
                        () -> runtime.shutdown().toCompletionStage(), telemetry);
            }
            return shutdown;
        }

    }

    /**
     * Releases what a failed startup had already acquired: the runtime, when it was created, and then
     * telemetry. Every step runs even if an earlier one failed, and each cleanup failure is added to
     * {@code failure} as suppressed, so the startup failure the caller rethrows stays the reported cause.
     *
     * @param runtimeShutdown stops the runtime, or {@code null} when startup failed before creating it
     */
    static void closePartiallyOpened(Supplier<? extends CompletionStage<?>> runtimeShutdown, AutoCloseable telemetry,
                                     Throwable failure) {
        if (runtimeShutdown != null) {
            try {
                runtimeShutdown.get().toCompletableFuture().join();
            } catch (Throwable cleanupFailure) {
                // Report the runtime's own failure, as releaseAfter does, not the join's wrapper around it.
                failure.addSuppressed(cleanupFailure instanceof CompletionException completion
                        && completion.getCause() != null ? completion.getCause() : cleanupFailure);
            }
        }
        try {
            telemetry.close();
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Releases the runtime and then telemetry once the server has stopped. Every step runs even if an
     * earlier one failed; the result fails with the first failure, carrying the later ones as suppressed.
     * <p>
     * The steps block, and the stop completes on whichever thread finished the last shutdown step, which can
     * belong to a pool the runtime shutdown waits for. They therefore run on a virtual thread of their own.
     */
    static CompletableFuture<Void> releaseAfter(CompletionStage<?> serverStopped,
                                                Supplier<? extends CompletionStage<?>> runtimeShutdown,
                                                AutoCloseable telemetry) {
        Executor release = task -> Thread.ofVirtual().name("qraft-server-release").start(task);
        CompletableFuture<Void> released = new CompletableFuture<>();
        serverStopped.whenCompleteAsync((ignored, serverFailure) -> {
            CompletionStage<?> runtimeStopped;
            try {
                runtimeStopped = runtimeShutdown.get();
            } catch (Throwable runtimeFailure) {
                runtimeStopped = CompletableFuture.failedFuture(runtimeFailure);
            }
            runtimeStopped.whenCompleteAsync((alsoIgnored, runtimeFailure) -> {
                Throwable failure = firstFailure(serverFailure, runtimeFailure);
                try {
                    telemetry.close();
                } catch (Throwable telemetryFailure) {
                    failure = firstFailure(failure, telemetryFailure);
                }
                if (failure == null) released.complete(null);
                else released.completeExceptionally(failure);
            }, release);
        }, release);
        return released;
    }

    private static Throwable firstFailure(Throwable first, Throwable second) {
        Throwable primary = unwrap(first);
        Throwable additional = unwrap(second);
        if (primary == null) return additional;
        if (additional != null && additional != primary) primary.addSuppressed(additional);
        return primary;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static void configureJulToSlf4jBridge() {
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            Logging.LOGGER.info("Installed JUL to SLF4J bridge");
        }
    }
}
