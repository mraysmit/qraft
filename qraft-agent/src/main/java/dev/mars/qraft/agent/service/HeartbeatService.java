
package dev.mars.qraft.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.agent.config.AgentConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Pure Java heartbeat publisher for the discovery agent. */
public final class HeartbeatService {
    private final AgentConfiguration config;
    private final AgentRegistrationClient registrationClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();

    public HeartbeatService(AgentConfiguration config, AgentRegistrationClient registrationClient) {
        this.config = config;
        this.registrationClient = registrationClient;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public CompletableFuture<Boolean> sendHeartbeat() {
        if (!registrationClient.isRegistered()) return CompletableFuture.completedFuture(false);
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "agentId", config.getAgentId(), "timestamp", Instant.now().toString(),
                    "sequenceNumber", sequence.incrementAndGet(), "status", "passing"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getControllerUrl() + "/agents/heartbeat"))
                    .timeout(Duration.ofMillis(config.getHttpConnectionTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> response.statusCode() == 200 || response.statusCode() == 204)
                    .exceptionally(error -> false);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
