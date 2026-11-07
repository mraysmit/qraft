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

package dev.mars.qraft.client.acl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * What a server answered to {@code POST /v1/internal/acl/resolve} for a token: the token with the policies and roles
 * it links to; that no token has the secret; or no answer at all, which the down policy decides.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public sealed interface AclLookup permits AclLookup.Found, AclLookup.NotFound, AclLookup.Unavailable {
    /** The server's answer: {@code token}, {@code policies} and {@code roles}. */
    record Found(JsonNode answer) implements AclLookup {
        public Found {
            Objects.requireNonNull(answer, "answer");
        }
    }

    /** No token has the secret: 403 {@code token_not_found}. */
    record NotFound() implements AclLookup {
    }

    /** No server answered, or none could: the reason, for the log. */
    record Unavailable(String reason) implements AclLookup {
    }
}
