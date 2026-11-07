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
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides a request to a client's local API for the token it presents (ACL list, proposals 10 and 11).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class ClientAuthorizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientAuthorizer.class);
    private static final String PRINCIPAL = ClientAuthorizer.class.getName() + ".principal";
    private static final String TOKEN_HEADER = "X-Qraft-Token";
    private static final String BEARER = "Bearer ";

    private final JsonApi api = new JsonApi(new ObjectMapper());
    private final ClientAclResolver resolver;
    private final ClientTokens tokens;
    private final String nodeName;
    private final String tenant;
    private final String namespace;

    /** Decides for the client {@code nodeName}, whose services are in {@code tenant} and {@code namespace}. */
    public ClientAuthorizer(ClientAclResolver resolver, ClientTokens tokens, String nodeName, String tenant,
                            String namespace) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** ACLs disabled: every request is allowed, and a token is passed on only as it came. */
    public static ClientAuthorizer disabled() {
        return new ClientAuthorizer(ClientAclResolver.disabled(), ClientTokens.none(), "", "default", "default");
    }

    /** Whether ACLs are enabled. */
    public boolean enabled() {
        return resolver.enabled();
    }

    /** The client's tokens, which {@code PUT /v1/client/token/{kind}} changes. */
    public ClientTokens tokens() {
        return tokens;
    }

    /**
     * Answers 403 {@code token_not_found}, or 400 for a token that cannot be read, and returns {@code true} when the
     * request's token cannot be resolved; for a read that filters rather than refuses.
     */
    public boolean unauthenticated(HttpExchange exchange) throws IOException {
        return enabled() && principal(exchange) == null;
    }

    /**
     * Answers 403, or 400 for a token that cannot be read, and returns {@code true} when the request's caller may not
     * make {@code request}. A refusal is not an error: it is logged at INFO with the accessor ID, as on a server.
     */
    public boolean refused(HttpExchange exchange, AclRequest request) throws IOException {
        if (!enabled()) return false;
        ClientPrincipal principal = principal(exchange);
        if (principal == null) return true;
        if (principal.allows(request)) return false;
        String denied = principal.permissionDenied(request);
        LOGGER.info("ACL refused {} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), denied);
        api.error(exchange, 403, "permission_denied", denied, false, Map.of());
        return true;
    }

    /**
     * Whether the request's caller may make {@code request}; for filtering what a read answers, after
     * {@link #unauthenticated} has resolved the caller.
     */
    public boolean allows(HttpExchange exchange, AclRequest request) {
        if (!enabled()) return true;
        Object principal = exchange.getAttribute(PRINCIPAL);
        return principal instanceof ClientPrincipal resolved && resolved.allows(request);
    }

    /**
     * The token the request presents in {@code X-Qraft-Token} or {@code Authorization: Bearer}, if any; refused with
     * {@link IllegalArgumentException} when it holds a character that cannot be sent on.
     */
    public Optional<String> presented(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (token == null) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.startsWith(BEARER)) {
                token = authorization.substring(BEARER.length());
            }
        }
        if (token == null || token.isBlank()) return Optional.empty();
        return Optional.of(ClientTokens.requireSendable(token, "The request's token"));
    }

    /** The token to send a request on under: the one it presents, else the client's default token. */
    public Optional<String> forwarding(HttpExchange exchange) {
        return presented(exchange).or(tokens::defaultToken);
    }

    /**
     * Whom the request acts as, resolved once per request; or {@code null} after answering 400 {@code invalid_token}
     * for a token that cannot be read, or 403 {@code token_not_found} for a secret no token has.
     */
    private ClientPrincipal principal(HttpExchange exchange) throws IOException {
        Object known = exchange.getAttribute(PRINCIPAL);
        if (known instanceof ClientPrincipal principal) return principal;
        Optional<String> secret;
        try {
            secret = presented(exchange);
        } catch (IllegalArgumentException unreadable) {
            api.error(exchange, 400, "invalid_token", unreadable.getMessage(), false, Map.of());
            return null;
        }
        Optional<ClientPrincipal> principal = resolver.resolve(secret.orElse(null));
        if (principal.isEmpty()) {
            LOGGER.info("ACL refused {} {}: the token was not found", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath());
            api.error(exchange, 403, "token_not_found", "ACL not found", false, Map.of());
            return null;
        }
        exchange.setAttribute(PRINCIPAL, principal.get());
        return principal.get();
    }

    /** {@code service:read} on {@code name} in the client's scope. */
    public AclRequest serviceRead(String name) {
        return AclRequest.service(name, tenant, namespace, AclAccess.READ);
    }

    /** {@code service:write} on {@code name} in the client's scope. */
    public AclRequest serviceWrite(String name) {
        return AclRequest.service(name, tenant, namespace, AclAccess.WRITE);
    }

    /** {@code node:write} on the client's node. */
    public AclRequest nodeWrite() {
        return AclRequest.named(AclResource.NODE, nodeName, AclAccess.WRITE);
    }

    /** {@code client:read} on the client's node. */
    public AclRequest clientRead() {
        return AclRequest.named(AclResource.CLIENT, nodeName, AclAccess.READ);
    }

    /** {@code client:write} on the client's node. */
    public AclRequest clientWrite() {
        return AclRequest.named(AclResource.CLIENT, nodeName, AclAccess.WRITE);
    }
}
