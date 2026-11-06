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

package dev.mars.qraft.server.api;

import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.server.acl.AclResolver;
import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * With ACLs enabled, every call to a guarded gRPC service needs {@code operator:write}, from a token sent as
 * {@code x-qraft-token} metadata, until the key/value list adds {@code key} rules (ACL list, proposal 11). A call
 * without a token acts as the anonymous token. Either refusal closes the call with {@code PERMISSION_DENIED}, its
 * description starting with the code the HTTP API would answer, {@code token_not_found} or
 * {@code permission_denied}, as Consul answers both with 403. A refusal is logged at INFO with the accessor ID, never
 * the secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
public final class AclGrpcGuard implements ServerInterceptor {
    /** The metadata key that carries a call's token. */
    public static final String TOKEN_METADATA_KEY = "x-qraft-token";

    private static final Logger LOG = LoggerFactory.getLogger(AclGrpcGuard.class);
    private static final Metadata.Key<String> TOKEN =
            Metadata.Key.of(TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER);
    private static final AclRequest OPERATOR_WRITE = AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE);

    private final AclResolver resolver;

    private AclGrpcGuard(AclResolver resolver) {
        this.resolver = resolver;
    }

    /** {@code service}, with every call authorized through {@code resolver}; unchanged with ACLs disabled. */
    public static BindableService guard(BindableService service, AclResolver resolver) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(resolver, "resolver");
        if (!resolver.enabled()) return service;
        AclGrpcGuard guard = new AclGrpcGuard(resolver);
        return () -> ServerInterceptors.intercept(service, guard);
    }

    @Override
    public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
                                                       ServerCallHandler<Q, A> next) {
        String method = call.getMethodDescriptor().getFullMethodName();
        Optional<AclPrincipal> principal = resolver.resolve(headers.get(TOKEN));
        if (principal.isEmpty()) {
            LOG.info("ACL refused gRPC {}: the token was not found", method);
            return refuse(call, "token_not_found: " + AclResolver.TOKEN_NOT_FOUND_MESSAGE);
        }
        if (!principal.get().allows(OPERATOR_WRITE)) {
            LOG.info("ACL refused gRPC {}: accessor={} lacks operator:write", method, principal.get().accessorId());
            return refuse(call, "permission_denied: " + principal.get().permissionDenied(OPERATOR_WRITE));
        }
        return next.startCall(call, headers);
    }

    private static <Q, A> ServerCall.Listener<Q> refuse(ServerCall<Q, A> call, String description) {
        call.close(Status.PERMISSION_DENIED.withDescription(description), new Metadata());
        return new ServerCall.Listener<>() { };
    }
}
