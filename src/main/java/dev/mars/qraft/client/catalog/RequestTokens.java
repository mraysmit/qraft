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

import java.util.Optional;

/**
 * The tokens a client's calls to the servers carry (ACL list, proposal 13): the client's own for what it does for its
 * node, which is registering and renewing it, anti-entropy's read and every removal during a pass; and a service's
 * for registering that service and reporting its checks. A call with no token is anonymous.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public interface RequestTokens {
    /** No tokens: every call is anonymous, as before ACLs. */
    RequestTokens NONE = new RequestTokens() {
        @Override public Optional<String> node() { return Optional.empty(); }
        @Override public Optional<String> service(String serviceId) { return Optional.empty(); }
    };

    /** The token for the client's calls for its node. */
    Optional<String> node();

    /** The token for registering the service {@code serviceId} and reporting its checks. */
    Optional<String> service(String serviceId);
}
