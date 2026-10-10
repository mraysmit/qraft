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
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for OpenTelemetry integration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public class TelemetryConfig {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryConfig.class);
    private static int configuredPrometheusPort;
    private static String configuredOtlpEndpoint;

    public static AutoCloseable configure(AppConfig config) {
        if (!config.isTelemetryEnabled()) {
            logger.info("Telemetry is disabled");
            return () -> { };
        }

        configuredPrometheusPort = config.getPrometheusPort();
        configuredOtlpEndpoint = config.getOtlpEndpoint();
        String serviceName = config.getServiceName();

        // 1. Configure Resource
        Resource resource = Resource.getDefault().toBuilder()
                .put("service.name", serviceName)
                .build();

        // 2. Configure Tracing (OTLP Exporter)
        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(configuredOtlpEndpoint)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                .setResource(resource)
                .build();

        PrometheusHttpServer prometheusReader = null;
        SdkMeterProvider meterProvider = null;
        try {
            // The meter provider owns the reader after registration; until then this scope owns it.
            prometheusReader = PrometheusHttpServer.builder().setPort(configuredPrometheusPort).build();
            meterProvider = SdkMeterProvider.builder()
                    .setResource(resource).registerMetricReader(prometheusReader).build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider).setMeterProvider(meterProvider).buildAndRegisterGlobal();
            OpenTelemetryAppender.install(openTelemetry);
            logger.info("OpenTelemetry configured: service={}, otlp={}, prometheus={}",
                    serviceName, configuredOtlpEndpoint, configuredPrometheusPort);
            return openTelemetry::close;
        } catch (RuntimeException | Error failure) {
            closeAfterFailedInitialization(meterProvider == null ? prometheusReader : meterProvider, failure);
            closeAfterFailedInitialization(tracerProvider, failure);
            throw failure;
        }
    }

    private static void closeAfterFailedInitialization(AutoCloseable resource, Throwable failure) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Gets the configured Prometheus metrics port.
     */
    public static int getPrometheusPort() {
        return configuredPrometheusPort;
    }

    /**
     * Gets the configured OTLP endpoint.
     */
    public static String getOtlpEndpoint() {
        return configuredOtlpEndpoint;
    }
}
