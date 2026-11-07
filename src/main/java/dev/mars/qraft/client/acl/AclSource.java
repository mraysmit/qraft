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

import java.util.concurrent.CompletableFuture;

/**
 * Asks the servers what a token holds: {@code POST /v1/internal/acl/resolve} under the token, or under none for the
 * anonymous token. The client's server client is one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
@FunctionalInterface
public interface AclSource {
    /** What the servers hold for {@code secret}; {@code null} asks for the anonymous token. */
    CompletableFuture<AclLookup> resolve(String secret);
}
