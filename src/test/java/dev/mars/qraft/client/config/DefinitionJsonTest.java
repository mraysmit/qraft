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

package dev.mars.qraft.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HttpCheck;
import dev.mars.qraft.client.health.TcpCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DefinitionJson}: a service registration's body by Consul's rules, and the written form of a service and
 * of each kind of check, which the data directory keeps across restarts.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-30
 * @version 1.0
 */
class DefinitionJsonTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ServiceDefinition WEB = new ServiceDefinition("web-1", "web", "10.0.0.5", 8080,
            List.of("v1"), Map.of("team", "platform"), true);

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void aServicesIdDefaultsToItsNameAndItsAddressToTheClientsAsConsulsDo() throws Exception {
        DefinitionJson.Registration registration = DefinitionJson.parseRegistration(
                json("{\"name\":\"web\",\"port\":8080}"), "10.0.0.9");

        assertEquals(new ServiceDefinition("web", "web", "10.0.0.9", 8080, List.of(), Map.of(), true),
                registration.service());
        assertEquals(List.of(), registration.checks());
    }

    @Test
    void aRegistrationCarriesItsServiceAndItsChecksAndATcpCheckDefaultsToTheService() throws Exception {
        DefinitionJson.Registration registration = DefinitionJson.parseRegistration(json("""
                {"id":"web-1","name":"web","address":"10.0.0.5","port":8080,"tags":["v1"],
                 "metadata":{"team":"platform"},
                 "checks":[{"id":"tcp","type":"tcp","intervalMs":5000,"timeoutMs":1000,"ttlMs":15000},
                           {"id":"app","type":"ttl","ttlMs":30000,"required":false}]}
                """), "10.0.0.9");

        assertEquals(WEB, registration.service());
        assertEquals(List.of(
                new TcpCheck("web-1", "tcp", "10.0.0.5", 8080, Duration.ofSeconds(5), Duration.ofSeconds(1),
                        Duration.ofSeconds(15), true),
                new TtlCheck("web-1", "app", Duration.ofSeconds(30), false)), registration.checks());
    }

    @Test
    void aRegistrationIsRefusedWhenItsNameIsMissingOrItCarriesAnUnknownSettingOrRepeatsACheck() {
        assertRefused("{\"port\":8080}", "name");
        assertRefused("{\"name\":\"web\"}", "port");
        assertRefused("{\"name\":\"web\",\"port\":0}", "port");
        assertRefused("{\"name\":\"web\",\"port\":8080,\"enabled\":false}", "enabled");
        assertRefused("{\"name\":\"web\",\"port\":8080,\"checks\":[{\"id\":\"a\",\"type\":\"ttl\",\"ttlMs\":1},"
                + "{\"id\":\"a\",\"type\":\"ttl\",\"ttlMs\":1}]}", "a");
        assertRefused("{\"name\":\"web\",\"port\":8080,\"checks\":[{\"id\":\"a\",\"serviceId\":\"other\","
                + "\"type\":\"ttl\",\"ttlMs\":1}]}", "serviceId");
    }

    private static void assertRefused(String body, String named) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DefinitionJson.parseRegistration(json(body), "10.0.0.9"), body);
        assertTrue(refused.getMessage().contains(named), refused.getMessage());
    }

    @Test
    void aCheckRegisteredAloneNamesItsServiceAndDefaultsToThatService() throws Exception {
        HealthCheckDefinition check = DefinitionJson.parseCheck(
                json("{\"id\":\"tcp\",\"serviceId\":\"web-1\",\"type\":\"tcp\"}"), WEB);

        assertEquals(new TcpCheck("web-1", "tcp", "10.0.0.5", 8080, Duration.ofSeconds(10), Duration.ofSeconds(2),
                Duration.ofSeconds(30), true), check);
        assertEquals("web-1", DefinitionJson.serviceIdOf(json("{\"id\":\"tcp\",\"serviceId\":\"web-1\"}")));
        assertThrows(IllegalArgumentException.class, () -> DefinitionJson.serviceIdOf(json("{\"id\":\"tcp\"}")));
    }

    @Test
    void aServiceAndEveryKindOfCheckReadBackAsTheyWereWritten() throws Exception {
        List<HealthCheckDefinition> checks = List.of(
                new HttpCheck("web-1", "http", URI.create("http://127.0.0.1:8080/health"), Duration.ofSeconds(10),
                        Duration.ofSeconds(2), Duration.ofSeconds(30), false, Duration.ofMinutes(5)),
                new TcpCheck("web-1", "tcp", "10.0.0.7", 9090, Duration.ofSeconds(5), Duration.ofSeconds(1),
                        Duration.ofSeconds(15), true, Duration.ZERO, Duration.ofMillis(500)),
                new TtlCheck("web-1", "app", Duration.ofSeconds(30), true, Duration.ofMinutes(1)));

        assertEquals(WEB, DefinitionJson.parseRegistration(DefinitionJson.write(WEB), null).service());
        for (HealthCheckDefinition check : checks) {
            assertEquals(check, DefinitionJson.parseCheck(DefinitionJson.write(check), WEB));
        }
    }

    @Test
    void aWrittenCheckReadsBackWithoutItsServiceBecauseItSpellsEverythingOut() throws Exception {
        List<HealthCheckDefinition> checks = List.of(
                new HttpCheck("web-1", "http", URI.create("http://127.0.0.1:8080/health"), Duration.ofSeconds(10),
                        Duration.ofSeconds(2), Duration.ofSeconds(30), false, Duration.ofMinutes(5)),
                new TcpCheck("web-1", "tcp", "10.0.0.7", 9090, Duration.ofSeconds(5), Duration.ofSeconds(1),
                        Duration.ofSeconds(15), true, Duration.ZERO, Duration.ofMillis(500)),
                new TtlCheck("web-1", "app", Duration.ofSeconds(30), true, Duration.ofMinutes(1)));

        for (HealthCheckDefinition check : checks) {
            assertEquals(check, DefinitionJson.readWrittenCheck(DefinitionJson.write(check)));
        }
        assertThrows(IllegalArgumentException.class, () -> DefinitionJson.readWrittenCheck(
                json("{\"id\":\"tcp\",\"serviceId\":\"web-1\",\"type\":\"tcp\"}")),
                "a written check names its address: nothing else could give it");
        assertThrows(IllegalArgumentException.class, () -> DefinitionJson.readWrittenCheck(
                json("{\"id\":\"app\",\"type\":\"ttl\",\"ttlMs\":1000}")));
    }

    @Test
    void theWrittenFormIsPinnedBecauseTheDataDirectoryKeepsItAcrossUpgrades() throws Exception {
        assertEquals(json("""
                {"id":"web-1","name":"web","address":"10.0.0.5","port":8080,"tags":["v1"],
                 "metadata":{"team":"platform"}}
                """), written(DefinitionJson.write(WEB)));
        assertEquals(json("""
                {"id":"http","serviceId":"web-1","type":"http","required":false,
                 "url":"http://127.0.0.1:8080/health","intervalMs":10000,"timeoutMs":2000,"ttlMs":30000,
                 "deregisterAfterMs":300000}
                """), written(DefinitionJson.write(new HttpCheck("web-1", "http", URI.create("http://127.0.0.1:8080/health"),
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(30), false, Duration.ofMinutes(5)))));
        assertEquals(json("""
                {"id":"tcp","serviceId":"web-1","type":"tcp","required":true,"address":"10.0.0.7","port":9090,
                 "intervalMs":5000,"timeoutMs":1000,"ttlMs":15000,"deregisterAfterMs":0,"warnAfterMs":500}
                """), written(DefinitionJson.write(new TcpCheck("web-1", "tcp", "10.0.0.7", 9090, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofSeconds(15), true, Duration.ZERO, Duration.ofMillis(500)))));
        assertEquals(json("""
                {"id":"app","serviceId":"web-1","type":"ttl","required":true,"ttlMs":30000,"deregisterAfterMs":60000}
                """), written(DefinitionJson.write(new TtlCheck("web-1", "app", Duration.ofSeconds(30), true,
                Duration.ofMinutes(1)))));
    }

    private static JsonNode written(JsonNode node) throws Exception {
        return json(node.toString());
    }
}
