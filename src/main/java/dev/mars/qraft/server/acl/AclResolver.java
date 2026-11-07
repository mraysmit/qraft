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

package dev.mars.qraft.server.acl;

import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the secret a request presents to the principal it acts as, against the answering server's applied ACL
 * state (ACL list, proposal 10). A request without a secret acts as the anonymous token. With ACLs disabled, every
 * request is unrestricted and a presented secret is ignored, as Consul ignores tokens then.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
public final class AclResolver {
    /** The refusal of a secret that names no token, in Consul's words. */
    public static final String TOKEN_NOT_FOUND_MESSAGE = "ACL not found";

    private static final AclResolver DISABLED = new AclResolver(null, null, null);

    private final AclDefaultPolicy defaultPolicy;
    private final AclView view;
    private final Clock clock;

    private AclResolver(AclDefaultPolicy defaultPolicy, AclView view, Clock clock) {
        this.defaultPolicy = defaultPolicy;
        this.view = view;
        this.clock = clock;
    }

    /** ACLs disabled: every request is unrestricted. */
    public static AclResolver disabled() {
        return DISABLED;
    }

    /**
     * ACLs enabled, deciding under {@code defaultPolicy} from the state {@code view} shows, and judging a token's
     * expiry by {@code clock}: a token whose expiry has come is refused as not found, as in Consul, before the leader
     * has deleted it (Phase 8).
     */
    public static AclResolver enabled(AclDefaultPolicy defaultPolicy, AclView view, Clock clock) {
        return new AclResolver(Objects.requireNonNull(defaultPolicy, "defaultPolicy"),
                Objects.requireNonNull(view, "view"), Objects.requireNonNull(clock, "clock"));
    }

    /** As {@link #enabled(AclDefaultPolicy, AclView, Clock)}, on the system clock. */
    public static AclResolver enabled(AclDefaultPolicy defaultPolicy, AclView view) {
        return enabled(defaultPolicy, view, Clock.systemUTC());
    }

    /** Whether ACLs are enabled. */
    public boolean enabled() {
        return defaultPolicy != null;
    }

    /**
     * The principal a request presenting {@code secret} acts as: the anonymous token when {@code secret} is
     * {@code null} or empty, and empty when the secret names no token.
     */
    public Optional<AclPrincipal> resolve(String secret) {
        if (!enabled()) return Optional.of(AclPrincipal.unrestricted());
        Optional<AclToken> token = secret == null || secret.isEmpty()
                ? view.token(AclBuiltins.ANONYMOUS_TOKEN_ID) : view.tokenBySecret(secret);
        return token.filter(this::unexpired).map(found -> AclPrincipal.of(found, view.grants(found), defaultPolicy));
    }

    private boolean unexpired(AclToken token) {
        return token.expirationTime() == null || clock.instant().isBefore(token.expirationTime());
    }
}
