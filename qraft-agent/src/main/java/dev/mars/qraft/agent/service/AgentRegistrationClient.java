package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.catalog.CatalogOutcome;
import dev.mars.qraft.agent.catalog.HttpCatalogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Agent membership facade over the shared controller HTTP client. */
public final class AgentRegistrationClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRegistrationClient.class);
    private final HttpCatalogClient controllerClient;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean registrationRetryable = new AtomicBoolean();
    private final AtomicReference<String> registrationId = new AtomicReference<>();
    private boolean acceptingRegistrations = true;
    private CompletableFuture<Boolean> registrationInFlight = CompletableFuture.completedFuture(false);
    private CompletableFuture<Boolean> shutdown;

    public AgentRegistrationClient(HttpCatalogClient controllerClient) {
        this.controllerClient = Objects.requireNonNull(controllerClient, "controllerClient");
    }

    public synchronized CompletableFuture<Boolean> register(AgentInfo agent) {
        if (!acceptingRegistrations) return CompletableFuture.completedFuture(false);
        String attemptId = UUID.randomUUID().toString();
        agent.addMetadata(AgentInfo.REGISTRATION_ID_METADATA_KEY, attemptId);
        registrationId.set(attemptId);
        registrationInFlight = controllerClient.registerAgent(agent)
                .thenApply(outcome -> {
                    registrationRetryable.set(outcome instanceof CatalogOutcome.Retryable);
                    if (outcome instanceof CatalogOutcome.Rejected rejected) {
                        LOGGER.warn("Agent registration rejected: agentId={}, code={}, message={}",
                                agent.getAgentId(), rejected.code(), rejected.message());
                    }
                    return outcome instanceof CatalogOutcome.Success;
                })
                .whenComplete((success, error) -> registered.set(Boolean.TRUE.equals(success)));
        return registrationInFlight;
    }

    public CompletableFuture<Boolean> heartbeat(String agentId, Instant timestamp,
                                                long sequenceNumber, String status) {
        if (!registered.get()) return CompletableFuture.completedFuture(false);
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("agentId", agentId);
        heartbeat.put("timestamp", timestamp.toString());
        heartbeat.put("sequenceNumber", sequenceNumber);
        heartbeat.put("status", status);
        String currentRegistrationId = registrationId.get();
        if (currentRegistrationId != null) heartbeat.put("registrationId", currentRegistrationId);
        return controllerClient.heartbeatAgent(heartbeat).thenApply(outcome -> {
            if (outcome instanceof CatalogOutcome.Rejected rejected
                    && ("agent_not_found".equals(rejected.code()) || "http_404".equals(rejected.code()))) {
                registered.set(false);
                return false;
            }
            return outcome instanceof CatalogOutcome.Success;
        });
    }

    public CompletableFuture<Boolean> deregister(String agentId) {
        return controllerClient.deregisterAgent(agentId)
                .thenApply(CatalogOutcome.Success.class::isInstance)
                .whenComplete((success, error) -> {
                    if (Boolean.TRUE.equals(success)) registered.set(false);
                });
    }

    /** Stops new registrations, waits for an active attempt, then always removes the node. */
    public synchronized CompletableFuture<Boolean> beginShutdownAndDeregister(String agentId) {
        if (shutdown != null) return shutdown;
        acceptingRegistrations = false;
        shutdown = registrationInFlight.handle((ignored, failure) -> null)
                .thenCompose(ignored -> deregister(agentId));
        return shutdown;
    }

    public boolean isRegistered() { return registered.get(); }
    public boolean shouldRetryRegistration() { return registrationRetryable.get(); }
    public String registrationId() { return registrationId.get(); }
    public void markRegistrationLost() { registered.set(false); }
}
