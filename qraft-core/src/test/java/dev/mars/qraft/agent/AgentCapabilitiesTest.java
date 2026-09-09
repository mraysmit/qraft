package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
