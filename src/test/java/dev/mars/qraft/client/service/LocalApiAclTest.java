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

package dev.mars.qraft.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.acl.AclLookup;
import dev.mars.qraft.client.acl.AclSource;
import dev.mars.qraft.client.acl.ClientAclResolver;
import dev.mars.qraft.client.acl.ClientAclSettings;
import dev.mars.qraft.client.acl.ClientAuthorizer;
import dev.mars.qraft.client.acl.ClientTokens;
import dev.mars.qraft.client.acl.DownPolicy;
import dev.mars.qraft.client.acl.TokenKind;
import dev.mars.qraft.client.catalog.ForwardedRead;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a client's local API with ACLs enabled (ACL list, Phase 6): one test for each row of proposal 11's client
 * table, deciding with the shared authorizer from what the servers resolve for the caller's token; a refusal is 403
 * {@code permission_denied} in Consul's words, an unknown secret 403 {@code token_not_found}, and a service or check
 * that does not exist 404, decided before its authorization since there is nothing to authorize; reads are filtered;
 * forwarded reads go under the caller's token, else the default token; a registration keeps the token it was made with;
 * health needs no token; and with no server, a cached token still reports its TTL check and the recovery token still
 * leaves, under each down policy.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class LocalApiAclTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = "client-1";
    private static final String FILTERED = "X-Qraft-Results-Filtered-By-ACLs";

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> answers = new HashMap<>();
    private final List<Map<String, String>> forwarded = new CopyOnWriteArrayList<>();
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private final AtomicBoolean departed = new AtomicBoolean();
    private volatile boolean reachable = true;
    private LocalDefinitions definitions;
    private ClientTokens tokens;
    private LocalApiServer api;

    private final AclSource source = secret -> {
        if (!reachable) return CompletableFuture.completedFuture(new AclLookup.Unavailable("no server answered"));
        String answer = answers.get(String.valueOf(secret));
        try {
            return CompletableFuture.completedFuture(answer == null ? new AclLookup.NotFound()
                    : new AclLookup.Found(JSON.readTree(answer)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    @AfterEach
    void stopApi() {
        if (api != null) api.shutdown();
    }

    // ---- The client's own routes ----

    @Test
    void selfNeedsClientRead() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("GET", "/v1/client/self", null, "nobody"), "client:read");
        assertEquals(200, send("GET", "/v1/client/self", null, "agent").statusCode());
    }

    @Test
    void membersIsForwardedUnderTheCallersTokenElseTheDefault() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertEquals(200, send("GET", "/v1/client/members", null, "nobody").statusCode(), "the server filters");
        assertEquals("nobody", forwarded.getLast().get("X-Qraft-Token"));
        send("GET", "/v1/client/members", null, null);
        assertEquals("default-secret", forwarded.getLast().get("X-Qraft-Token"));
    }

    @Test
    void leaveNeedsClientWrite() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("PUT", "/v1/client/leave", null, "nobody"), "client:write");
        assertFalse(departed.get(), "a refused leave does not leave");
        assertEquals(200, send("PUT", "/v1/client/leave", null, "agent").statusCode());
        assertTrue(departed.get());
    }

    @Test
    void settingATokenNeedsClientWriteAndReplacesIt() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("PUT", "/v1/client/token/default", "{\"token\":\"new-default\"}", "nobody"),
                "client:write");
        HttpResponse<String> set = send("PUT", "/v1/client/token/default", "{\"token\":\"new-default\"}", "agent");
        assertEquals(200, set.statusCode(), set.body());
        assertFalse(set.body().contains("new-default"), "the answer never holds the token");
        assertEquals(Optional.of("new-default"), tokens.defaultToken());
        assertEquals(400, send("PUT", "/v1/client/token/agent", "{\"token\":\"x\"}", "agent").statusCode());
        assertEquals(400, send("PUT", "/v1/client/token/client", "{}", "agent").statusCode());
    }

    @Test
    void nodeMaintenanceNeedsNodeWrite() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("PUT", "/v1/client/maintenance?enable=true", null, "file-writer"), "node:write");
        assertEquals(200, send("PUT", "/v1/client/maintenance?enable=true", null, "agent").statusCode());
    }

    // ---- Services ----

    @Test
    void registeringAServiceNeedsServiceWriteOnItAndOnTheInstanceItReplaces() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("PUT", "/v1/client/service/register", "{\"id\":\"web-1\",\"name\":\"web\",\"port\":80}",
                "nobody"), "service:write");
        assertEquals(200, send("PUT", "/v1/client/service/register",
                "{\"id\":\"web-1\",\"name\":\"web\",\"port\":80}", "web-writer").statusCode());
        assertEquals(Optional.of("web-writer"), definitions.registrationToken("web-1"), "the token is kept with it");

        // Replacing the file's instance "file" with one named web needs service:write on file too.
        HttpResponse<String> replacing = send("PUT", "/v1/client/service/register",
                "{\"id\":\"file\",\"name\":\"web\",\"port\":81}", "web-writer");
        assertRefused(replacing, "service:write");
        assertTrue(json(replacing).path("message").asText().contains("\"file\""), replacing.body());
    }

    @Test
    void deregisteringAServiceNeedsServiceWriteOnItAndAnUnknownOneIsNotFound() throws Exception {
        start(DownPolicy.EXTEND_CACHE);
        send("PUT", "/v1/client/service/register", "{\"id\":\"web-1\",\"name\":\"web\",\"port\":80}", "web-writer");

        assertRefused(send("PUT", "/v1/client/service/deregister/web-1", null, "file-writer"), "service:write");
        assertEquals(200, send("PUT", "/v1/client/service/deregister/web-1", null, "web-writer").statusCode());
        HttpResponse<String> unknown = send("PUT", "/v1/client/service/deregister/absent", null, "web-writer");
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertEquals("service_not_found", json(unknown).path("code").asText());
    }

    @Test
    void theServiceAndCheckListsAreFilteredByServiceRead() throws Exception {
        start(DownPolicy.EXTEND_CACHE);
        send("PUT", "/v1/client/service/register", "{\"id\":\"web-1\",\"name\":\"web\",\"port\":80}", "web-writer");

        HttpResponse<String> all = send("GET", "/v1/client/services", null, "reader");
        assertEquals(List.of("file", "web-1"), fieldNames(all));
        assertFalse(all.headers().firstValue(FILTERED).isPresent());
        HttpResponse<String> some = send("GET", "/v1/client/services", null, "web-writer");
        assertEquals(List.of("web-1"), fieldNames(some), "web-writer reads only web");
        assertEquals("true", some.headers().firstValue(FILTERED).orElse(""));
        assertEquals(List.of(), fieldNames(send("GET", "/v1/client/services", null, "nobody")));

        assertEquals(List.of("file-check"), fieldNames(send("GET", "/v1/client/checks", null, "reader")));
        assertEquals(List.of(), fieldNames(send("GET", "/v1/client/checks", null, "web-writer")));
        assertEquals("token_not_found", json(send("GET", "/v1/client/services", null, "unknown")).path("code")
                .asText());
    }

    @Test
    void readingOneServiceNeedsServiceReadAndAnswers403WithoutIt() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("GET", "/v1/client/service/file", null, "nobody"), "service:read");
        assertEquals(200, send("GET", "/v1/client/service/file", null, "reader").statusCode());
        assertEquals(404, send("GET", "/v1/client/service/absent", null, "reader").statusCode());
    }

    @Test
    void serviceMaintenanceNeedsServiceWriteOnTheService() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        assertRefused(send("PUT", "/v1/client/service/maintenance/file?enable=true", null, "web-writer"),
                "service:write");
        assertEquals(200, send("PUT", "/v1/client/service/maintenance/file?enable=true", null, "file-writer")
                .statusCode());
        assertEquals(404, send("PUT", "/v1/client/service/maintenance/absent?enable=true", null, "file-writer")
                .statusCode());
    }

    // ---- Checks ----

    @Test
    void registeringACheckNeedsServiceWriteOnItsService() throws Exception {
        start(DownPolicy.EXTEND_CACHE);
        String check = "{\"id\":\"extra\",\"serviceId\":\"file\",\"type\":\"ttl\",\"ttlMs\":15000,\"required\":false}";

        assertRefused(send("PUT", "/v1/client/check/register", check, "web-writer"), "service:write");
        HttpResponse<String> registered = send("PUT", "/v1/client/check/register", check, "file-writer");
        assertEquals(200, registered.statusCode(), registered.body());
    }

    @Test
    void reportingOrRemovingACheckNeedsServiceWriteOnItsServiceAndAnUnknownOneIsNotFound() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        for (String path : List.of("/v1/client/check/pass/file-check", "/v1/client/check/warn/file-check",
                "/v1/client/check/fail/file-check", "/v1/client/check/deregister/file-check")) {
            assertRefused(send("PUT", path, null, "web-writer"), "service:write");
        }
        assertRefused(send("PUT", "/v1/client/check/update/file-check", "{\"status\":\"passing\"}", "web-writer"),
                "service:write");
        assertEquals(200, send("PUT", "/v1/client/check/pass/file-check", null, "file-writer").statusCode());
        assertEquals(List.of("PASSING:"), reports);
        assertEquals(200, send("PUT", "/v1/client/check/deregister/file-check", null, "file-writer").statusCode());
        HttpResponse<String> unknown = send("PUT", "/v1/client/check/deregister/absent", null, "file-writer");
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertEquals("check_not_found", json(unknown).path("code").asText());
    }

    // ---- Forwarded reads, health, and tokens ----

    @Test
    void discoveryReadsAreForwardedUnderTheCallersTokenElseTheDefault() throws Exception {
        start(DownPolicy.EXTEND_CACHE);

        for (String path : List.of("/v1/catalog/services", "/v1/catalog/service/web", "/v1/catalog/nodes",
                "/v1/health/service/web")) {
            send("GET", path, null, "reader");
            assertEquals("reader", forwarded.getLast().get("X-Qraft-Token"), path);
            send("GET", path, null, null);
            assertEquals("default-secret", forwarded.getLast().get("X-Qraft-Token"), path);
        }
    }

    @Test
    void healthNeedsNoTokenEver() throws Exception {
        start(DownPolicy.DENY);
        reachable = false;

        assertEquals(200, send("GET", "/health/live", null, null).statusCode());
        assertEquals(200, send("GET", "/health/live", null, "unknown").statusCode());
        assertEquals(200, send("GET", "/health/ready", null, "unknown").statusCode());
    }

    @Test
    void aRequestWithoutATokenActsAsTheDefaultToken() throws Exception {
        start(DownPolicy.EXTEND_CACHE);
        answers.put("default-secret", answer("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "{\"service_prefix\":{\"\":{\"policy\":\"read\"}}}"));

        assertEquals(200, send("GET", "/v1/client/service/file", null, null).statusCode());
    }

    // ---- With no server ----

    @Test
    void withNoServerACachedTokenReportsItsCheckAndTheRecoveryTokenLeavesUnderEachDownPolicy() throws Exception {
        for (DownPolicy policy : DownPolicy.values()) {
            start(policy);
            assertEquals(200, send("PUT", "/v1/client/check/pass/file-check", null, "file-writer").statusCode(),
                    policy + ": caching the token while a server answers");
            reachable = false;

            // The entry is fresh, so every policy uses it; how each treats a stale one is ClientAclResolverTest's.
            HttpResponse<String> report = send("PUT", "/v1/client/check/pass/file-check", null, "file-writer");
            assertEquals(200, report.statusCode(), policy + ": the cached token reports its check: " + report.body());
            HttpResponse<String> leave = send("PUT", "/v1/client/leave", null, "recovery-secret");
            assertEquals(200, leave.statusCode(), policy + ": the recovery token needs no server: " + leave.body());

            api.shutdown();
            api = null;
            reachable = true;
            departed.set(false);
        }
    }

    // ---- Helpers ----

    private void start(DownPolicy downPolicy) throws Exception {
        Path data = Files.createDirectories(directory.resolve(downPolicy.setting()));
        answers.put("nobody", answer("11111111-1111-4111-8111-111111111111", "{}"));
        answers.put("agent", answer("22222222-2222-4222-8222-222222222222",
                "{\"client\":{\"client-1\":{\"policy\":\"write\"}},\"node\":{\"client-1\":{\"policy\":\"write\"}}}"));
        answers.put("web-writer", answer("33333333-3333-4333-8333-333333333333",
                "{\"service\":{\"web\":{\"policy\":\"write\"}}}"));
        answers.put("file-writer", answer("44444444-4444-4444-8444-444444444444",
                "{\"service\":{\"file\":{\"policy\":\"write\"}}}"));
        answers.put("reader", answer("55555555-5555-4555-8555-555555555555",
                "{\"service_prefix\":{\"\":{\"policy\":\"read\"}}}"));
        ClientAclSettings settings = new ClientAclSettings(true, AclDefaultPolicy.DENY, downPolicy,
                ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, false,
                Map.of(TokenKind.DEFAULT, Files.writeString(data.resolve("default"), "default-secret"),
                        TokenKind.RECOVERY, Files.writeString(data.resolve("recovery"), "recovery-secret")));
        tokens = ClientTokens.load(settings, data);
        definitions = LocalDefinitions.load(
                List.of(new ServiceDefinition("file", "file", "10.0.0.9", 7000, List.of(), Map.of(), true)),
                List.of(new TtlCheck("file", "file-check", Duration.ofSeconds(30), true)),
                new RegistrationStore(data));
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName(NODE).dataDirectory(data).serverUrl("http://localhost").clientPort(0).acl(settings).build();
        ClientAuthorizer acl = new ClientAuthorizer(new ClientAclResolver(settings, NODE, tokens, source,
                Clock.systemUTC()), tokens, NODE, "default", "default");
        api = new LocalApiServer(config, () -> true);
        new LocalRegistrationRoutes(definitions, (serviceId, checkId) -> Optional.empty(),
                (serviceId, checkId) -> "file-check".equals(checkId) ? Optional.of((status, output) -> {
                    reports.add(status + ":" + output);
                    return true;
                }) : Optional.empty(), "10.0.0.5", acl).install(api);
        LocalDiscoveryRoutes.ForwardedReads reads = (pathAndQuery, requestId, headers) -> {
            forwarded.add(Map.copyOf(headers));
            return CompletableFuture.completedFuture(Optional.of(new ForwardedRead(200, "[]", Optional.empty(),
                    Optional.empty())));
        };
        new LocalClientRoutes(config, "6a1b7c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d",
                () -> new ReadinessPolicy.Conditions(true, true, true, true, true),
                new ServerContactTracker(Clock.systemUTC()), reads, () -> {
                    departed.set(true);
                    return CompletableFuture.completedFuture(true);
                }, () -> { }, acl).install(api);
        new LocalDiscoveryRoutes(reads, acl).install(api);
        api.start();
    }

    /** The servers' resolve answer for a token linked to one policy with {@code rules}. */
    private static String answer(String accessor, String rules) {
        return """
                {"token":{"accessorId":"%s","secretId":"<hidden>","policies":[{"id":"%s","name":"p-%s"}],
                          "roles":[],"nodeIdentities":[],"serviceIdentities":[]},
                 "policies":[{"id":"%s","name":"p-%s","rules":%s}],"roles":[]}"""
                .formatted(accessor, accessor, accessor, accessor, accessor, rules);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("X-Qraft-Token", token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static List<String> fieldNames(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        List<String> names = new java.util.ArrayList<>();
        json(response).fieldNames().forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private static void assertRefused(HttpResponse<String> response, String permission) throws Exception {
        assertEquals(403, response.statusCode(), response.body());
        assertEquals("permission_denied", json(response).path("code").asText(), response.body());
        assertTrue(json(response).path("message").asText().contains("lacks permission '" + permission + "'"),
                response.body());
    }
}
