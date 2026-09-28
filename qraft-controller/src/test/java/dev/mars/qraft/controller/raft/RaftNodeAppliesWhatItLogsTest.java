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

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.catalog.ServiceKey;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.raft.api.CommandCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a {@link RaftNode} leader applies exactly the command it logged, the command decoded from
 * its own encoded entry, so that no codec behaviour can make the leader's state differ from the state
 * its followers reach by decoding the replicated bytes; and that entries are compared by their
 * replicated bytes, so a retransmitted entry whose re-encoding differs does not fence a follower.
 *
 * <p>The cluster runs on manual timers ({@link ManualRaftCluster}): node a is elected by the test, and
 * followers learn the commit when the test fires a's heartbeat.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 2.0
 */
class RaftNodeAppliesWhatItLogsTest {
    private final Map<String, RaftNode> nodes = new LinkedHashMap<>();
    private final Map<String, QraftStateStore> stores = new LinkedHashMap<>();
    private final JavaRuntime runtime = JavaRuntime.create();
    private final ManualRaftCluster cluster = new ManualRaftCluster(runtime);

    @AfterEach
    void stopCluster() throws Exception {
        try {
            cluster.close();
        } finally {
            runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            InMemoryTransportSimulator.clearAllTransports();
        }
    }

    @Test
    void theLeaderAndItsFollowersApplyTheSameDecodedCommand() throws Exception {
        String leader = startCluster("a", "b", "c");

        nodes.get(leader).submitCommand(new DistributedStateRaftCommand(DistributedStateCommand.put("key", "value")))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        cluster.heartbeatUntil(nodes.get(leader), () -> stores.values().stream()
                .allMatch(store -> store.findMetadata("key").isPresent()), "every replica applies the command");
        assertEquals(Optional.of("value|decoded"), stores.get(leader).findMetadata("key"),
                "the leader applies the command as decoded from its own log entry");
        assertEquals(1, stores.values().stream().map(store -> store.findMetadata("key")).distinct().count(),
                "every replica holds the same value");
    }

    @Test
    void aRetransmittedEntryIsComparedByItsReplicatedBytesAndDoesNotFenceTheFollower() throws Exception {
        QraftStateStore store = new QraftStateStore();
        RaftNode follower = cluster.add(cluster.builder("follower", Set.of("follower", "leader"),
                new InMemoryTransportSimulator("follower"), store, RaftNodeMode.volatileMode()));
        follower.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        byte[] entry = registrationWhoseEncodingIsNotReproducedByReEncoding();
        AppendEntriesRequest request = AppendEntriesRequest.newBuilder().setTerm(1).setLeaderId("leader")
                .setPrevLogIndex(0).setPrevLogTerm(0).setLeaderCommit(1)
                .addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                        .setTerm(1).setIndex(1).setData(com.google.protobuf.ByteString.copyFrom(entry)))
                .build();

        AppendEntriesResponse first = follower.handleAppendEntriesRequest(request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        AppendEntriesResponse retransmitted = follower.handleAppendEntriesRequest(request)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertTrue(first.getSuccess());
        assertTrue(retransmitted.getSuccess(), "a duplicate of an identical entry is not a divergent log");
        assertEquals(1, retransmitted.getMatchIndex());
        assertTrue(!follower.isFenced(), "the follower must not fence itself");
        assertEquals(1, store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size());
    }

    /**
     * Encodes a registration with its metadata in an order that this JVM does not reproduce when it
     * re-encodes the decoded command, as happens when leader and follower iterate maps differently.
     */
    private static byte[] registrationWhoseEncodingIsNotReproducedByReEncoding() throws Exception {
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        Map<String, String> metadata = new LinkedHashMap<>();
        for (char key = 'a'; key <= 'h'; key++) metadata.put(String.valueOf(key), "v" + key);
        byte[] encoded = codec.serialize(dev.mars.qraft.controller.state.CatalogCommand.register(
                new dev.mars.qraft.catalog.ServiceInstance("web", "web", "agent-1", "127.0.0.1", 8080,
                        List.of(), metadata, dev.mars.qraft.catalog.ServiceHealth.UNKNOWN)));
        var message = dev.mars.qraft.controller.raft.grpc.RaftCommandMessage.parseFrom(encoded).toBuilder();
        var instance = message.getCatalogCommandBuilder().getInstanceBuilder();
        List<Map.Entry<String, String>> reversed = new java.util.ArrayList<>(instance.getMetadataMap().entrySet());
        java.util.Collections.reverse(reversed);
        instance.clearMetadata();
        reversed.forEach(pair -> instance.putMetadata(pair.getKey(), pair.getValue()));
        for (byte[] candidate : List.of(encoded, message.build().toByteArray())) {
            if (!java.util.Arrays.equals(candidate, codec.serialize(codec.deserialize(candidate)))) return candidate;
        }
        throw new IllegalStateException("could not construct an encoding that re-encoding changes");
    }

    /** Starts the members with the normalizing codec and elects the first. */
    private String startCluster(String... nodeIds) throws Exception {
        InMemoryTransportSimulator.clearAllTransports();
        Set<String> members = new LinkedHashSet<>(List.of(nodeIds));
        for (String nodeId : nodeIds) {
            QraftStateStore store = new QraftStateStore();
            nodes.put(nodeId, cluster.add(cluster.builder(nodeId, members, new InMemoryTransportSimulator(nodeId),
                    store, RaftNodeMode.volatileMode()).commandCodec(new NormalizingCodec())));
            stores.put(nodeId, store);
        }
        ManualRaftCluster.startAll(nodes.values().toArray(RaftNode[]::new));
        cluster.elect(nodes.get(nodeIds[0]));
        return nodeIds[0];
    }

    /** A codec whose decoding normalizes a value, standing in for any lossy or canonicalizing field. */
    private static final class NormalizingCodec implements CommandCodec<RaftCommand> {
        private final ProtobufRaftCommandCodec delegate = new ProtobufRaftCommandCodec();

        @Override
        public byte[] serialize(RaftCommand command) {
            return delegate.serialize(command);
        }

        @Override
        public RaftCommand deserialize(byte[] bytes) {
            RaftCommand command = delegate.deserialize(bytes);
            if (command instanceof DistributedStateRaftCommand state
                    && state.delegate() instanceof DistributedStateCommand.Put put
                    && !put.value().endsWith("|decoded")) {
                return new DistributedStateRaftCommand(DistributedStateCommand.put(put.key(), put.value() + "|decoded"));
            }
            return command;
        }
    }
}
