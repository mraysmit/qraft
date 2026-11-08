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

package dev.mars.qraft.client.catalog;

import java.util.Map;
import java.util.Objects;

/**
 * A server's answer to a request the client forwarded for an application, kept as it came: its status, its body's
 * bytes, and the headers the client passes on (key/value list, Phase 4).
 *
 * @param status  the server's status
 * @param body    the body's bytes, possibly empty
 * @param headers the headers passed on, by name
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record ForwardedResponse(int status, byte[] body, Map<String, String> headers) {
    public ForwardedResponse {
        body = Objects.requireNonNull(body, "body").clone();
        headers = Map.copyOf(headers);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
