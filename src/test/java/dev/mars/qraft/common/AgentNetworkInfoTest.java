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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the wire form of {@link AgentNetworkInfo}, the network facts an agent reports: the exact JSON field set,
 * a round trip that keeps every value, the legacy name of the NAT traversal flag, and the fields
 * {@code toString} names.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-27
 * @version 2.0
 */
class AgentNetworkInfoTest {

    @Test
    void serializesOnlyReportedFieldsAndNoDerivedScores() throws Exception {
        TreeSet<String> fields = new TreeSet<>();
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new AgentNetworkInfo()))
                .fieldNames().forEachRemaining(fields::add);

        assertEquals(new TreeSet<>(Set.of("publicIpAddress", "privateIpAddress",
                "networkInterfaces", "bandwidthCapacity", "currentBandwidthUsage", "latencyMs",
                "packetLossPercentage", "connectionType", "isNatTraversal", "firewallPorts")), fields);
    }

    @Test
    void aJsonRoundTripKeepsEveryValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        AgentNetworkInfo read = mapper.readValue(mapper.writeValueAsString(reported()), AgentNetworkInfo.class);

        assertEquals("203.0.113.42", read.getPublicIpAddress());
        assertEquals("192.168.1.100", read.getPrivateIpAddress());
        assertEquals(List.of("eth0", "eth1"), read.getNetworkInterfaces());
        assertEquals(125_000_000L, read.getBandwidthCapacity());
        assertEquals(50_000_000L, read.getCurrentBandwidthUsage());
        assertEquals(25.5, read.getLatencyMs());
        assertEquals(1.2, read.getPacketLossPercentage());
        assertEquals("ethernet", read.getConnectionType());
        assertTrue(read.isNatTraversal(), "the flag is set, so it cannot pass as the default");
        assertEquals(List.of(8080, 8443), read.getFirewallPorts());
    }

    @Test
    void readsTheNatTraversalFlagUnderEitherName() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertTrue(mapper.readValue("{\"isNatTraversal\":true}", AgentNetworkInfo.class).isNatTraversal());
        assertTrue(mapper.readValue("{\"natTraversal\":true}", AgentNetworkInfo.class).isNatTraversal(),
                "agents built before the duplicate was removed also sent natTraversal");
    }

    @Test
    void toStringNamesTheAddressesMetricsAndConnectionTypeWithTheirValues() {
        String text = reported().toString();

        for (String expected : List.of("publicIpAddress='203.0.113.42'", "privateIpAddress='192.168.1.100'",
                "bandwidthCapacity=125000000", "currentBandwidthUsage=50000000", "latencyMs=25.5",
                "packetLossPercentage=1.2", "connectionType='ethernet'")) {
            assertTrue(text.contains(expected), expected + " in " + text);
        }
    }

    /** Network facts with every field set to a value that differs from its default. */
    private static AgentNetworkInfo reported() {
        AgentNetworkInfo info = new AgentNetworkInfo();
        info.setPublicIpAddress("203.0.113.42");
        info.setPrivateIpAddress("192.168.1.100");
        info.setNetworkInterfaces(List.of("eth0", "eth1"));
        info.setBandwidthCapacity(125_000_000L);
        info.setCurrentBandwidthUsage(50_000_000L);
        info.setLatencyMs(25.5);
        info.setPacketLossPercentage(1.2);
        info.setConnectionType("ethernet");
        info.setNatTraversal(true);
        info.setFirewallPorts(List.of(8080, 8443));
        return info;
    }
}
