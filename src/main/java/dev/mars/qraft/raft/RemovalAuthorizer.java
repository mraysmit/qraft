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

package dev.mars.qraft.raft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Decides whether a server removal may proceed from the token it carries. {@link MembershipService} asks on the server
 * that receives the removal and again on the leader, so the Raft port does not bypass it. With ACLs disabled this is
 * the shared operator token ({@link #operatorToken}); with ACLs enabled the server asks for {@code operator:write}
 * (ACL list, Phase 4).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
@FunctionalInterface
public interface RemovalAuthorizer {
    /** Why a removal carrying {@code token} is refused, or empty when it may proceed. */
    Optional<String> refusal(String token);

    /**
     * The shared operator token, compared in constant time; with none configured, every removal is refused.
     *
     * @param operatorToken the token a removal must carry; null or blank refuses every removal
     * @param serverName    this server's name, for the refusal when no token is configured
     */
    static RemovalAuthorizer operatorToken(String operatorToken, String serverName) {
        Logger logger = LoggerFactory.getLogger(MembershipService.class);
        byte[] expected = operatorToken == null || operatorToken.isBlank()
                ? null : operatorToken.getBytes(StandardCharsets.UTF_8);
        return token -> {
            if (expected == null) {
                return Optional.of("Removal refused: no operator token is configured on " + serverName);
            }
            if (!MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
                logger.warn("Refused a server removal with a missing or wrong operator token");
                return Optional.of("The operator token is missing or wrong");
            }
            return Optional.empty();
        };
    }
}
