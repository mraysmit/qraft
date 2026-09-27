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

package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for {@link AgentNetworkInfo}.
 * Tests constructors, getters/setters, business logic, and JSON serialization.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-27
 * @version 1.0
 */
class AgentNetworkInfoTest {

    @Test
    void testDefaultConstructor() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        assertNotNull(networkInfo);
    }

    @Test
    void testPublicIpAddress() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setPublicIpAddress("203.0.113.42");
        assertEquals("203.0.113.42", networkInfo.getPublicIpAddress());
    }

    @Test
    void testPrivateIpAddress() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setPrivateIpAddress("192.168.1.100");
        assertEquals("192.168.1.100", networkInfo.getPrivateIpAddress());
    }

    @Test
    void testNetworkInterfaces() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        List<String> interfaces = Arrays.asList("eth0", "eth1", "wlan0");
        networkInfo.setNetworkInterfaces(interfaces);
        
        assertEquals(3, networkInfo.getNetworkInterfaces().size());
        assertTrue(networkInfo.getNetworkInterfaces().contains("eth0"));
        assertTrue(networkInfo.getNetworkInterfaces().contains("wlan0"));
    }

    @Test
    void testBandwidthCapacity() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setBandwidthCapacity(125_000_000L); // 1 Gbps
        assertEquals(125_000_000L, networkInfo.getBandwidthCapacity());
    }

    @Test
    void testCurrentBandwidthUsage() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setCurrentBandwidthUsage(50_000_000L); // 400 Mbps
        assertEquals(50_000_000L, networkInfo.getCurrentBandwidthUsage());
    }

    @Test
    void testLatencyMs() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setLatencyMs(25.5);
        assertEquals(25.5, networkInfo.getLatencyMs(), 0.01);
    }

    @Test
    void testPacketLossPercentage() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setPacketLossPercentage(1.5);
        assertEquals(1.5, networkInfo.getPacketLossPercentage(), 0.01);
    }

    @Test
    void testConnectionType() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setConnectionType("ethernet");
        assertEquals("ethernet", networkInfo.getConnectionType());
        
        networkInfo.setConnectionType("wifi");
        assertEquals("wifi", networkInfo.getConnectionType());
    }

    @Test
    void testNatTraversal() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        networkInfo.setNatTraversal(true);
        assertTrue(networkInfo.isNatTraversal());
        
        networkInfo.setNatTraversal(false);
        assertFalse(networkInfo.isNatTraversal());
    }

    @Test
    void testFirewallPorts() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        
        List<Integer> ports = Arrays.asList(8080, 8443, 9000);
        networkInfo.setFirewallPorts(ports);
        
        assertEquals(3, networkInfo.getFirewallPorts().size());
        assertTrue(networkInfo.getFirewallPorts().contains(8080));
        assertTrue(networkInfo.getFirewallPorts().contains(8443));
    }

    @Test
    void testToString() {
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        networkInfo.setPublicIpAddress("203.0.113.42");
        networkInfo.setPrivateIpAddress("192.168.1.100");
        networkInfo.setConnectionType("ethernet");
        
        String str = networkInfo.toString();
        assertNotNull(str);
        assertTrue(str.contains("AgentNetworkInfo"));
        assertTrue(str.contains("203.0.113.42"));
        assertTrue(str.contains("192.168.1.100"));
        assertTrue(str.contains("ethernet"));
    }

    @Test
    void serializesOnlyReportedFieldsAndNoDerivedScores() throws Exception {
        var fields = new java.util.TreeSet<String>();
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new AgentNetworkInfo()))
                .fieldNames().forEachRemaining(fields::add);

        assertEquals(new java.util.TreeSet<>(java.util.Set.of("publicIpAddress", "privateIpAddress",
                "networkInterfaces", "bandwidthCapacity", "currentBandwidthUsage", "latencyMs",
                "packetLossPercentage", "connectionType", "isNatTraversal", "firewallPorts")), fields);
    }

    @Test
    void readsTheNatTraversalFlagUnderEitherName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertTrue(mapper.readValue("{\"isNatTraversal\":true}", AgentNetworkInfo.class).isNatTraversal());
        assertTrue(mapper.readValue("{\"natTraversal\":true}", AgentNetworkInfo.class).isNatTraversal(),
                "agents built before the duplicate was removed also sent natTraversal");
    }

    @Test
    void testJsonSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        AgentNetworkInfo networkInfo = new AgentNetworkInfo();
        networkInfo.setPublicIpAddress("203.0.113.42");
        networkInfo.setPrivateIpAddress("192.168.1.100");
        networkInfo.setNetworkInterfaces(Arrays.asList("eth0", "eth1"));
        networkInfo.setBandwidthCapacity(125_000_000L);
        networkInfo.setCurrentBandwidthUsage(50_000_000L);
        networkInfo.setLatencyMs(25.5);
        networkInfo.setPacketLossPercentage(1.2);
        networkInfo.setConnectionType("ethernet");
        networkInfo.setNatTraversal(false);
        networkInfo.setFirewallPorts(Arrays.asList(8080, 8443));
        
        // Serialize to JSON
        String json = mapper.writeValueAsString(networkInfo);
        assertNotNull(json);
        assertTrue(json.contains("203.0.113.42"));
        assertTrue(json.contains("192.168.1.100"));
        assertTrue(json.contains("ethernet"));
        
        // Deserialize from JSON
        AgentNetworkInfo deserialized = mapper.readValue(json, AgentNetworkInfo.class);
        assertNotNull(deserialized);
        assertEquals("203.0.113.42", deserialized.getPublicIpAddress());
        assertEquals("192.168.1.100", deserialized.getPrivateIpAddress());
        assertEquals(2, deserialized.getNetworkInterfaces().size());
        assertEquals(125_000_000L, deserialized.getBandwidthCapacity());
        assertEquals(50_000_000L, deserialized.getCurrentBandwidthUsage());
        assertEquals(25.5, deserialized.getLatencyMs(), 0.01);
        assertEquals(1.2, deserialized.getPacketLossPercentage(), 0.01);
        assertEquals("ethernet", deserialized.getConnectionType());
        assertFalse(deserialized.isNatTraversal());
        assertEquals(2, deserialized.getFirewallPorts().size());
    }
}
