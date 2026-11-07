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
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclGrant;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.acl.AclRule;
import dev.mars.qraft.common.acl.AclRules;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves a token presented to a client's local API to whom it acts as (ACL list, proposal 10), as Consul's agent
 * resolves one through its servers:
 *
 * <ul>
 *   <li>A request without a token uses the client's default token, and without that the anonymous token, which the
 *       servers resolve when asked under none.</li>
 *   <li>The servers' {@code resolve} answer, the token with the policies and roles it links to, becomes grants in the
 *       order the servers build them (the token's policies and identities, then each role's), so the shared
 *       {@code AclAuthorizer} decides here as it does there.</li>
 *   <li>A resolution is cached until the shortest time to live of what it holds: the token's, and the policies' or
 *       the roles' when it links to any, on the injected clock. A secret no token has is not cached.</li>
 *   <li>The recovery token needs no server: it writes this client ({@code client:write} on its node name) and reads
 *       every node, as Consul's {@code agent_recovery} token.</li>
 *   <li>When no server answers, the down policy decides: {@code extend-cache} uses the cached resolution however old,
 *       and the default policy where there is none; {@code async-cache} answers from a stale entry at once and asks
 *       again behind it, and otherwise behaves as {@code extend-cache}; {@code allow} and {@code deny} allow or refuse
 *       everything that is not freshly cached.</li>
 * </ul>
 *
 * <p>The cost, as in Consul: a revoked token keeps working on a client until its entry expires, and for as long as no
 * server answers under {@code extend-cache}. The cache is held only in memory.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ClientAclResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientAclResolver.class);
    private static final String RECOVERY_ACCESSOR = "recovery";
    private static final String UNRESOLVED_ACCESSOR = "(unresolved: no server answered)";

    private record Entry(ClientPrincipal principal, Instant expires) {
    }

    private final ClientAclSettings settings;
    private final String nodeName;
    private final ClientTokens tokens;
    private final AclSource source;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean downReported = new AtomicBoolean();

    public ClientAclResolver(ClientAclSettings settings, String nodeName, ClientTokens tokens, AclSource source,
                             Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** ACLs disabled: every request is unrestricted. */
    public static ClientAclResolver disabled() {
        return new ClientAclResolver(ClientAclSettings.DISABLED, "", ClientTokens.none(),
                secret -> CompletableFuture.completedFuture(new AclLookup.Unavailable("ACLs are disabled")),
                Clock.systemUTC());
    }

    /** Whether ACLs are enabled. */
    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * Whom a request presenting {@code secret} acts as: the default token's principal, or the anonymous token's, when
     * {@code secret} is {@code null}; empty when no token has the secret.
     */
    public Optional<ClientPrincipal> resolve(String secret) {
        if (!settings.enabled()) return Optional.of(ClientPrincipal.unrestricted());
        if (tokens.isRecovery(secret)) return Optional.of(recovery());
        String asked = secret != null ? secret : tokens.defaultToken().orElse(null);
        String key = asked == null ? "" : asked;
        Entry entry = cache.get(key);
        Instant now = clock.instant();
        if (entry != null && now.isBefore(entry.expires())) return Optional.of(entry.principal());
        if (entry != null && settings.downPolicy() == DownPolicy.ASYNC_CACHE) {
            source.resolve(asked).thenAccept(lookup -> {
                if (lookup instanceof AclLookup.Unavailable unavailable) reportDown(unavailable);
                else remember(key, lookup);
            });
            return Optional.of(entry.principal());
        }
        AclLookup lookup = source.resolve(asked).join();
        if (lookup instanceof AclLookup.Unavailable unavailable) {
            reportDown(unavailable);
            return Optional.of(down(entry));
        }
        return remember(key, lookup);
    }

    private Optional<ClientPrincipal> remember(String key, AclLookup lookup) {
        if (lookup instanceof AclLookup.Found found) {
            ClientPrincipal principal;
            try {
                principal = principal(found.answer());
            } catch (RuntimeException malformed) {
                reportDown(new AclLookup.Unavailable("the answer could not be read: " + malformed.getMessage()));
                return Optional.of(down(cache.get(key)));
            }
            cache.put(key, new Entry(principal, clock.instant().plus(ttl(found.answer()))));
            if (downReported.compareAndSet(true, false)) {
                LOGGER.info("The servers answer the resolution of tokens again");
            }
            return Optional.of(principal);
        }
        cache.remove(key);
        return Optional.empty();
    }

    /** What the down policy gives with no server: {@code stale} is the expired entry, if any. */
    private ClientPrincipal down(Entry stale) {
        return switch (settings.downPolicy()) {
            case EXTEND_CACHE, ASYNC_CACHE -> stale != null ? stale.principal()
                    : ClientPrincipal.of(UNRESOLVED_ACCESSOR, List.of(), settings.defaultPolicy());
            case ALLOW -> ClientPrincipal.unrestricted();
            case DENY -> ClientPrincipal.deniedEverything();
        };
    }

    private void reportDown(AclLookup.Unavailable unavailable) {
        if (downReported.compareAndSet(false, true)) {
            LOGGER.warn("No server answered the resolution of a token, so the down policy {} decides until one does: {}",
                    settings.downPolicy().setting(), unavailable.reason());
        }
    }

    /** The shortest time to live of what {@code answer} holds. */
    private Duration ttl(JsonNode answer) {
        Duration ttl = settings.tokenTtl();
        if (!answer.path("policies").isEmpty() && settings.policyTtl().compareTo(ttl) < 0) ttl = settings.policyTtl();
        if (!answer.path("roles").isEmpty() && settings.roleTtl().compareTo(ttl) < 0) ttl = settings.roleTtl();
        return ttl;
    }

    private ClientPrincipal recovery() {
        return ClientPrincipal.of(RECOVERY_ACCESSOR, List.of(AclGrant.policy(RECOVERY_ACCESSOR,
                "the client's recovery token", null, new AclRules(List.of(
                        AclRule.exact(AclResource.CLIENT, nodeName, AclAccess.WRITE),
                        AclRule.prefix(AclResource.NODE, "", AclAccess.READ))))), AclDefaultPolicy.DENY);
    }

    /** The principal the servers' {@code resolve} answer describes, its grants in the servers' order. */
    private ClientPrincipal principal(JsonNode answer) {
        JsonNode token = answer.path("token");
        Map<String, JsonNode> policies = new HashMap<>();
        answer.path("policies").forEach(policy -> policies.put(policy.path("id").asText(), policy));
        List<AclGrant> grants = new ArrayList<>();
        addGrants(grants, token, policies, null);
        answer.path("roles").forEach(role -> addGrants(grants, role, policies, role.path("id").asText()));
        return ClientPrincipal.of(token.path("accessorId").asText(), grants, settings.defaultPolicy());
    }

    private static void addGrants(List<AclGrant> grants, JsonNode holder, Map<String, JsonNode> policies,
                                  String roleId) {
        holder.path("policies").forEach(link -> {
            JsonNode policy = policies.get(link.path("id").asText());
            if (policy != null) {
                grants.add(AclGrant.policy(policy.path("id").asText(), policy.path("name").asText(), roleId,
                        AclRulesJson.parse(policy.path("rules").toString())));
            }
        });
        holder.path("nodeIdentities").forEach(identity -> grants.add(AclGrant.of(
                new NodeIdentity(identity.path("nodeName").asText()), roleId)));
        holder.path("serviceIdentities").forEach(identity -> grants.add(AclGrant.of(new ServiceIdentity(
                identity.path("serviceName").asText(), text(identity, "tenant"), text(identity, "namespace")), roleId)));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
