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
import dev.mars.qraft.controller.observability.TelemetryConfig;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Main application class for Qraft Controller.
 *
 * Bootstraps the Java 25 runtime and controller services.
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
        configureJulToSlf4jBridge();
        System.out.println(BANNER);
        logger.info("Initializing Qraft Controller with OpenTelemetry (Java 25 runtime)...");

        // Load and validate configuration (fail fast on misconfiguration)
        AppConfig config = AppConfig.get();
        config.validate();

        TelemetryConfig.configure();
        JavaRuntime runtime = JavaRuntime.create();
        
        if (config.isTelemetryEnabled()) {
            logger.info("OpenTelemetry tracing enabled - OTLP endpoint: {}, Prometheus metrics port: {}",
                    config.getRedactedOtlpEndpoint(), TelemetryConfig.getPrometheusPort());
        }

        QraftControllerService controller = new QraftControllerService(runtime);
        controller.start()
                .onSuccess(ignored -> logger.info("Qraft controller started successfully"))
                .onFailure(err -> {
                    logger.error("Failed to start Qraft controller: {}", err.getMessage(), err);
                    System.exit(1);
                });

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping controller...");
            try {
                controller.stop().toCompletionStage().toCompletableFuture().join();
                runtime.shutdown().toCompletionStage().toCompletableFuture().join();
                logger.info("Controller runtime closed successfully");
            } catch (Throwable error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                logger.error("Controller did not shut down safely; runtime was left open: {}",
                        cause.getMessage(), cause);
            }
        }));
    }

    private static void configureJulToSlf4jBridge() {
        if (!SLF4JBridgeHandler.isInstalled()) {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            logger.info("Installed JUL to SLF4J bridge");
        }
    }
}
