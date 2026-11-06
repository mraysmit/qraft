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

import dev.mars.qraft.common.acl.AclDefaultPolicy;
import dev.mars.qraft.common.acl.AclRulesJson;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.GrpcServiceServer;
import dev.mars.qraft.raft.InMemoryTransportSimulatorFixture;
import dev.mars.qraft.raft.ManualRaftClusterFixture;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.acl.AclResolver;
import dev.mars.qraft.server.api.grpc.DistributedStateServiceGrpc;
import dev.mars.qraft.server.api.grpc.GetRequest;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclPolicy;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that, with ACLs enabled, every call to the gRPC {@code DistributedStateService} needs {@code operator:write}
 * from a token sent as {@code x-qraft-token} metadata (ACL list, proposal 11, Phase 4): no token is the anonymous
 * token, an unknown one is not found, and either refusal is {@code PERMISSION_DENIED} naming its code and never the
 * secret. With ACLs disabled the service is unguarded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-06
 * @version 1.0
 */
class AclGrpcGuardTest {
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String READER_ACCESSOR = "3d4e5f60-7182-4a39-a4b5-c6d7e8f9a0b1";
    private static final String READER = "9d4e6f51-be70-4182-ad9e-0f1a2b3c4d5e";
    private static final String WRITER = "ae5f7062-cf81-4293-be0f-1a2b3c4d5e6f";
    private static final String UNKNOWN = "bf608173-d092-43a4-8f10-2b3c4d5e6f70";

    private RaftExecutorsFixture runtime;
    private ManualRaftClusterFixture cluster;
    private RaftNode node;
    private final QraftStateStore acl = new QraftStateStore();
    private GrpcServiceServer server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        runtime = RaftExecutorsFixture.create();
        cluster = new ManualRaftClusterFixture(runtime);
        InMemoryTransportSimulatorFixture.clearAllTransports();
        node = cluster.add(cluster.builder("single", Set.of("single"), new InMemoryTransportSimulatorFixture("single"),
                new QraftStateStore(), RaftNodeMode.volatileMode()));
        ManualRaftClusterFixture.startAll(node);
        createToken(READER_ACCESSOR, READER, "{\"operator\":\"read\"}");
        createToken(UUID.randomUUID().toString(), WRITER, "{\"operator\":\"write\"}");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (channel != null) channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
            if (server != null) server.stop().get(10, TimeUnit.SECONDS);
            cluster.close();
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulatorFixture.clearAllTransports();
        }
    }

    @Test
    void withoutATokenACallActsAsTheAnonymousTokenAndIsRefused() throws Exception {
        serve(AclResolver.enabled(AclDefaultPolicy.DENY, acl.getAcl()));

        StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () -> get(null));

        assertEquals(Status.Code.PERMISSION_DENIED, refused.getStatus().getCode());
        assertEquals("permission_denied: Permission denied: token with AccessorID '" + AclBuiltins.ANONYMOUS_TOKEN_ID
                + "' lacks permission 'operator:write'", refused.getStatus().getDescription());
    }

    @Test
    void anUnknownTokenIsNotFound() throws Exception {
        serve(AclResolver.enabled(AclDefaultPolicy.ALLOW, acl.getAcl()));

        StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () -> get(UNKNOWN));

        assertEquals(Status.Code.PERMISSION_DENIED, refused.getStatus().getCode());
        assertEquals("token_not_found: ACL not found", refused.getStatus().getDescription());
    }

    @Test
    void aCallNeedsOperatorWriteEvenToRead() throws Exception {
        serve(AclResolver.enabled(AclDefaultPolicy.DENY, acl.getAcl()));

        StatusRuntimeException refused = assertThrows(StatusRuntimeException.class, () -> get(READER));
        assertEquals(Status.Code.PERMISSION_DENIED, refused.getStatus().getCode());
        assertEquals("permission_denied: Permission denied: token with AccessorID '" + READER_ACCESSOR
                + "' lacks permission 'operator:write'", refused.getStatus().getDescription());

        assertFalse(get(WRITER), "an operator:write token is served");
    }

    @Test
    void withAclsDisabledTheServiceIsUnguarded() throws Exception {
        serve(AclResolver.disabled());

        assertFalse(get(null));
        assertFalse(get(UNKNOWN));
    }

    private void serve(AclResolver resolver) throws Exception {
        server = new GrpcServiceServer(runtime.blockingTasks(), 0,
                AclGrpcGuard.guard(new DistributedStateGrpcService(node, new QraftStateStore()), resolver));
        server.start().get(10, TimeUnit.SECONDS);
        channel = ManagedChannelBuilder.forAddress("localhost", server.port()).usePlaintext().build();
    }

    /** Reads a key that does not exist, so a call that is served answers {@code false}. */
    private boolean get(String token) {
        DistributedStateServiceGrpc.DistributedStateServiceBlockingStub stub =
                DistributedStateServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
        if (token != null) {
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of(AclGrpcGuard.TOKEN_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER), token);
            stub = stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
        }
        return stub.get(GetRequest.newBuilder().setKey("missing").build()).getFound();
    }

    private void createToken(String accessor, String secret, String rules) {
        String policyId = UUID.randomUUID().toString();
        assertInstanceOf(RaftCommandResult.Success.class, acl.apply(AclCommand.createPolicy(
                new AclPolicy(policyId, "policy-" + accessor, "", AclRulesJson.parse(rules)))));
        assertInstanceOf(RaftCommandResult.Success.class, acl.apply(AclCommand.createToken(new AclToken(accessor,
                AclSecrets.hash(secret), "", List.of(policyId), List.of(), List.of(), List.of(), NOW, null, false))));
    }
}
