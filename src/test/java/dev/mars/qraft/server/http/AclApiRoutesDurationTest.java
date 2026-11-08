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

package dev.mars.qraft.server.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the Go durations the ACL and session APIs read ({@code expirationTtl}, a session's {@code TTL} and
 * {@code LockDelay}): hours, minutes, seconds and milliseconds, alone or together, as Go writes them; and ISO-8601.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class AclApiRoutesDurationTest {

    @Test
    void goDurationsAreReadInEveryUnit() {
        assertEquals(Duration.ofHours(2), AclApiRoutes.duration("2h"));
        assertEquals(Duration.ofSeconds(90), AclApiRoutes.duration("1m30s"));
        assertEquals(Duration.ofMillis(1500), AclApiRoutes.duration("1500ms"), "milliseconds, not minutes and a stray s");
        assertEquals(Duration.ofMillis(10_250), AclApiRoutes.duration("10s250ms"));
        assertEquals(Duration.ofMinutes(5), AclApiRoutes.duration("PT5M"));
        assertThrows(IllegalArgumentException.class, () -> AclApiRoutes.duration("10x"));
        assertThrows(IllegalArgumentException.class, () -> AclApiRoutes.duration("ms"));
    }
}
