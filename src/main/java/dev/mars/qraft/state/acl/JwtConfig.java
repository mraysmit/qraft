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

package dev.mars.qraft.state.acl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code jwt} auth method's configuration (authentication list, decision 1), as Consul's: the PEM public keys a token
 * must be signed with, the issuer and audiences it must name, how its claims map to the names selectors and bind names
 * read, and the leeways on its times.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record JwtConfig(List<String> publicKeysPem, String boundIssuer, List<String> boundAudiences,
                        Map<String, String> claimMappings, Map<String, String> listClaimMappings,
                        Duration expirationLeeway, Duration notBeforeLeeway, Duration clockSkewLeeway) {
    public JwtConfig {
        publicKeysPem = List.copyOf(Objects.requireNonNull(publicKeysPem, "publicKeysPem"));
        boundIssuer = boundIssuer == null ? "" : boundIssuer;
        boundAudiences = List.copyOf(boundAudiences == null ? List.of() : boundAudiences);
        claimMappings = Map.copyOf(claimMappings == null ? Map.of() : claimMappings);
        listClaimMappings = Map.copyOf(listClaimMappings == null ? Map.of() : listClaimMappings);
        expirationLeeway = expirationLeeway == null ? Duration.ZERO : expirationLeeway;
        notBeforeLeeway = notBeforeLeeway == null ? Duration.ZERO : notBeforeLeeway;
        clockSkewLeeway = clockSkewLeeway == null ? Duration.ZERO : clockSkewLeeway;
    }
}
