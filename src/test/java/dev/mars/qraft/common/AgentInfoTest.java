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
 * Tests {@link AgentInfo}: a new agent's defaults, its endpoint, identity by agent ID alone, the copy the state
 * store takes before changing an agent, metadata handling, the fields {@code toString} names, and a JSON round
 * trip of every field.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
class AgentInfoTest {
    private static final Instant REGISTERED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant HEARTBEAT = Instant.parse("2026-01-01T00:00:05Z");

    @Test
    void aNewAgentIsRegisteringWithEmptyMetadataAndItsCreationTime() {
        Instant before = Instant.now();
        AgentInfo agent = new AgentInfo("agent-001", "host1.example.com", "192.168.1.100", 8080);
        Instant after = Instant.now();

        assertEquals(AgentStatus.REGISTERING, agent.getStatus());
        assertTrue(agent.getMetadata().isEmpty());
        assertFalse(agent.getRegistrationTime().isBefore(before), "registered no earlier than its creation");
        assertFalse(agent.getRegistrationTime().isAfter(after), "registered no later than its creation");
        assertEquals("agent-001", agent.getAgentId());
        assertEquals("host1.example.com", agent.getHostname());
        assertEquals("192.168.1.100", agent.getAddress());
        assertEquals(8080, agent.getPort());
    }

    @Test
    void theEndpointIsAnHttpUrlOfTheAddressAndPort() {
        assertEquals("http://192.168.1.100:8080",
                new AgentInfo("agent-001", "host1", "192.168.1.100", 8080).getEndpoint());
    }

    @Test
    void agentsAreEqualExactlyWhenTheirIdsAre() {
        AgentInfo agent = new AgentInfo("agent-001", "host1", "192.168.1.1", 8080);
        AgentInfo sameIdElsewhere = new AgentInfo("agent-001", "host2", "192.168.1.2", 9090);
        sameIdElsewhere.setStatus(AgentStatus.FAILED);
        AgentInfo otherIdSameHost = new AgentInfo("agent-002", "host1", "192.168.1.1", 8080);

        assertEquals(agent, sameIdElsewhere, "only the ID identifies an agent");
        assertEquals(agent.hashCode(), sameIdElsewhere.hashCode());
        assertNotEquals(agent, otherIdSameHost);
        assertNotEquals(agent, null);
        assertNotEquals(agent, "agent-001");
    }

    @Test
    void aCopyHasEveryFieldAndItsOwnMetadata() {
        AgentInfo source = fullyPopulated();

        AgentInfo copy = AgentInfo.copyOf(source);

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
        AgentInfo agent = new AgentInfo();

        agent.addMetadata("environment", "production");
        agent.addMetadata("tier", "premium");
        agent.addMetadata("tier", "standard");
        assertEquals(Map.of("environment", "production", "tier", "standard"), agent.getMetadata());

        agent.setMetadata(null);
        assertTrue(agent.getMetadata().isEmpty(), "clearing metadata leaves an empty map");
        agent.addMetadata("after", "clearing");
        assertEquals(Map.of("after", "clearing"), agent.getMetadata());
    }

    @Test
    void toStringNamesTheIdentifyingFieldsWithTheirValues() {
        String text = fullyPopulated().toString();

        for (String expected : List.of("agentId='agent-001'", "hostname='host1'", "address='192.168.1.1'",
                "port=8080", "status=" + AgentStatus.HEALTHY, "version='1.2.3'", "region='us-west-2'", "datacenter='dc1'")) {
            assertTrue(text.contains(expected), expected + " in " + text);
        }
    }

    @Test
    void aJsonRoundTripKeepsEveryField() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        AgentInfo source = fullyPopulated();

        AgentInfo read = mapper.readValue(mapper.writeValueAsString(source), AgentInfo.class);

        assertFullyPopulated(read);
        assertEquals(Set.of("transfer"), read.getCapabilities().getSupportedServices());
    }

    @Test
    void onlyAHealthyNodeIsHealthy() {
        AgentInfo agentInfo = new AgentInfo();

        agentInfo.setStatus(AgentStatus.HEALTHY);
        assertTrue(agentInfo.isHealthy());

        for (AgentStatus status : List.of(AgentStatus.DEGRADED, AgentStatus.MAINTENANCE,
                AgentStatus.UNREACHABLE, AgentStatus.FAILED)) {
            agentInfo.setStatus(status);
            assertFalse(agentInfo.isHealthy(), status.toString());
        }
    }

    @Test
    void anAgentHasNoWorkAvailabilityProperty() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(new AgentInfo("agent-1", "host", "10.0.0.1", 8080));

        assertFalse(json.contains("\"available\""), "the job system's availability for work is gone: " + json);
    }

    /** An agent with every field set to a value that differs from its default. */
    private static AgentInfo fullyPopulated() {
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(Set.of("transfer"));
        AgentInfo agent = new AgentInfo("agent-001", "host1", "192.168.1.1", 8080);
        agent.setCapabilities(capabilities);
        agent.setStatus(AgentStatus.HEALTHY);
        agent.setRegistrationTime(REGISTERED);
        agent.setLastHeartbeat(HEARTBEAT);
        agent.setVersion("1.2.3");
        agent.setRegion("us-west-2");
        agent.setDatacenter("dc1");
        agent.setMetadata(new HashMap<>(Map.of("tier", "premium")));
        return agent;
    }

    /** Checks each field against the literal {@link #fullyPopulated()} sets, so a setter that drops its value fails. */
    private static void assertFullyPopulated(AgentInfo actual) {
        assertEquals("agent-001", actual.getAgentId());
        assertEquals("host1", actual.getHostname());
        assertEquals("192.168.1.1", actual.getAddress());
        assertEquals(8080, actual.getPort());
        assertEquals(AgentStatus.HEALTHY, actual.getStatus());
        assertEquals(REGISTERED, actual.getRegistrationTime());
        assertEquals(HEARTBEAT, actual.getLastHeartbeat());
        assertEquals("1.2.3", actual.getVersion());
        assertEquals("us-west-2", actual.getRegion());
        assertEquals("dc1", actual.getDatacenter());
        assertEquals(Map.of("tier", "premium"), actual.getMetadata());
    }
}
