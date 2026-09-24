package dev.mars.qraft.agent.catalog;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControllerEndpointsTest {
    private static final URI FIRST = URI.create("http://first:8080");
    private static final URI SECOND = URI.create("http://second:8080");
    private static final URI THIRD = URI.create("http://third:8080");

    @Test
    void cycleStartsInConfigurationOrderAndContainsEachSeedOnce() {
        ControllerEndpoints endpoints = new ControllerEndpoints(List.of(FIRST, SECOND, THIRD));
        assertEquals(List.of(FIRST, SECOND, THIRD), endpoints.cycle());
    }

    @Test
    void lastSuccessfulEndpointStartsTheNextCycle() {
        ControllerEndpoints endpoints = new ControllerEndpoints(List.of(FIRST, SECOND, THIRD));
        endpoints.markSuccessful(THIRD);
        assertEquals(List.of(THIRD, FIRST, SECOND), endpoints.cycle());
        endpoints.markSuccessful(SECOND);
        assertEquals(List.of(SECOND, THIRD, FIRST), endpoints.cycle());
    }

    @Test
    void rejectsEmptyOrDuplicateSeedsAndIgnoresUnknownSuccess() {
        assertThrows(IllegalArgumentException.class, () -> new ControllerEndpoints(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ControllerEndpoints(List.of(FIRST, FIRST)));
        ControllerEndpoints endpoints = new ControllerEndpoints(List.of(FIRST, SECOND));
        assertThrows(IllegalArgumentException.class,
                () -> endpoints.markSuccessful(URI.create("http://unknown:8080")));
    }
}
