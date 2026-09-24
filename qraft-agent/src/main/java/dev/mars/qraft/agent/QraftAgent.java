package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.agent.catalog.ControllerContactTracker;
import dev.mars.qraft.agent.catalog.ControllerRetryPolicy;
import dev.mars.qraft.agent.catalog.HttpCatalogClient;
import dev.mars.qraft.agent.catalog.ServiceReconciler;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.agent.service.AgentRegistrationClient;
import dev.mars.qraft.agent.service.HealthService;
import dev.mars.qraft.agent.service.HeartbeatService;
import dev.mars.qraft.agent.service.ReadinessPolicy;
import dev.mars.qraft.config.ConfigFileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Pure Java discovery agent lifecycle. */
public final class QraftAgent implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(QraftAgent.class);

    private final AgentConfiguration config;
    private final HttpCatalogClient controllerClient;
    private final AgentRegistrationClient registrationClient;
    private final HeartbeatService heartbeatService;
    private final HealthService healthService;
    private final ControllerRetryPolicy retryPolicy;
    private final ServiceReconciler serviceReconciler;
    private final ReadinessPolicy readinessPolicy;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean();
    private final AtomicBoolean reconciliationScheduled = new AtomicBoolean();
    private final AtomicBoolean registrationRetryScheduled = new AtomicBoolean();
    private final AtomicInteger registrationRetryNumber = new AtomicInteger();
    private final AtomicReference<CompletableFuture<Void>> pendingRetryDelay = new AtomicReference<>();
    private CompletableFuture<Boolean> shutdownFuture;

    public QraftAgent(AgentConfiguration config) {
        this(config, new ControllerRetryPolicy(config.getRegistrationRetryMinMs(),
                config.getRegistrationRetryMaxMs(), () -> ThreadLocalRandom.current().nextDouble()),
                Clock.systemUTC());
    }

    QraftAgent(AgentConfiguration config, ControllerRetryPolicy retryPolicy) {
        this(config, retryPolicy, Clock.systemUTC());
    }

    QraftAgent(AgentConfiguration config, ControllerRetryPolicy retryPolicy, Clock clock) {
        this.config = config;
        this.retryPolicy = retryPolicy;
        ControllerContactTracker contactTracker = new ControllerContactTracker(clock);
        this.controllerClient = new HttpCatalogClient(HttpClient.newHttpClient(), new ObjectMapper(),
                config.getControllerUrls(), config.getAgentId(), config.getTenant(), config.getNamespace(),
                config.getDatacenter(), config.getRegion(), Duration.ofMillis(config.getRequestTimeoutMs()),
                contactTracker);
        this.registrationClient = new AgentRegistrationClient(controllerClient);
        this.heartbeatService = new HeartbeatService(config, registrationClient);
        this.serviceReconciler = new ServiceReconciler(controllerClient, config::getServices, clock);
        this.readinessPolicy = new ReadinessPolicy(running::get, registrationClient::isRegistered,
                serviceReconciler::isConverged, contactTracker,
                Duration.ofMillis(config.getContactFreshnessMs()), clock);
        this.healthService = new HealthService(config, readinessPolicy::isReady);
    }

    public CompletableFuture<Boolean> start() {
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(registrationClient.isRegistered());
        }
        healthService.start();
        AgentInfo agent = new AgentInfo(config.getAgentId(), config.getHostname(), config.getAddress(), config.getAgentPort());
        agent.setVersion(config.getVersion());
        agent.setRegion(config.getRegion());
        agent.setDatacenter(config.getDatacenter());
        return registrationClient.register(agent).thenApply(registered -> {
            if (registered) activateHeartbeat(agent);
            else if (registrationClient.shouldRetryRegistration()) scheduleRegistrationRetry(agent);
            return registered;
        });
    }

    private void scheduleRegistrationRetry(AgentInfo agent) {
        if (!running.get() || registrationClient.isRegistered()) return;
        if (!registrationRetryScheduled.compareAndSet(false, true)) return;
        try {
            CompletableFuture<Void> delay = retryPolicy.delay(
                    scheduler, registrationRetryNumber.getAndIncrement());
            pendingRetryDelay.set(delay);
            delay.thenRun(() -> registrationClient.register(agent).whenComplete((registered, error) -> {
                registrationRetryScheduled.set(false);
                pendingRetryDelay.set(null);
                if (!running.get()) return;
                if (error == null && Boolean.TRUE.equals(registered)) activateHeartbeat(agent);
                else if (registrationClient.shouldRetryRegistration()) scheduleRegistrationRetry(agent);
            }));
        } catch (RejectedExecutionException ignored) {
            registrationRetryScheduled.set(false);
            // Shutdown won the race with a registration callback.
        }
    }

    private void activateHeartbeat(AgentInfo agent) {
        if (!running.get()) return;
        registrationRetryNumber.set(0);
        serviceReconciler.trigger();
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
                                    scheduleRegistrationRetry(agent);
                                }
                            });
                    },
                    config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        }
    }

    public synchronized CompletableFuture<Boolean> shutdown() {
        if (shutdownFuture != null) return shutdownFuture;

        running.set(false);
        CompletableFuture<ServiceReconciler.ShutdownResult> serviceShutdown =
                serviceReconciler.beginShutdown();
        stopScheduledWork();

        CompletableFuture<Boolean> graceful = serviceShutdown.thenCompose(services -> {
            CompletableFuture<Boolean> nodeShutdown =
                    registrationClient.beginShutdownAndDeregister(config.getAgentId());
            return nodeShutdown.handle((nodeRemoved, failure) ->
                    services.complete() && failure == null && Boolean.TRUE.equals(nodeRemoved));
        });
        CompletableFuture<Boolean> bounded = graceful.orTimeout(
                config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS);
        shutdownFuture = bounded.handle((complete, failure) -> {
            boolean succeeded = failure == null && Boolean.TRUE.equals(complete);
            finishShutdown();
            if (failure != null) {
                LOGGER.warn("Agent shutdown incomplete within deadline={}ms; automatic expiry may be required",
                        config.getShutdownTimeoutMs());
            } else if (!Boolean.TRUE.equals(complete)) {
                LOGGER.warn("Agent shutdown incomplete because one or more deregistration attempts failed; "
                        + "automatic expiry may be required");
            }
            return succeeded;
        });
        return shutdownFuture;
    }

    private void stopScheduledWork() {
        CompletableFuture<Void> delay = pendingRetryDelay.getAndSet(null);
        if (delay != null) delay.cancel(false);
        scheduler.shutdownNow();
    }

    private void finishShutdown() {
        healthService.shutdown();
        controllerClient.closeNow();
    }

    public boolean isRunning() { return running.get(); }
    public HealthService healthService() { return healthService; }
    ServiceReconciler serviceReconciler() { return serviceReconciler; }
    boolean resourcesTerminated() {
        return scheduler.isTerminated() && controllerClient.isClosed() && !healthService.isHealthy();
    }
    @Override public void close() { shutdown().join(); }

    public static QraftAgent launch(Path configPath) {
        return launch(configPath, QraftAgent::new, QraftAgent::start, QraftAgent::shutdown);
    }

    static <T> T launch(Path configPath,
                        Function<AgentConfiguration, T> resourceFactory,
                        Function<T, CompletableFuture<?>> starter,
                        Function<T, CompletableFuture<?>> shutdown) {
        AgentConfiguration configuration = AgentConfiguration.fromFile(configPath);
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

    public static void main(String[] args) {
        Path configPath = ConfigFileResolver.resolve(args, "client");
        QraftAgent agent = launch(configPath);
        CountDownLatch shutdownComplete = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            try {
                agent.close();
            } finally {
                shutdownComplete.countDown();
            }
        }));
        try {
            shutdownComplete.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            agent.close();
        }
    }
}
