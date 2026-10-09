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

package dev.mars.qraft.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ClientInfo}: a new client's defaults, its endpoint, identity by client ID alone, the copy the state
 * store takes before changing a client, metadata handling, the fields {@code toString} names, and a JSON round
 * trip of every field.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
class ClientInfoTest {
    private static final Instant REGISTERED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant HEARTBEAT = Instant.parse("2026-01-01T00:00:05Z");

    @Test
    void aNewClientIsRegisteringWithEmptyMetadataAndItsCreationTime() {
        Instant before = Instant.now();
        ClientInfo client = new ClientInfo("client-001", "host1.example.com", "192.168.1.100", 8080);
        Instant after = Instant.now();

        assertEquals(ClientStatus.REGISTERING, client.getStatus());
        assertTrue(client.getMetadata().isEmpty());
        assertFalse(client.getRegistrationTime().isBefore(before), "registered no earlier than its creation");
        assertFalse(client.getRegistrationTime().isAfter(after), "registered no later than its creation");
        assertEquals("client-001", client.getClientId());
        assertEquals("host1.example.com", client.getHostname());
        assertEquals("192.168.1.100", client.getAddress());
        assertEquals(8080, client.getPort());
    }

    @Test
    void theEndpointIsAnHttpUrlOfTheAddressAndPort() {
        assertEquals("http://192.168.1.100:8080",
                new ClientInfo("client-001", "host1", "192.168.1.100", 8080).getEndpoint());
    }

    @Test
    void clientsAreEqualExactlyWhenTheirIdsAre() {
        ClientInfo client = new ClientInfo("client-001", "host1", "192.168.1.1", 8080);
        ClientInfo sameIdElsewhere = new ClientInfo("client-001", "host2", "192.168.1.2", 9090);
        sameIdElsewhere.setStatus(ClientStatus.FAILED);
        ClientInfo otherIdSameHost = new ClientInfo("client-002", "host1", "192.168.1.1", 8080);

        assertEquals(client, sameIdElsewhere, "only the ID identifies a client");
        assertEquals(client.hashCode(), sameIdElsewhere.hashCode());
        assertNotEquals(client, otherIdSameHost);
        assertNotEquals(client, null);
        assertNotEquals(client, "client-001");
    }

    @Test
    void aCopyHasEveryFieldAndItsOwnMetadata() {
        ClientInfo source = fullyPopulated();

        ClientInfo copy = ClientInfo.copyOf(source);

        assertNotSame(source, copy);
        assertFullyPopulated(copy);
        assertSame(source.getCapabilities(), copy.getCapabilities());
        copy.addMetadata("changed", "in-copy");
        source.addMetadata("changed-too", "in-source");
        assertEquals(Map.of("tier", "premium", "changed-too", "in-source"), source.getMetadata(),
                "changing the copy's metadata leaves the source alone");
        assertEquals(Map.of("tier", "premium", "changed", "in-copy"), copy.getMetadata(),
                "changing the source's metadata leaves the copy alone");
    }

    @Test
    void metadataAccumulatesByKeyAndIsNeverNull() {
        ClientInfo client = new ClientInfo();

        client.addMetadata("environment", "production");
        client.addMetadata("tier", "premium");
        client.addMetadata("tier", "standard");
        assertEquals(Map.of("environment", "production", "tier", "standard"), client.getMetadata());

        client.setMetadata(null);
        assertTrue(client.getMetadata().isEmpty(), "clearing metadata leaves an empty map");
        client.addMetadata("after", "clearing");
        assertEquals(Map.of("after", "clearing"), client.getMetadata());
    }

    @Test
    void toStringNamesTheIdentifyingFieldsWithTheirValues() {
        String text = fullyPopulated().toString();

        for (String expected : List.of("clientId='client-001'", "hostname='host1'", "address='192.168.1.1'",
                "port=8080", "status=" + ClientStatus.HEALTHY, "version='1.2.3'", "region='us-west-2'", "datacenter='dc1'")) {
            assertTrue(text.contains(expected), expected + " in " + text);
        }
    }

    @Test
    void aJsonRoundTripKeepsEveryField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ClientInfo source = fullyPopulated();

        ClientInfo read = mapper.readValue(mapper.writeValueAsString(source), ClientInfo.class);

        assertFullyPopulated(read);
        assertEquals(Set.of("transfer"), read.getCapabilities().getSupportedServices());
    }

    @Test
    void onlyAHealthyNodeIsHealthy() {
        ClientInfo clientInfo = new ClientInfo();

        clientInfo.setStatus(ClientStatus.HEALTHY);
        assertTrue(clientInfo.isHealthy());

        for (ClientStatus status : List.of(ClientStatus.DEGRADED, ClientStatus.MAINTENANCE,
                ClientStatus.UNREACHABLE, ClientStatus.FAILED)) {
            clientInfo.setStatus(status);
            assertFalse(clientInfo.isHealthy(), status.toString());
        }
    }

    @Test
    void aClientHasNoWorkAvailabilityProperty() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(new ClientInfo("client-1", "host", "10.0.0.1", 8080));

        assertFalse(json.contains("\"available\""), "the job system's availability for work is gone: " + json);
    }

    /** A client with every field set to a value that differs from its default. */
    private static ClientInfo fullyPopulated() {
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setSupportedServices(Set.of("transfer"));
        ClientInfo client = new ClientInfo("client-001", "host1", "192.168.1.1", 8080);
        client.setCapabilities(capabilities);
        client.setStatus(ClientStatus.HEALTHY);
        client.setRegistrationTime(REGISTERED);
        client.setLastHeartbeat(HEARTBEAT);
        client.setVersion("1.2.3");
        client.setRegion("us-west-2");
        client.setDatacenter("dc1");
        client.setMetadata(new HashMap<>(Map.of("tier", "premium")));
        return client;
    }

    /** Checks each field against the literal {@link #fullyPopulated()} sets, so a setter that drops its value fails. */
    private static void assertFullyPopulated(ClientInfo actual) {
        assertEquals("client-001", actual.getClientId());
        assertEquals("host1", actual.getHostname());
        assertEquals("192.168.1.1", actual.getAddress());
        assertEquals(8080, actual.getPort());
        assertEquals(ClientStatus.HEALTHY, actual.getStatus());
        assertEquals(REGISTERED, actual.getRegistrationTime());
        assertEquals(HEARTBEAT, actual.getLastHeartbeat());
        assertEquals("1.2.3", actual.getVersion());
        assertEquals("us-west-2", actual.getRegion());
        assertEquals("dc1", actual.getDatacenter());
        assertEquals(Map.of("tier", "premium"), actual.getMetadata());
    }
}
