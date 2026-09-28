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

package dev.mars.qraft.controller;

import dev.mars.qraft.controller.config.AppConfig;
import dev.mars.qraft.config.ConfigFileResolver;
import dev.mars.qraft.controller.observability.TelemetryConfig;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Main application class for Qraft Controller.
 *
 * Bootstraps the Java 27 runtime and controller services.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-26
 * @version 2.0 
 */
public class QraftControllerApplication {

    private static final Logger logger = LoggerFactory.getLogger(QraftControllerApplication.class);

    /**
     * Main entry point for the Qraft Controller application.
     */
    private static final String BANNER = """
            
              ██████  ██    ██  ██████  ██████  ██    ██ ███████
             ██    ██ ██    ██ ██    ██ ██   ██ ██    ██ ██
             ██    ██ ██    ██ ██    ██ ██████  ██    ██ ███████
             ██ ▄▄ ██ ██    ██ ██    ██ ██   ██ ██    ██      ██
              ██████   ██████   ██████  ██   ██  ██████  ███████
                 ▀▀                       Controller
            """;

    public static void main(String[] args) {
        Path configPath = ConfigFileResolver.resolve(args, "server");
        RunningController controller = launch(configPath);
        CountDownLatch shutdownComplete = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            logger.info("Shutdown signal received, stopping controller...");
            try {
                controller.close();
                logger.info("Controller runtime closed successfully");
            } catch (Throwable error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                logger.error("Controller did not shut down safely: {}",
                        cause.getMessage(), cause);
            } finally {
                shutdownComplete.countDown();
            }
        }));
        try {
            shutdownComplete.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            controller.close();
        }
    }

    public static RunningController launch(Path configPath) {
        ControllerResources resources = launch(configPath, ControllerResources::open,
                ControllerResources::start, ControllerResources::shutdown);
        return new RunningController(resources);
    }

    static <T> T launch(Path configPath,
                        Function<AppConfig, T> resourceFactory,
                        Function<T, CompletableFuture<?>> starter,
                        Function<T, CompletableFuture<?>> shutdown) {
        AppConfig config = AppConfig.install(configPath);
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

    public static final class RunningController implements AutoCloseable {
        private final ControllerResources resources;

        private RunningController(ControllerResources resources) {
            this.resources = resources;
        }

        public CompletableFuture<Void> closeAsync() {
            return resources.shutdown();
        }

        /** The ports the controller's listeners bound; see {@link QraftControllerService#boundPorts()}. */
        public java.util.Map<String, Integer> boundPorts() {
            return resources.controller.boundPorts();
        }

        @Override
        public void close() {
            closeAsync().join();
        }
    }

    private static final class ControllerResources {
        private final QraftControllerService controller;
        private final JavaRuntime runtime;
        private final AutoCloseable telemetry;
        private CompletableFuture<Void> shutdown;

        private ControllerResources(QraftControllerService controller, JavaRuntime runtime,
                                    AutoCloseable telemetry) {
            this.controller = controller;
            this.runtime = runtime;
            this.telemetry = telemetry;
        }

        static ControllerResources open(AppConfig config) {
            System.setProperty("qraft.log.dir", config.getLoggingDirectory());
            configureJulToSlf4jBridge();
            System.out.println(BANNER);
            logger.info("Initializing Qraft Controller with OpenTelemetry (Java 27 runtime)...");
            AutoCloseable telemetry = TelemetryConfig.configure();
            JavaRuntime runtime = null;
            try {
                runtime = JavaRuntime.create();
                QraftControllerService controller = new QraftControllerService(runtime);
                if (config.isTelemetryEnabled()) {
                    logger.info("OpenTelemetry tracing enabled - OTLP endpoint: {}, Prometheus metrics port: {}",
                            config.getRedactedOtlpEndpoint(), TelemetryConfig.getPrometheusPort());
                }
                return new ControllerResources(controller, runtime, telemetry);
            } catch (RuntimeException | Error failure) {
                JavaRuntime opened = runtime;
                closePartiallyOpened(opened == null ? null : () -> opened.shutdown().toCompletionStage(),
                        telemetry, failure);
                throw failure;
            }
        }

        CompletableFuture<Void> start() {
            return controller.start().toCompletionStage().toCompletableFuture()
                    .thenRun(() -> logger.info("Qraft controller started successfully"));
        }

        synchronized CompletableFuture<Void> shutdown() {
            if (shutdown == null) {
                shutdown = releaseAfter(controller.stop().toCompletionStage(),
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
     * Releases the runtime and then telemetry once the controller has stopped. Every step runs even if an
     * earlier one failed; the result fails with the first failure, carrying the later ones as suppressed.
     * <p>
     * The steps block, and the stop completes on whichever thread finished the last shutdown step, which can
     * belong to a pool the runtime shutdown waits for. They therefore run on a virtual thread of their own.
     */
    static CompletableFuture<Void> releaseAfter(CompletionStage<?> controllerStopped,
                                                Supplier<? extends CompletionStage<?>> runtimeShutdown,
                                                AutoCloseable telemetry) {
        Executor release = task -> Thread.ofVirtual().name("qraft-controller-release").start(task);
        CompletableFuture<Void> released = new CompletableFuture<>();
        controllerStopped.whenCompleteAsync((ignored, controllerFailure) -> {
            CompletionStage<?> runtimeStopped;
            try {
                runtimeStopped = runtimeShutdown.get();
            } catch (Throwable runtimeFailure) {
                runtimeStopped = CompletableFuture.failedFuture(runtimeFailure);
            }
            runtimeStopped.whenCompleteAsync((alsoIgnored, runtimeFailure) -> {
                Throwable failure = firstFailure(controllerFailure, runtimeFailure);
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
            logger.info("Installed JUL to SLF4J bridge");
        }
    }
}
