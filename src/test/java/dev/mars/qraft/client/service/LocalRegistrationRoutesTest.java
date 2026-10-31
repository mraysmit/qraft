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
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.client.health.CheckResult;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.http.JsonApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the local API's service and check routes over real HTTP against a real listener, as Consul's agent serves
 * {@code /v1/agent/service/*} and {@code /v1/agent/check/*} (read in {@code agent/agent_endpoint.go} on 2026-10-31).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
class LocalRegistrationRoutesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WEB = "{\"id\":\"web-1\",\"name\":\"web\",\"port\":8080}";

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, CheckResult> results = new HashMap<>();
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private LocalDefinitions definitions;
    private LocalApiServer api;

    @BeforeEach
    void startApi() throws Exception {
        definitions = LocalDefinitions.load(
                List.of(new ServiceDefinition("file", "file", "10.0.0.9", 7000, List.of(), Map.of(), true)),
                List.of(new TtlCheck("file", "file-check", Duration.ofSeconds(30), true)),
                new RegistrationStore(directory));
        ClientConfiguration config = ClientConfiguration.builder()
                .nodeName("client-1").dataDirectory(directory).serverUrl("http://localhost").clientPort(0).build();
        api = new LocalApiServer(config, () -> true);
        new LocalRegistrationRoutes(definitions,
                (serviceId, checkId) -> Optional.ofNullable(results.get(serviceId + "/" + checkId)),
                (serviceId, checkId) -> "file-check".equals(checkId) ? Optional.of((status, output) -> {
                    reports.add(status + ":" + output);
                    return running;
                }) : Optional.empty(),
                "10.0.0.5").install(api);
        api.start();
    }

    @AfterEach
    void stopApi() {
        api.shutdown();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).path("code").asText(), response.body());
        assertFalse(json(response).path("requestId").asText().isEmpty(), "the error envelope carries the request ID");
    }

    @Test
    void aServiceIsRegisteredAndReadBackWithTheClientsAddressAsItsDefault() throws Exception {
        HttpResponse<String> registered = send("PUT", "/v1/client/service/register", """
                {"id":"web-1","name":"web","port":8080,"tags":["v1"],"metadata":{"team":"platform"}}
                """);

        assertEquals(200, registered.statusCode(), registered.body());
        assertEquals("web-1", json(registered).path("serviceId").asText());
        HttpResponse<String> read = send("GET", "/v1/client/service/web-1", null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(JSON.readTree("""
                {"id":"web-1","name":"web","address":"10.0.0.5","port":8080,"tags":["v1"],
                 "metadata":{"team":"platform"}}
                """), json(read));
        assertTrue(definitions.service("web-1").isPresent());
    }

    @Test
    void aServicesIdDefaultsToItsNameAsConsulsDoes() throws Exception {
        HttpResponse<String> registered = send("PUT", "/v1/client/service/register",
                "{\"name\":\"web\",\"port\":8080}");

        assertEquals("web", json(registered).path("serviceId").asText());
        assertEquals(200, send("GET", "/v1/client/service/web", null).statusCode());
    }

    @Test
    void servicesAreListedByIdWithThoseTheFileDefines() throws Exception {
        send("PUT", "/v1/client/service/register", WEB);

        HttpResponse<String> listed = send("GET", "/v1/client/services", null);

        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode services = json(listed);
        assertEquals(2, services.size());
        assertEquals("web", services.path("web-1").path("name").asText());
        assertEquals("10.0.0.9", services.path("file").path("address").asText());
    }

    @Test
    void anUnknownServiceIsNotFoundAsConsulAnswers() throws Exception {
        assertRefused(send("GET", "/v1/client/service/nobody", null), 404, "service_not_found");
    }

    @Test
    void aServiceIsRegisteredWithItsChecks() throws Exception {
        HttpResponse<String> registered = send("PUT", "/v1/client/service/register", """
                {"id":"web-1","name":"web","port":8080,
                 "checks":[{"id":"ttl","type":"ttl","ttlMs":15000}]}
                """);

        assertEquals(200, registered.statusCode(), registered.body());
        assertEquals(List.of("ttl"), definitions.service("web-1").orElseThrow().checkIds());
    }

    @Test
    void aRegistrationKeepsTheServicesOtherChecksUnlessAskedToReplaceThem() throws Exception {
        String withCheck = "{\"id\":\"web-1\",\"name\":\"web\",\"port\":8080,"
                + "\"checks\":[{\"id\":\"first\",\"type\":\"ttl\",\"ttlMs\":15000}]}";
        String withOther = "{\"id\":\"web-1\",\"name\":\"web\",\"port\":8080,"
                + "\"checks\":[{\"id\":\"second\",\"type\":\"ttl\",\"ttlMs\":15000}]}";
        send("PUT", "/v1/client/service/register", withCheck);

        send("PUT", "/v1/client/service/register", withOther);
        assertEquals(List.of("first", "second"), definitions.service("web-1").orElseThrow().checkIds());

        send("PUT", "/v1/client/service/register?replace-existing-checks=false", withOther);
        assertEquals(List.of("first", "second"), definitions.service("web-1").orElseThrow().checkIds());

        send("PUT", "/v1/client/service/register?replace-existing-checks", withCheck);
        assertEquals(List.of("first"), definitions.service("web-1").orElseThrow().checkIds(),
                "as in Consul, the parameter with no value asks for it");

        send("PUT", "/v1/client/service/register?replace-existing-checks=true", withOther);
        assertEquals(List.of("second"), definitions.service("web-1").orElseThrow().checkIds());
    }

    @Test
    void anInvalidRegistrationIsRefusedAndChangesNothing() throws Exception {
        for (String body : List.of("{ not json", "[]", "", "{\"port\":8080}", "{\"name\":\"web\"}",
                "{\"name\":\"web\",\"port\":0}", "{\"name\":\"web\",\"port\":8080,\"unknown\":true}",
                "{\"name\":\"web\",\"port\":8080,\"checks\":[{\"id\":\"c\",\"type\":\"nonsense\"}]}",
                "{\"name\":\"web\",\"port\":8080,\"checks\":[{\"id\":\"c\",\"type\":\"ttl\",\"ttlMs\":1},"
                        + "{\"id\":\"c\",\"type\":\"ttl\",\"ttlMs\":1}]}")) {
            assertRefused(send("PUT", "/v1/client/service/register", body), 400, "invalid_registration");
        }

        assertEquals(1, json(send("GET", "/v1/client/services", null)).size());
    }

    @Test
    void aServiceIsDeregisteredAndAnUnknownOneIsNotAnErrorAsInConsul() throws Exception {
        send("PUT", "/v1/client/service/register", WEB);

        HttpResponse<String> removed = send("PUT", "/v1/client/service/deregister/web-1", null);
        assertEquals(200, removed.statusCode(), removed.body());
        assertEquals("web-1", json(removed).path("serviceId").asText());
        assertTrue(json(removed).path("deregistered").asBoolean());
        assertEquals(404, send("GET", "/v1/client/service/web-1", null).statusCode());

        HttpResponse<String> again = send("PUT", "/v1/client/service/deregister/web-1", null);
        assertEquals(200, again.statusCode(), again.body());
        assertFalse(json(again).path("deregistered").asBoolean());
    }

    @Test
    void aCheckIsRegisteredForItsServiceAndListedWithItsLatestResult() throws Exception {
        HttpResponse<String> registered = send("PUT", "/v1/client/check/register",
                "{\"id\":\"ttl\",\"serviceId\":\"file\",\"type\":\"ttl\",\"ttlMs\":15000,\"required\":false}");

        assertEquals(200, registered.statusCode(), registered.body());
        assertEquals("ttl", json(registered).path("checkId").asText());
        assertEquals("file", json(registered).path("serviceId").asText());
        assertEquals(List.of("file-check", "ttl"), definitions.service("file").orElseThrow().checkIds());

        results.put("file/ttl", new CheckResult(CheckStatus.WARNING, "slow", Instant.parse("2026-10-31T01:02:03Z")));
        JsonNode checks = json(send("GET", "/v1/client/checks", null));
        assertEquals(2, checks.size());
        JsonNode ttl = checks.path("ttl");
        assertEquals("file", ttl.path("serviceId").asText());
        assertEquals("ttl", ttl.path("type").asText());
        assertEquals(15_000, ttl.path("ttlMs").asLong());
        assertEquals("warning", ttl.path("status").asText());
        assertEquals("slow", ttl.path("output").asText());
        assertEquals("2026-10-31T01:02:03Z", ttl.path("observedAt").asText());
        assertFalse(checks.path("file-check").has("status"), "a check that has not reported has no status yet");
    }

    @Test
    void aCheckForAnUnknownServiceIsNotFoundAsConsulAnswers() throws Exception {
        assertRefused(send("PUT", "/v1/client/check/register",
                "{\"id\":\"ttl\",\"serviceId\":\"nobody\",\"type\":\"ttl\",\"ttlMs\":15000}"), 404,
                "service_not_found");
    }

    @Test
    void anInvalidCheckIsRefusedAndChangesNothing() throws Exception {
        for (String body : List.of("{ not json", "[]", "", "{\"id\":\"ttl\",\"type\":\"ttl\",\"ttlMs\":1000}",
                "{\"serviceId\":\"file\",\"type\":\"ttl\",\"ttlMs\":1000}",
                "{\"id\":\"ttl\",\"serviceId\":\"file\",\"type\":\"ttl\"}",
                "{\"id\":\"ttl\",\"serviceId\":\"file\",\"type\":\"nonsense\"}",
                "{\"id\":\"ttl\",\"serviceId\":\"file\",\"type\":\"ttl\",\"ttlMs\":1000,\"unknown\":true}")) {
            assertRefused(send("PUT", "/v1/client/check/register", body), 400, "invalid_registration");
        }

        assertEquals(1, json(send("GET", "/v1/client/checks", null)).size());
    }

    @Test
    void aCheckIsDeregisteredAndAnUnknownOneIsNotAnErrorAsInConsul() throws Exception {
        HttpResponse<String> removed = send("PUT", "/v1/client/check/deregister/file-check", null);
        assertEquals(200, removed.statusCode(), removed.body());
        assertEquals("file-check", json(removed).path("checkId").asText());
        assertTrue(json(removed).path("deregistered").asBoolean());
        assertEquals(List.of(), definitions.checks());

        HttpResponse<String> again = send("PUT", "/v1/client/check/deregister/file-check", null);
        assertEquals(200, again.statusCode(), again.body());
        assertFalse(json(again).path("deregistered").asBoolean());
    }

    @Test
    void aRouteRefusesAMethodItDoesNotServe() throws Exception {
        for (String[] route : List.of(
                new String[] {"GET", "/v1/client/service/register"},
                new String[] {"POST", "/v1/client/service/register"},
                new String[] {"GET", "/v1/client/service/deregister/web"},
                new String[] {"PUT", "/v1/client/service/web"},
                new String[] {"PUT", "/v1/client/services"},
                new String[] {"GET", "/v1/client/check/register"},
                new String[] {"GET", "/v1/client/check/deregister/ttl"},
                new String[] {"PUT", "/v1/client/checks"})) {
            assertRefused(send(route[0], route[1], route[0].equals("GET") ? null : "{}"), 405, "method_not_allowed");
        }
    }

    @Test
    void aPathBeyondARouteOrWithoutAnIdIsNotFound() throws Exception {
        for (String[] route : List.of(
                new String[] {"GET", "/v1/client/services/web"},
                new String[] {"GET", "/v1/client/checks/ttl"},
                new String[] {"PUT", "/v1/client/service/register/web"},
                new String[] {"PUT", "/v1/client/check/register/ttl"},
                new String[] {"PUT", "/v1/client/service/deregister/"},
                new String[] {"PUT", "/v1/client/check/deregister/"},
                new String[] {"GET", "/v1/client/service/"})) {
            assertRefused(send(route[0], route[1], route[0].equals("GET") ? null : "{}"), 404, "not_found");
        }
    }

    @Test
    void writesAreRefusedOnceShutdownBeginsAndReadsGoOn() throws Exception {
        api.beginShutdown();

        for (String path : List.of("/v1/client/service/register", "/v1/client/service/deregister/file",
                "/v1/client/check/register", "/v1/client/check/deregister/file-check")) {
            assertRefused(send("PUT", path, WEB), 503, "shutting_down");
        }
        assertEquals(200, send("GET", "/v1/client/services", null).statusCode());
        assertEquals(200, send("GET", "/v1/client/service/file", null).statusCode());
        assertEquals(200, send("GET", "/v1/client/checks", null).statusCode());
        assertEquals(List.of("file"), definitions.services().stream().map(ServiceDefinition::id).toList());
    }

    @Test
    void aTtlCheckIsToldItsStatusWithAnOptionalNoteAsConsulsIs() throws Exception {
        for (String[] route : List.of(
                new String[] {"pass", "passing"}, new String[] {"warn", "warning"}, new String[] {"fail", "critical"})) {
            HttpResponse<String> told = send("PUT", "/v1/client/check/" + route[0] + "/file-check", null);
            assertEquals(200, told.statusCode(), told.body());
            assertEquals("file-check", json(told).path("checkId").asText());
            assertEquals(route[1], json(told).path("status").asText());
        }
        assertEquals(200, send("PUT", "/v1/client/check/pass/file-check?note=all%20good%20%26%20well", null).statusCode());

        assertEquals(List.of("PASSING:", "WARNING:", "CRITICAL:", "PASSING:all good & well"), reports);
    }

    @Test
    void aTtlCheckIsUpdatedWithAStatusAndOutputInTheBody() throws Exception {
        HttpResponse<String> told = send("PUT", "/v1/client/check/update/file-check",
                "{\"status\":\"warning\",\"output\":\"slow\"}");
        assertEquals(200, told.statusCode(), told.body());
        assertEquals(200, send("PUT", "/v1/client/check/update/file-check", "{\"status\":\"passing\"}").statusCode());

        assertEquals(List.of("WARNING:slow", "PASSING:"), reports);
    }

    @Test
    void anInvalidUpdateIsRefusedAndTellsTheCheckNothing() throws Exception {
        for (String body : List.of("{ not json", "[]", "", "{}", "{\"status\":\"nonsense\"}",
                "{\"status\":\"maintenance\"}", "{\"status\":1}", "{\"status\":\"passing\",\"output\":1}",
                "{\"status\":\"passing\",\"unknown\":true}")) {
            assertRefused(send("PUT", "/v1/client/check/update/file-check", body), 400, "invalid_request");
        }

        assertEquals(List.of(), reports);
    }

    @Test
    void aStatusForACheckThatCannotTakeOneIsRefused() throws Exception {
        assertRefused(send("PUT", "/v1/client/check/pass/nobody", null), 404, "check_not_found");
        send("PUT", "/v1/client/check/register", "{\"id\":\"probe\",\"serviceId\":\"file\",\"type\":\"tcp\"}");
        assertRefused(send("PUT", "/v1/client/check/pass/probe", null), 400, "check_not_ttl");
        definitions.setServiceMaintenance("file", true, "upgrade");
        assertRefused(send("PUT", "/v1/client/check/pass/_service_maintenance:file", null), 400, "check_not_ttl");

        assertEquals(List.of(), reports);
    }

    @Test
    void aStatusForACheckThatIsNotRunningYetIsRefusedAsRetryable() throws Exception {
        running = false;

        HttpResponse<String> refused = send("PUT", "/v1/client/check/pass/file-check", null);

        assertRefused(refused, 503, "check_not_running");
        assertTrue(json(refused).path("retryable").asBoolean());
    }

    @Test
    void aServiceIsPutInMaintenanceWithAReasonAndTakenOutAgain() throws Exception {
        HttpResponse<String> enabled = send("PUT", "/v1/client/service/maintenance/file?enable=true&reason=big%20upgrade",
                null);
        assertEquals(200, enabled.statusCode(), enabled.body());
        assertEquals("file", json(enabled).path("serviceId").asText());
        assertTrue(json(enabled).path("maintenance").asBoolean());

        JsonNode check = json(send("GET", "/v1/client/checks", null)).path("_service_maintenance:file");
        assertEquals("maintenance", check.path("type").asText());
        assertEquals("big upgrade", check.path("reason").asText());
        assertEquals("file", check.path("serviceId").asText());

        HttpResponse<String> disabled = send("PUT", "/v1/client/service/maintenance/file?enable=false", null);
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertFalse(json(disabled).path("maintenance").asBoolean());
        assertFalse(json(send("GET", "/v1/client/checks", null)).has("_service_maintenance:file"));
    }

    @Test
    void aNodeIsPutInMaintenanceWithAReasonAndTakenOutAgain() throws Exception {
        HttpResponse<String> enabled = send("PUT", "/v1/client/maintenance?enable=true&reason=rack%20move", null);
        assertEquals(200, enabled.statusCode(), enabled.body());
        assertTrue(json(enabled).path("maintenance").asBoolean());
        assertEquals("rack move", json(send("GET", "/v1/client/checks", null)).path("_node_maintenance:file")
                .path("reason").asText());

        HttpResponse<String> disabled = send("PUT", "/v1/client/maintenance?enable=false", null);
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertFalse(json(send("GET", "/v1/client/checks", null)).has("_node_maintenance:file"));
    }

    @Test
    void maintenanceWithoutAReasonHasADefaultOneAndEnableReadsAsGoReadsABool() throws Exception {
        for (String truth : List.of("1", "t", "T", "TRUE", "true", "True")) {
            assertEquals(200, send("PUT", "/v1/client/maintenance?enable=" + truth, null).statusCode(), truth);
            assertTrue(json(send("GET", "/v1/client/checks", null)).path("_node_maintenance:file").path("reason")
                    .asText().contains("no reason"), truth);
            assertEquals(200, send("PUT", "/v1/client/maintenance?enable=false", null).statusCode());
        }
        for (String falsehood : List.of("0", "f", "F", "FALSE", "false", "False")) {
            send("PUT", "/v1/client/maintenance?enable=true", null);
            assertEquals(200, send("PUT", "/v1/client/maintenance?enable=" + falsehood, null).statusCode(), falsehood);
            assertFalse(json(send("GET", "/v1/client/checks", null)).has("_node_maintenance:file"), falsehood);
        }
    }

    @Test
    void maintenanceWithoutAValidEnableIsRefusedAsConsulRefusesIt() throws Exception {
        for (String path : List.of("/v1/client/maintenance", "/v1/client/service/maintenance/file")) {
            assertRefused(send("PUT", path, null), 400, "invalid_request");
            assertRefused(send("PUT", path + "?enable=maybe", null), 400, "invalid_request");
            assertRefused(send("PUT", path + "?enable=", null), 400, "invalid_request");
        }
        assertEquals(1, json(send("GET", "/v1/client/checks", null)).size());
    }

    @Test
    void maintenanceOfAnUnknownServiceIsNotFoundEitherWayAsConsulAnswers() throws Exception {
        assertRefused(send("PUT", "/v1/client/service/maintenance/nobody?enable=true", null), 404, "service_not_found");
        assertRefused(send("PUT", "/v1/client/service/maintenance/nobody?enable=false", null), 404, "service_not_found");
    }

    @Test
    void theStatusAndMaintenanceRoutesRefuseOtherMethodsAndPathsAndWritesOnceShutdownBegins() throws Exception {
        for (String path : List.of("/v1/client/check/pass/file-check", "/v1/client/check/warn/file-check",
                "/v1/client/check/fail/file-check", "/v1/client/check/update/file-check",
                "/v1/client/maintenance?enable=true", "/v1/client/service/maintenance/file?enable=true")) {
            assertRefused(send("GET", path, null), 405, "method_not_allowed");
        }
        for (String path : List.of("/v1/client/check/pass/", "/v1/client/check/warn/", "/v1/client/check/fail/",
                "/v1/client/check/update/", "/v1/client/maintenance/extra", "/v1/client/service/maintenance/")) {
            assertRefused(send("PUT", path, "{}"), 404, "not_found");
        }

        api.beginShutdown();

        for (String path : List.of("/v1/client/check/pass/file-check", "/v1/client/check/update/file-check",
                "/v1/client/maintenance?enable=true", "/v1/client/service/maintenance/file?enable=true")) {
            assertRefused(send("PUT", path, "{\"status\":\"passing\"}"), 503, "shutting_down");
        }
        assertEquals(List.of(), reports);
    }

    @Test
    void maintenanceThatCannotBeKeptIsAnsweredAsARetryableFailure() throws Exception {
        Path temporary = Files.createDirectory(directory.resolve(RegistrationStore.FILE_NAME + ".tmp"));

        assertRefused(send("PUT", "/v1/client/maintenance?enable=true", null), 500, "registration_not_kept");
        assertRefused(send("PUT", "/v1/client/service/maintenance/file?enable=true", null), 500,
                "registration_not_kept");

        Files.delete(temporary);
        assertEquals(1, json(send("GET", "/v1/client/checks", null)).size());
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeTheRouteRuns() throws Exception {
        String tooLong = "{\"name\":\"web\",\"port\":8080,\"tags\":[\"" + "x".repeat(JsonApi.MAX_BODY_BYTES) + "\"]}";

        assertRefused(send("PUT", "/v1/client/service/register", tooLong), 413, "request_too_large");
    }

    @Test
    void aRegistrationThatCannotBeKeptIsAnsweredAsARetryableFailureAndChangesNothing() throws Exception {
        send("PUT", "/v1/client/service/register", WEB);
        Path temporary = Files.createDirectory(directory.resolve(RegistrationStore.FILE_NAME + ".tmp"));

        HttpResponse<String> refused = send("PUT", "/v1/client/service/register",
                "{\"id\":\"api-1\",\"name\":\"api\",\"port\":9000}");

        assertRefused(refused, 500, "registration_not_kept");
        assertTrue(json(refused).path("retryable").asBoolean());
        assertTrue(definitions.service("api-1").isEmpty());
        assertRefused(send("PUT", "/v1/client/service/deregister/web-1", null), 500, "registration_not_kept");
        assertTrue(definitions.service("web-1").isPresent());
        Files.delete(temporary);
    }
}
