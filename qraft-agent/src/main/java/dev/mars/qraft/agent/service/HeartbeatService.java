
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
import java.util.HashMap;
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
            Map<String, Object> heartbeat = new HashMap<>();
            heartbeat.put("agentId", config.getAgentId());
            heartbeat.put("timestamp", Instant.now().toString());
            heartbeat.put("sequenceNumber", sequence.incrementAndGet());
            heartbeat.put("status", "passing");
            String registrationId = registrationClient.registrationId();
            if (registrationId != null) heartbeat.put("registrationId", registrationId);
            String body = objectMapper.writeValueAsString(heartbeat);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getControllerUrl() + "/agents/heartbeat"))
                    .timeout(Duration.ofMillis(config.getHttpConnectionTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> {
                        if (response.statusCode() == 404) {
                            registrationClient.markRegistrationLost();
                            return false;
                        }
                        return response.statusCode() == 200 || response.statusCode() == 204;
                    })
                    .exceptionally(error -> false);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
