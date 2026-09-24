package dev.mars.qraft.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceDefinitionTest {
    @Test
    void isAnImmutableClientOwnedDefinition() {
        List<String> tags = new java.util.ArrayList<>(List.of("primary"));
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("zone", "a"));

        ServiceDefinition definition = new ServiceDefinition(
                "web-1", "web", "10.0.0.5", 8080, tags, metadata, true);
        tags.add("changed");
        metadata.put("owner", "other");

        assertEquals(List.of("primary"), definition.tags());
        assertEquals(Map.of("zone", "a"), definition.metadata());
        assertThrows(UnsupportedOperationException.class, () -> definition.tags().add("changed"));
        assertThrows(UnsupportedOperationException.class, () -> definition.metadata().put("x", "y"));
    }

    @Test
    void rejectsMissingIdentityAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("", "web", "localhost", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "", "localhost", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "web", "", 80, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceDefinition("web", "web", "localhost", 0, List.of(), Map.of(), true));
    }
}
