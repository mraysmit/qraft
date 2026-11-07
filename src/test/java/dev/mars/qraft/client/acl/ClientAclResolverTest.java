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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a client resolves a token presented to its local API (ACL list, proposal 10, Phase 6): from the server's
 * {@code resolve} answer, into grants that decide as the server's would; cached for the shortest time to live of what
 * it holds, on an injected clock; the default token for a request without one, and else the anonymous token; the
 * recovery token with no server; and each of Consul's four down policies when no server answers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientAclResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = "client-1";
    private static final AclRequest WEB_WRITE = AclRequest.service("web", "default", "default", AclAccess.WRITE);
    private static final AclRequest DB_WRITE = AclRequest.service("db", "default", "default", AclAccess.WRITE);
    private static final AclRequest NODE_WRITE = AclRequest.named(AclResource.NODE, NODE, AclAccess.WRITE);
    private static final AclRequest CLIENT_WRITE = AclRequest.named(AclResource.CLIENT, NODE, AclAccess.WRITE);
    private static final AclRequest OPERATOR_READ = AclRequest.single(AclResource.OPERATOR, AclAccess.READ);
    /** A token linking to a policy that writes web, and to a role whose policy reads every node. */
    private static final String WRITER = """
            {"token":{"accessorId":"11111111-1111-4111-8111-111111111111","secretId":"<hidden>",
                      "policies":[{"id":"33333333-3333-4333-8333-333333333333","name":"web"}],
                      "roles":[{"id":"55555555-5555-4555-8555-555555555555","name":"nodes"}],
                      "nodeIdentities":[],"serviceIdentities":[]},
             "policies":[{"id":"33333333-3333-4333-8333-333333333333","name":"web",
                          "rules":{"service":{"web":{"policy":"write"}}}},
                         {"id":"44444444-4444-4444-8444-444444444444","name":"nodes",
                          "rules":{"node_prefix":{"":{"policy":"write"}}}}],
             "roles":[{"id":"55555555-5555-4555-8555-555555555555","name":"nodes",
                       "policies":[{"id":"44444444-4444-4444-8444-444444444444","name":"nodes"}],
                       "nodeIdentities":[],"serviceIdentities":[]}]}""";
    /** A token holding only a node identity for the client's node. */
    private static final String IDENTITY = """
            {"token":{"accessorId":"22222222-2222-4222-8222-222222222222","secretId":"<hidden>",
                      "policies":[],"roles":[],"nodeIdentities":[{"nodeName":"client-1"}],"serviceIdentities":[]},
             "policies":[],"roles":[]}""";
    private static final String ANONYMOUS = """
            {"token":{"accessorId":"00000000-0000-0000-0000-000000000002","secretId":"<hidden>",
                      "policies":[],"roles":[],"nodeIdentities":[],"serviceIdentities":[]},
             "policies":[],"roles":[]}""";

    @TempDir
    Path directory;

    private final ManualClock clock = new ManualClock();
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private volatile boolean reachable = true;
    private final AclSource source = secret -> {
        asked.add(String.valueOf(secret));
        if (!reachable) return CompletableFuture.completedFuture(new AclLookup.Unavailable("no server answered"));
        String answer = answers.get(String.valueOf(secret));
        try {
            return CompletableFuture.completedFuture(answer == null ? new AclLookup.NotFound()
                    : new AclLookup.Found(JSON.readTree(answer)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    @Test
    void aResolvedTokenDecidesAsTheServersWouldThroughItsPoliciesRolesAndIdentities() {
        answers.put("writer", WRITER);
        answers.put("identity", IDENTITY);
        ClientAclResolver resolver = resolver(DownPolicy.EXTEND_CACHE, ClientTokens.none());

        ClientPrincipal writer = resolver.resolve("writer").orElseThrow();
        assertEquals("11111111-1111-4111-8111-111111111111", writer.accessorId());
        assertTrue(writer.allows(WEB_WRITE), "through its own policy");
        assertTrue(writer.allows(NODE_WRITE), "through its role's policy");
        assertFalse(writer.allows(DB_WRITE));
        assertTrue(writer.permissionDenied(DB_WRITE).contains("lacks permission 'service:write' on \"db\""));

        ClientPrincipal identity = resolver.resolve("identity").orElseThrow();
        assertTrue(identity.allows(NODE_WRITE), "a node identity writes its node");
        assertTrue(identity.allows(AclRequest.service("db", "acme", "prod", AclAccess.READ)),
                "and reads every service");
        assertFalse(identity.allows(WEB_WRITE));
        assertTrue(resolver.resolve("nobody").isEmpty(), "no token has the secret");
    }

    @Test
    void aResolutionIsCachedForTheShortestTimeToLiveOfWhatItHolds() {
        answers.put("writer", WRITER);
        answers.put("identity", IDENTITY);
        ClientAclResolver resolver = new ClientAclResolver(settings(DownPolicy.EXTEND_CACHE, Duration.ofSeconds(30),
                Duration.ofSeconds(20), Duration.ofSeconds(10)), NODE, ClientTokens.none(), source, clock);

        resolver.resolve("writer");
        resolver.resolve("writer");
        assertEquals(List.of("writer"), asked, "cached");
        clock.advance(Duration.ofSeconds(10));
        resolver.resolve("writer");
        assertEquals(List.of("writer", "writer"), asked, "its role's time to live has passed");

        resolver.resolve("identity");
        clock.advance(Duration.ofSeconds(29));
        resolver.resolve("identity");
        assertEquals(3, asked.size(), "a token with no policy or role lives for the token's time");
        clock.advance(Duration.ofSeconds(1));
        resolver.resolve("identity");
        assertEquals(4, asked.size());

        resolver.resolve("nobody");
        resolver.resolve("nobody");
        assertEquals(6, asked.size(), "a secret no token has is not cached");
    }

    @Test
    void aRequestWithoutATokenUsesTheDefaultTokenElseTheAnonymousToken() throws Exception {
        answers.put("default-secret", IDENTITY);
        answers.put("null", ANONYMOUS);
        ClientTokens withDefault = ClientTokens.load(withFiles(Map.of(TokenKind.DEFAULT,
                Files.writeString(directory.resolve("default"), "default-secret"))), directory);

        assertEquals("22222222-2222-4222-8222-222222222222",
                resolver(DownPolicy.EXTEND_CACHE, withDefault).resolve(null).orElseThrow().accessorId());
        assertEquals("00000000-0000-0000-0000-000000000002",
                resolver(DownPolicy.EXTEND_CACHE, ClientTokens.none()).resolve(null).orElseThrow().accessorId());
        assertEquals(List.of("default-secret", "null"), asked, "the anonymous token is asked for with no token");
    }

    @Test
    void theRecoveryTokenWritesTheClientAndReadsNodesWithNoServer() throws Exception {
        reachable = false;
        ClientTokens tokens = ClientTokens.load(withFiles(Map.of(TokenKind.RECOVERY,
                Files.writeString(directory.resolve("recovery"), "recovery-secret"))), directory);

        ClientPrincipal recovery = resolver(DownPolicy.DENY, tokens).resolve("recovery-secret").orElseThrow();

        assertTrue(recovery.allows(CLIENT_WRITE));
        assertTrue(recovery.allows(AclRequest.named(AclResource.NODE, "any-node", AclAccess.READ)));
        assertFalse(recovery.allows(NODE_WRITE));
        assertFalse(recovery.allows(WEB_WRITE));
        assertTrue(asked.isEmpty(), "no server is asked");
    }

    @Test
    void extendCacheUsesWhatIsCachedHoweverOldAndTheDefaultPolicyWhereNothingIs() {
        answers.put("writer", WRITER);
        ClientAclResolver resolver = resolver(DownPolicy.EXTEND_CACHE, ClientTokens.none());
        resolver.resolve("writer");
        reachable = false;
        clock.advance(Duration.ofHours(1));

        assertTrue(resolver.resolve("writer").orElseThrow().allows(WEB_WRITE), "the stale entry is used");
        ClientPrincipal uncached = resolver.resolve("never-seen").orElseThrow();
        assertFalse(uncached.allows(WEB_WRITE), "the default policy, deny, decides");

        ClientAclResolver allowing = new ClientAclResolver(new ClientAclSettings(true, AclDefaultPolicy.ALLOW,
                DownPolicy.EXTEND_CACHE, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL,
                ClientAclSettings.DEFAULT_TTL, false, Map.of()), NODE, ClientTokens.none(), source, clock);
        ClientPrincipal allowed = allowing.resolve("never-seen").orElseThrow();
        assertTrue(allowed.allows(WEB_WRITE), "the default policy, allow, decides");
        assertFalse(allowed.allows(AclRequest.single(AclResource.ACL, AclAccess.READ)), "and never grants acl");
    }

    @Test
    void asyncCacheAnswersFromTheStaleEntryAndAsksAgainBehindIt() {
        answers.put("writer", WRITER);
        ClientAclResolver resolver = resolver(DownPolicy.ASYNC_CACHE, ClientTokens.none());
        resolver.resolve("writer");
        clock.advance(Duration.ofMinutes(1));
        answers.put("writer", IDENTITY);

        ClientPrincipal stale = resolver.resolve("writer").orElseThrow();

        assertTrue(stale.allows(WEB_WRITE), "the stale entry answers at once");
        assertEquals(2, asked.size(), "and the token is asked for again");
        assertFalse(resolver.resolve("writer").orElseThrow().allows(WEB_WRITE), "the fresh answer replaces it");
        reachable = false;
        clock.advance(Duration.ofMinutes(1));
        assertFalse(resolver.resolve("writer").orElseThrow().allows(WEB_WRITE),
                "with no server it goes on as extend-cache");
    }

    @Test
    void allowAndDenyDecideEverythingThatIsNotFreshlyCached() {
        answers.put("writer", WRITER);
        ClientAclResolver allow = resolver(DownPolicy.ALLOW, ClientTokens.none());
        ClientAclResolver deny = resolver(DownPolicy.DENY, ClientTokens.none());
        allow.resolve("writer");
        deny.resolve("writer");
        reachable = false;

        assertTrue(deny.resolve("writer").orElseThrow().allows(WEB_WRITE), "a fresh entry is still used");
        clock.advance(Duration.ofMinutes(1));
        assertTrue(allow.resolve("writer").orElseThrow().allows(OPERATOR_READ), "allow allows everything");
        assertTrue(allow.resolve("never-seen").orElseThrow().allows(DB_WRITE));
        assertFalse(deny.resolve("writer").orElseThrow().allows(WEB_WRITE), "deny denies everything");
        assertFalse(deny.resolve("never-seen").orElseThrow().allows(AclRequest.named(AclResource.NODE, "x",
                AclAccess.READ)));
    }

    @Test
    void aRevokedTokenWorksUntilItsEntryExpiresAndNotAfter() {
        answers.put("writer", WRITER);
        ClientAclResolver resolver = resolver(DownPolicy.EXTEND_CACHE, ClientTokens.none());
        assertTrue(resolver.resolve("writer").orElseThrow().allows(WEB_WRITE));

        answers.remove("writer");
        clock.advance(ClientAclSettings.DEFAULT_TTL.minusMillis(1));
        assertTrue(resolver.resolve("writer").orElseThrow().allows(WEB_WRITE),
                "the bound, as in Consul: a revoked token keeps working until its cache entry expires");
        clock.advance(Duration.ofMillis(1));
        assertTrue(resolver.resolve("writer").isEmpty(), "and not after");
    }

    @Test
    void withAclsDisabledEverythingIsAllowedAndNoServerIsAsked() {
        ClientAclResolver resolver = new ClientAclResolver(ClientAclSettings.DISABLED, NODE, ClientTokens.none(),
                source, clock);

        assertFalse(resolver.enabled());
        assertTrue(resolver.resolve("anything").orElseThrow().allows(OPERATOR_READ));
        assertTrue(asked.isEmpty());
    }

    private ClientAclResolver resolver(DownPolicy downPolicy, ClientTokens tokens) {
        return new ClientAclResolver(settings(downPolicy, ClientAclSettings.DEFAULT_TTL,
                ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL), NODE, tokens, source, clock);
    }

    private static ClientAclSettings settings(DownPolicy downPolicy, Duration token, Duration policy, Duration role) {
        return new ClientAclSettings(true, AclDefaultPolicy.DENY, downPolicy, token, policy, role, false, Map.of());
    }

    private static ClientAclSettings withFiles(Map<TokenKind, Path> files) {
        return new ClientAclSettings(true, AclDefaultPolicy.DENY, DownPolicy.EXTEND_CACHE,
                ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, ClientAclSettings.DEFAULT_TTL, false,
                files);
    }

    /** A clock the test moves. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
