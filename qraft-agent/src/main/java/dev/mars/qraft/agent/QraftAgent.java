package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.agent.config.AgentConfiguration;
import dev.mars.qraft.agent.service.AgentRegistrationClient;
import dev.mars.qraft.agent.service.HealthService;
import dev.mars.qraft.agent.service.HeartbeatService;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure Java discovery agent lifecycle. */
public final class QraftAgent implements AutoCloseable {
    private final AgentConfiguration config;
    private final AgentRegistrationClient registrationClient;
    private final HeartbeatService heartbeatService;
    private final HealthService healthService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean();
    private final AtomicBoolean registrationRetryScheduled = new AtomicBoolean();

    public QraftAgent(AgentConfiguration config) {
        this.config = config;
        this.registrationClient = new AgentRegistrationClient(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create(config.getControllerUrl()), Duration.ofMillis(config.getHttpConnectionTimeout()));
        this.heartbeatService = new HeartbeatService(config, registrationClient);
        this.healthService = new HealthService(config);
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
            else scheduleRegistrationRetry(agent);
            return registered;
        });
    }

    private void scheduleRegistrationRetry(AgentInfo agent) {
        if (!running.get() || registrationClient.isRegistered()) return;
        if (!registrationRetryScheduled.compareAndSet(false, true)) return;
        try {
            scheduler.schedule(() -> registrationClient.register(agent).whenComplete((registered, error) -> {
                registrationRetryScheduled.set(false);
                if (!running.get()) return;
                if (error == null && Boolean.TRUE.equals(registered)) activateHeartbeat(agent);
                else scheduleRegistrationRetry(agent);
            }), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            registrationRetryScheduled.set(false);
            // Shutdown won the race with a registration callback.
        }
    }

    private void activateHeartbeat(AgentInfo agent) {
        if (!running.get()) return;
        healthService.setReady(true);
        if (heartbeatScheduled.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(() -> heartbeatService.sendHeartbeat()
                            .whenComplete((accepted, error) -> {
                                if (running.get() && !registrationClient.isRegistered()) {
                                    healthService.setReady(false);
                                    scheduleRegistrationRetry(agent);
                                }
                            }),
                    config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        }
    }

    public CompletableFuture<Boolean> shutdown() {
        if (!running.getAndSet(false)) return CompletableFuture.completedFuture(true);
        stopLocal();
        return registrationClient.deregister(config.getAgentId());
    }

    private void stopLocal() { scheduler.shutdownNow(); healthService.shutdown(); }
    public boolean isRunning() { return running.get(); }
    public HealthService healthService() { return healthService; }
    @Override public void close() { shutdown().join(); }

    public static void main(String[] args) {
        QraftAgent agent = new QraftAgent(AgentConfiguration.fromEnvironment());
        Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
        agent.start().join();
    }
}
