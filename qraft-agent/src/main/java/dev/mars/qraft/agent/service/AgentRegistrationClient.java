package dev.mars.qraft.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.agent.AgentInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure Java HTTP client for agent registration with the controller. */
public final class AgentRegistrationClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI controllerBaseUri;
    private final Duration requestTimeout;
    private final AtomicBoolean registered = new AtomicBoolean();

    public AgentRegistrationClient(HttpClient httpClient, ObjectMapper objectMapper,
                                   URI controllerBaseUri, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient);
        this.objectMapper = Objects.requireNonNull(objectMapper).findAndRegisterModules();
        this.controllerBaseUri = Objects.requireNonNull(controllerBaseUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
    }

    public CompletableFuture<Boolean> register(AgentInfo agent) {
        try {
            return send("POST", endpoint("/agents/register"), objectMapper.writeValueAsString(agent))
                    .thenApply(response -> response != null
                            && (response.statusCode() == 200 || response.statusCode() == 201))
                    .whenComplete((success, error) -> registered.set(Boolean.TRUE.equals(success)));
        } catch (IOException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<Boolean> deregister(String agentId) {
        return send("DELETE", endpoint("/agents/" + agentId), null)
                .thenApply(response -> response != null && (response.statusCode() == 200
                        || response.statusCode() == 204
                        || response.statusCode() == 404))
                .whenComplete((success, error) -> { if (Boolean.TRUE.equals(success)) registered.set(false); });
    }

    public boolean isRegistered() { return registered.get(); }

    private CompletableFuture<HttpResponse<String>> send(String method, URI uri, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .exceptionally(error -> null);
    }

    private URI endpoint(String path) {
        String base = controllerBaseUri.toString();
        return URI.create(base.replaceAll("/$", "") + path);
    }

}
