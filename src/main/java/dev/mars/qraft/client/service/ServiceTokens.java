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

package dev.mars.qraft.client.service;

import dev.mars.qraft.client.acl.ClientTokens;
import dev.mars.qraft.client.catalog.RequestTokens;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The tokens a client's calls to the servers carry (ACL list, proposal 13): the {@code client} token, else the
 * default, for the client's calls for its node; for a service registered through the local API, the token it was
 * registered with, else the default; and for a service from the file, its own {@code tokenFile}, else the
 * {@code configFileRegistration} token, else the default.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ServiceTokens implements RequestTokens {
    private final ClientTokens tokens;
    private final LocalDefinitions definitions;
    private final Map<String, String> fileServiceTokens;

    /**
     * @param fileServiceTokens the token a configured service names for itself, by service ID, already read
     */
    public ServiceTokens(ClientTokens tokens, LocalDefinitions definitions, Map<String, String> fileServiceTokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.fileServiceTokens = Map.copyOf(fileServiceTokens);
    }

    @Override
    public Optional<String> node() {
        return tokens.clientToken();
    }

    @Override
    public Optional<String> service(String serviceId) {
        if (definitions.registeredThroughApi(serviceId)) {
            return definitions.registrationToken(serviceId).or(tokens::defaultToken);
        }
        return Optional.ofNullable(fileServiceTokens.get(serviceId)).or(tokens::configFileRegistrationToken);
    }
}
