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

package dev.mars.qraft.agent.catalog;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link ControllerEndpoints} seed cycle ordering, preference for the last successful
 * endpoint, and rejection of empty or duplicate seeds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
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
