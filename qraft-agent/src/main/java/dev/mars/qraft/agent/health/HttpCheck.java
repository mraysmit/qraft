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

package dev.mars.qraft.agent.health;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP GET check: 2xx is passing, 429 is warning, and anything else is critical.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public record HttpCheck(String serviceId, String checkId, URI url, Duration interval, Duration timeout,
                        Duration ttl, boolean required) implements ProbeCheck {
    public HttpCheck {
        HealthCheckDefinition.requireText(serviceId, "serviceId");
        HealthCheckDefinition.requireText(checkId, "checkId");
        if (url == null || url.getScheme() == null
                || !Set.of("http", "https").contains(url.getScheme().toLowerCase(Locale.ROOT))
                || url.getHost() == null) {
            throw new IllegalArgumentException("HTTP check url must be an absolute HTTP or HTTPS URL");
        }
        if (url.getRawUserInfo() != null) {
            throw new IllegalArgumentException("HTTP check url must not contain user information");
        }
        ProbeCheck.validateTiming(interval, timeout, ttl);
    }
}
