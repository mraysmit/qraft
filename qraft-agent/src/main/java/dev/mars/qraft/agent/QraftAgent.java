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

    public QraftAgent(AgentConfiguration config) {
        this.config = config;
        this.registrationClient = new AgentRegistrationClient(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create(config.getControllerUrl()), Duration.ofMillis(config.getHttpConnectionTimeout()));
        this.heartbeatService = new HeartbeatService(config, registrationClient);
        this.healthService = new HealthService(config);
    }

    public CompletableFuture<Boolean> start() {
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(true);
        healthService.start();
        AgentInfo agent = new AgentInfo(config.getAgentId(), config.getHostname(), config.getAddress(), config.getAgentPort());
        agent.setVersion(config.getVersion());
        agent.setRegion(config.getRegion());
        agent.setDatacenter(config.getDatacenter());
        return registrationClient.register(agent).thenApply(registered -> {
            healthService.setReady(registered);
            if (registered) {
                scheduler.scheduleAtFixedRate(heartbeatService::sendHeartbeat,
                        config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
            } else {
                stopLocal();
            }
            return registered;
        });
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
