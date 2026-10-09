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

package dev.mars.qraft.client;

import dev.mars.qraft.common.ClientInfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.catalog.ServerRetryPolicy;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.catalog.ServiceReconciler;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.client.health.ExecutorCheckScheduler;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HealthPublisher;
import dev.mars.qraft.client.health.LocalHealthChecks;
import dev.mars.qraft.client.health.LocalStatusReporter;
import dev.mars.qraft.client.health.RequiredCheckReadiness;
import dev.mars.qraft.client.health.SocketTcpConnector;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.concurrent.Deadlines;
import dev.mars.qraft.client.service.RegistrationClient;
import dev.mars.qraft.client.service.HealthService;
import dev.mars.qraft.client.service.HeartbeatService;
import dev.mars.qraft.client.service.ReadinessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure Java discovery client lifecycle.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class QraftClient implements AutoCloseable {
    private static final class Logging {
        private static final Logger LOGGER = LoggerFactory.getLogger(QraftClient.class);
    }

    private final ClientConfiguration config;
    private final HttpCatalogClient serverClient;
    private final RegistrationClient registrationClient;
    private final HeartbeatService heartbeatService;
    private final HealthService healthService;
    private final ServerRetryPolicy retryPolicy;
    private final ServiceReconciler serviceReconciler;
    private final ReadinessPolicy readinessPolicy;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService checkScheduler;
    private final HttpClient checkHttpClient = HttpClient.newHttpClient();
    private final HttpClient serverHttpClient = HttpClient.newHttpClient();
    private final LocalHealthChecks localChecks;
    private final HealthPublisher healthPublisher;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean();
    private final AtomicBoolean reconciliationScheduled = new AtomicBoolean();
    private final AtomicBoolean registrationRetryScheduled = new AtomicBoolean();
    private final AtomicInteger registrationRetryNumber = new AtomicInteger();
    private final AtomicReference<CompletableFuture<Void>> pendingRetryDelay = new AtomicReference<>();
    private CompletableFuture<Boolean> shutdownFuture;

    public QraftClient(ClientConfiguration config) {
        this(config, new ServerRetryPolicy(config.getRegistrationRetryMinMs(),
                config.getRegistrationRetryMaxMs(), () -> ThreadLocalRandom.current().nextDouble()),
                Clock.systemUTC());
    }

    QraftClient(ClientConfiguration config, ServerRetryPolicy retryPolicy) {
        this(config, retryPolicy, Clock.systemUTC());
    }

    QraftClient(ClientConfiguration config, ServerRetryPolicy retryPolicy, Clock clock) {
        this(config, retryPolicy, clock, Executors.newSingleThreadScheduledExecutor(),
                Executors.newSingleThreadScheduledExecutor());
    }

    /**
     * Creates a client on the given executors. The client owns both and shuts them down with itself.
     *
     * @param scheduler      runs registration retry delays, heartbeats, and periodic reconciliation
     * @param checkScheduler runs local health checks and publication retries
     */
    QraftClient(ClientConfiguration config, ServerRetryPolicy retryPolicy, Clock clock,
               ScheduledExecutorService scheduler, ScheduledExecutorService checkScheduler) {
        this.config = config;
        this.retryPolicy = retryPolicy;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.checkScheduler = Objects.requireNonNull(checkScheduler, "checkScheduler");
        ServerContactTracker contactTracker = new ServerContactTracker(clock);
        this.serverClient = new HttpCatalogClient(serverHttpClient, new ObjectMapper(),
                config.getServerUrls(), config.getClientId(), config.getTenant(), config.getNamespace(),
                config.getDatacenter(), config.getRegion(), Duration.ofMillis(config.getRequestTimeoutMs()),
                contactTracker);
        this.registrationClient = new RegistrationClient(serverClient);
        this.heartbeatService = new HeartbeatService(config, registrationClient);
        this.serviceReconciler = new ServiceReconciler(serverClient, config::getServices, clock);
        List<HealthCheckDefinition> checks = enabledServiceChecks(config);
        RequiredCheckReadiness checkReadiness = new RequiredCheckReadiness(checks);
        ExecutorCheckScheduler checkTimer = new ExecutorCheckScheduler(checkScheduler);
        this.healthPublisher = new HealthPublisher(serverClient, checkTimer, clock, retryPolicy::delayMillis);
        this.localChecks = new LocalHealthChecks(checks, checkHttpClient, new SocketTcpConnector(), checkTimer,
                clock, (check, result) -> {
                    checkReadiness.onResult(check, result);
                    healthPublisher.onResult(check, result);
                });
        this.readinessPolicy = new ReadinessPolicy(running::get, registrationClient::isRegistered,
                serviceReconciler::isConverged, checkReadiness::isSatisfied, contactTracker,
                Duration.ofMillis(config.getContactFreshnessMs()), clock);
        this.healthService = new HealthService(config, readinessPolicy::isReady);
    }

    public CompletableFuture<Boolean> start() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(registrationClient.isRegistered());
        }
        healthService.start();
        // The server records the port the health endpoint actually bound, not a configured 0.
        ClientInfo client = new ClientInfo(config.getClientId(), config.getHostname(), config.getAddress(),
                healthService.port());
        client.setVersion(config.getVersion());
        client.setRegion(config.getRegion());
        client.setDatacenter(config.getDatacenter());
        return registrationClient.register(client).thenApply(registered -> {
            if (registered) activateHeartbeat(client);
            else if (registrationClient.shouldRetryRegistration()) scheduleRegistrationRetry(client);
            return registered;
        });
    }

    private void scheduleRegistrationRetry(ClientInfo client) {
        if (!running.get() || registrationClient.isRegistered()) return;
        if (!registrationRetryScheduled.compareAndSet(false, true)) return;
        try {
            CompletableFuture<Void> delay = retryPolicy.delay(
                    scheduler, registrationRetryNumber.getAndIncrement());
            pendingRetryDelay.set(delay);
            delay.thenRun(() -> registrationClient.register(client).whenComplete((registered, error) -> {
                registrationRetryScheduled.set(false);
                pendingRetryDelay.set(null);
                if (!running.get()) return;
                if (error == null && Boolean.TRUE.equals(registered)) activateHeartbeat(client);
                else if (registrationClient.shouldRetryRegistration()) scheduleRegistrationRetry(client);
            }));
        } catch (RejectedExecutionException ignored) {
            registrationRetryScheduled.set(false);
            // Shutdown won the race with a registration callback.
        }
    }

    /** Checks of disabled services are never run: their services are not registered. */
    private static List<HealthCheckDefinition> enabledServiceChecks(ClientConfiguration config) {
        Set<String> enabled = config.getServices().stream().filter(ServiceDefinition::enabled)
                .map(ServiceDefinition::id).collect(Collectors.toUnmodifiableSet());
        return config.getHealthChecks().stream().filter(check -> enabled.contains(check.serviceId())).toList();
    }

    private void activateHeartbeat(ClientInfo client) {
        if (!running.get()) return;
        registrationRetryNumber.set(0);
        serviceReconciler.trigger();
        localChecks.start();
        try {
            schedulePeriodicWork(client);
        } catch (RejectedExecutionException ignored) {
            // Shutdown stopped the scheduler after the running check; there is nothing left to schedule.
        }
    }

    private void schedulePeriodicWork(ClientInfo client) {
        if (reconciliationScheduled.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(() -> {
                        if (running.get()) serviceReconciler.trigger();
                    },
                    config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        }
        if (heartbeatScheduled.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(() -> {
                        if (!running.get()) return;
                        heartbeatService.sendHeartbeat()
                            .whenComplete((accepted, error) -> {
                                if (running.get() && !registrationClient.isRegistered()) {
                                    scheduleRegistrationRetry(client);
                                }
                            });
                    },
                    config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized CompletableFuture<Boolean> shutdown() {
        if (shutdownFuture != null) return shutdownFuture;

        running.set(false);
        // Checks and publications stop, and in-flight publications finish, before deregistration begins.
        localChecks.stop();
        CompletableFuture<Void> publicationsStopped = healthPublisher.stop();
        stopScheduledWork();
        CompletableFuture<ServiceReconciler.ShutdownResult> serviceShutdown =
                publicationsStopped.thenCompose(ignored -> serviceReconciler.beginShutdown());

        CompletableFuture<Boolean> graceful = serviceShutdown.thenCompose(services -> {
            CompletableFuture<Boolean> nodeShutdown =
                    registrationClient.beginShutdownAndDeregister(config.getClientId());
            return nodeShutdown.handle((nodeRemoved, failure) ->
                    services.complete() && failure == null && Boolean.TRUE.equals(nodeRemoved));
        });
        CompletableFuture<Boolean> bounded = Deadlines.bound(graceful,
                config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS);
        shutdownFuture = bounded.handle((complete, failure) -> {
            boolean succeeded = failure == null && Boolean.TRUE.equals(complete);
            finishShutdown();
            if (failure != null) {
                Logging.LOGGER.warn("Client shutdown incomplete within deadline={}ms; automatic expiry may be required",
                        config.getShutdownTimeoutMs());
            } else if (!Boolean.TRUE.equals(complete)) {
                Logging.LOGGER.warn("Client shutdown incomplete because one or more deregistration attempts failed; "
                        + "automatic expiry may be required");
            }
            return succeeded;
        }).thenCompose(succeeded -> awaitQuiescence().thenApply(quiet -> succeeded && quiet));
        return shutdownFuture;
    }

    private void stopScheduledWork() {
        CompletableFuture<Void> delay = pendingRetryDelay.getAndSet(null);
        if (delay != null) delay.cancel(false);
        scheduler.shutdownNow();
    }

    /**
     * Completes once no client-owned thread or client can still run: a scheduled task that was mid-run
     * at shutdown has returned, so nothing is sent after shutdown completes.
     */
    private CompletableFuture<Boolean> awaitQuiescence() {
        return Quiescence.shutdownNowAndAwait(List.of(scheduler, checkScheduler),
                        List.of(checkHttpClient, serverHttpClient), Duration.ofMillis(config.getShutdownTimeoutMs()))
                .thenApply(quiet -> {
                    if (!quiet) {
                        Logging.LOGGER.warn("Client threads did not terminate within {}ms of shutdown",
                                config.getShutdownTimeoutMs());
                    }
                    return quiet;
                });
    }

    private void finishShutdown() {
        checkScheduler.shutdownNow();
        checkHttpClient.shutdownNow();
        healthService.shutdown();
        serverClient.closeNow();
    }

    public boolean isRunning() { return running.get(); }
    public HealthService healthService() { return healthService; }

    /** Returns the process-local status input for a configured TTL check, or empty for any other check. */
    public Optional<LocalStatusReporter> statusReporter(String serviceId, String checkId) {
        return localChecks.reporter(serviceId, checkId);
    }
    ServiceReconciler serviceReconciler() { return serviceReconciler; }
    /** True once shutdown has completed and every client-owned thread, client, and listener has stopped. */
    public boolean isTerminated() {
        return scheduler.isTerminated() && checkScheduler.isTerminated() && checkHttpClient.isTerminated()
                && serverHttpClient.isTerminated() && serverClient.isClosed() && !healthService.isHealthy();
    }
    @Override public void close() { shutdown().join(); }

    public static QraftClient launch(Path configPath) {
        return launch(configPath, QraftClient::new, QraftClient::start, QraftClient::shutdown);
    }

    static <T> T launch(Path configPath,
                        Function<ClientConfiguration, T> resourceFactory,
                        Function<T, CompletableFuture<?>> starter,
                        Function<T, CompletableFuture<?>> shutdown) {
        ClientConfiguration configuration = ClientConfiguration.fromFile(configPath);
        System.setProperty("qraft.log.mode", "client");
        System.setProperty("qraft.log.dir", configuration.getLoggingDirectory());
        T resource = resourceFactory.apply(configuration);
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
}
