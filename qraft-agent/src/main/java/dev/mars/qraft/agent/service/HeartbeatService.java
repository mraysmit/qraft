
package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.config.AgentConfiguration;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Pure Java heartbeat publisher for the discovery agent. */
public final class HeartbeatService {
    private final AgentConfiguration config;
    private final AgentRegistrationClient registrationClient;
    private final AtomicLong sequence = new AtomicLong();

    public HeartbeatService(AgentConfiguration config, AgentRegistrationClient registrationClient) {
        this.config = config;
        this.registrationClient = registrationClient;
    }

    public CompletableFuture<Boolean> sendHeartbeat() {
        if (!registrationClient.isRegistered()) return CompletableFuture.completedFuture(false);
        return registrationClient.heartbeat(config.getAgentId(), Instant.now(),
                sequence.incrementAndGet(), "passing");
    }
}
