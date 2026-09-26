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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link AgentCapabilities} discovery metadata defaults, advertised services and regions, and
 * JSON serialization shape.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class AgentCapabilitiesTest {
    @Test
    void startsWithEmptyDiscoveryMetadata() {
        AgentCapabilities capabilities = new AgentCapabilities();

        assertTrue(capabilities.getSupportedServices().isEmpty());
        assertTrue(capabilities.getAvailableRegions().isEmpty());
        assertTrue(capabilities.getCustomCapabilities().isEmpty());
    }

    @Test
    void advertisesServicesAndRegions() {
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(Set.of("catalog", "health"));
        capabilities.addAvailableRegion("eu-west-1");

        assertTrue(capabilities.supportsService("catalog"));
        assertTrue(capabilities.isAvailableInRegion("eu-west-1"));
        assertFalse(capabilities.isAvailableInRegion("us-east-1"));
    }

    @Test
    void emptyRegionSetMeansAllRegions() {
        assertTrue(new AgentCapabilities().isAvailableInRegion("any-region"));
    }

    @Test
    void serializesDiscoveryShape() throws Exception {
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.addSupportedService("catalog");
        capabilities.addCustomCapability("version", "1");

        AgentCapabilities decoded = new ObjectMapper().readValue(
                new ObjectMapper().writeValueAsString(capabilities), AgentCapabilities.class);

        assertTrue(decoded.supportsService("catalog"));
        assertEquals("1", decoded.getCustomCapabilities().get("version"));
    }
}
