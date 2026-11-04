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

import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A token, as replicated state holds it (ACL list, proposals 2 and 3).
 *
 * <ul>
 *   <li>The accessor ID names the token in the API and in logs; the secret is what a caller presents, and is held
 *       only as its SHA-256 hash ({@link AclSecrets}).</li>
 *   <li>It links to policies and roles by their IDs, and holds node and service identities, each sorted and
 *       distinct.</li>
 *   <li>Its creation time, and its expiry if it has one, are stamped by the leader into the command, in
 *       milliseconds, so every replica stores the same values. An expiry lies between one minute and 24 hours after
 *       the creation time, Consul's default bounds ({@code agent/consul/auth/token_writer.go}).</li>
 *   <li>{@code installedFromFile} marks the token that a server installed from its management file (proposal 12),
 *       which returns at the next leadership change while a server's file still holds its secret.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclToken(String accessorId, String secretHash, String description, List<String> policyIds,
                       List<String> roleIds, List<NodeIdentity> nodeIdentities,
                       List<ServiceIdentity> serviceIdentities, Instant createTime, Instant expirationTime,
                       boolean installedFromFile) {
    /** The shortest time from a token's creation to its expiry. */
    public static final Duration MIN_EXPIRATION_TTL = Duration.ofMinutes(1);
    /** The longest time from a token's creation to its expiry. */
    public static final Duration MAX_EXPIRATION_TTL = Duration.ofHours(24);

    public AclToken {
        accessorId = AclSecrets.canonicalId(accessorId, "a token's accessor ID");
        secretHash = AclSecrets.secretHash(secretHash);
        description = description == null ? "" : description;
        policyIds = AclLinks.ids(policyIds, "a token's policy link");
        roleIds = AclLinks.ids(roleIds, "a token's role link");
        nodeIdentities = AclLinks.sorted(nodeIdentities);
        serviceIdentities = AclLinks.sorted(serviceIdentities);
        createTime = Instant.ofEpochMilli(Objects.requireNonNull(createTime, "createTime").toEpochMilli());
        if (expirationTime != null) {
            expirationTime = Instant.ofEpochMilli(expirationTime.toEpochMilli());
            Duration ttl = Duration.between(createTime, expirationTime);
            if (ttl.compareTo(MIN_EXPIRATION_TTL) < 0 || ttl.compareTo(MAX_EXPIRATION_TTL) > 0) {
                throw new IllegalArgumentException("a token's expiry must be between " + MIN_EXPIRATION_TTL + " and "
                        + MAX_EXPIRATION_TTL + " after its creation, not " + ttl);
            }
        }
    }

    /** This token with the links, identities and description of {@code changed}, keeping everything the server owns. */
    AclToken withChangesOf(AclToken changed) {
        return new AclToken(accessorId, secretHash, changed.description, changed.policyIds, changed.roleIds,
                changed.nodeIdentities, changed.serviceIdentities, createTime, expirationTime, installedFromFile);
    }

    /** This token linking only to the policies and roles in {@code policies} and {@code roles}. */
    AclToken withLinksIn(java.util.Set<String> policies, java.util.Set<String> roles) {
        return new AclToken(accessorId, secretHash, description,
                policyIds.stream().filter(policies::contains).toList(), roleIds.stream().filter(roles::contains).toList(),
                nodeIdentities, serviceIdentities, createTime, expirationTime, installedFromFile);
    }
}
