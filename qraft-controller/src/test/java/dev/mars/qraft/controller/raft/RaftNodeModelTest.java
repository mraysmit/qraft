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

import com.google.protobuf.ByteString;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.raftlog.storage.RaftStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded model tests comparing generated follower histories on {@link RaftNode} against an
 * independent reference model, including fencing after an ambiguous sync failure.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-17
 * @version 1.0
 */
@RemediationTest(phase = "7-node-model", scenarioPrefix = "RAFT-NODE-MODEL")
class RaftNodeModelTest {
    private static final long[] REGRESSION_SEEDS = {
            0x4E4F_4445L, 0x5141_4654L, 0x5EED_1001L, 0x5EED_1002L
    };

    private JavaRuntime runtime;
    private RaftNode node;

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) await(node.stop());
        if (runtime != null) {
            runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void generatedFollowerHistoriesMatchIndependentNodeModel() {
        for (long seed : configuredSeeds()) {
            try {
                verifyFollowerHistory(seed, false);
            } catch (Throwable error) {
                throw new AssertionError("Reproduce with -Dqraft.node.model.seed=" + seed, error);
            } finally {
                stopNode();
            }
        }
    }

    @Test
    void generatedPrehistoryThenAmbiguousSyncFailureFencesFurtherMutation() {
        for (long seed : configuredSeeds()) {
            try {
                verifyFollowerHistory(seed, true);
            } catch (Throwable error) {
                throw new AssertionError("Reproduce with -Dqraft.node.model.seed=" + seed, error);
            } finally {
                stopNode();
            }
        }
    }

    private void verifyFollowerHistory(long seed, boolean injectFailure) {
        Fixture fixture = startFollower();
        TestRaftStorage storage = fixture.storage();
        QraftStateStore stateStore = fixture.stateStore();
        Random random = new Random(seed);
        ReferenceState expected = ReferenceState.initial();

        List<Operation> operations = generatedOperations(random, injectFailure ? 24 : 64);
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index).materialize(expected, index, random);
            ExpectedResult result = applyReference(expected, operation);
            executeAndAssert(operation, result, seed, index);
            expected = result.state();
            assertNodeState(expected, storage, stateStore, seed, index);
        }

        if (!injectFailure) return;

        ReferenceState beforeFailure = expected;
        storage.setFailOnSync(true);
        AppendOperation failing = validAppend(expected, 10_000, "failure", "ambiguous");
        AppendEntriesResponse failed = await(node.handleAppendEntriesRequest(failing.request()));
        assertFalse(failed.getSuccess(), context(seed, operations.size()));
        assertTrue(node.isFenced(), context(seed, operations.size()));
        assertNodeState(beforeFailure, storage, stateStore, seed, operations.size(), 1);

        int persistedAfterFailure = storage.getLog().size();
        AppendEntriesResponse fenced = await(node.handleAppendEntriesRequest(
                AppendEntriesRequest.newBuilder()
                        .setTerm(beforeFailure.term())
                        .setLeaderId("leader")
                        .setPrevLogIndex(beforeFailure.lastIndex())
                        .setPrevLogTerm(beforeFailure.lastTerm())
                        .build()));
        assertFalse(fenced.getSuccess(), context(seed, operations.size() + 1));
        assertEquals(persistedAfterFailure, storage.getLog().size(),
                context(seed, operations.size() + 1));
    }

    private Fixture startFollower() {
        runtime = JavaRuntime.create();
        TestRaftStorage storage = new TestRaftStorage();
        storage.open(null).join();
        QraftStateStore stateStore = new QraftStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("follower")
                .clusterNodes(Set.of("follower", "leader", "candidate-a", "candidate-b"))
                .transport(new InMemoryTransportSimulator("follower"))
                .stateMachine(stateStore)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.durable(storage, storage))
                .electionTimeout(60_000)
                .heartbeatInterval(10_000)
                .build();
        await(node.start());
        return new Fixture(storage, stateStore);
    }

    private void stopNode() {
        if (node != null) {
            await(node.stop());
            node = null;
        }
        if (runtime != null) {
            await(runtime.shutdown());
            runtime = null;
        }
        InMemoryTransportSimulator.clearAllTransports();
    }

    private static List<Operation> generatedOperations(Random random, int count) {
        List<Operation> operations = new ArrayList<>(count);
        operations.add(new AppendTemplate(TermChoice.HIGHER, AppendShape.EXTEND, 1, false));
        operations.add(new AppendTemplate(TermChoice.SAME, AppendShape.EXTEND, 0, false));
        operations.add(new AppendTemplate(TermChoice.HIGHER, AppendShape.REPLACE_UNCOMMITTED, 1, false));
        operations.add(new VoteTemplate(TermChoice.SAME, true, "candidate-a"));
        operations.add(new VoteTemplate(TermChoice.SAME, true, "candidate-b"));
        operations.add(new AppendTemplate(TermChoice.STALE, AppendShape.HEARTBEAT, 0, false));
        while (operations.size() < count) {
            if (random.nextInt(4) == 0) {
                operations.add(new VoteTemplate(
                        TermChoice.values()[random.nextInt(TermChoice.values().length)],
                        random.nextBoolean(),
                        random.nextBoolean() ? "candidate-a" : "candidate-b"));
            } else {
                AppendShape shape = random.nextInt(5) == 0
                        ? AppendShape.INCONSISTENT_HEARTBEAT
                        : random.nextBoolean() ? AppendShape.EXTEND : AppendShape.HEARTBEAT;
                operations.add(new AppendTemplate(
                        TermChoice.values()[random.nextInt(TermChoice.values().length)],
                        shape, random.nextInt(3), random.nextBoolean()));
            }
        }
        return operations;
    }

    private void executeAndAssert(
            Operation operation, ExpectedResult expected, long seed, int index) {
        String context = context(seed, index);
        switch (operation) {
            case AppendOperation append -> {
                AppendEntriesResponse actual = await(node.handleAppendEntriesRequest(append.request()));
                assertEquals(expected.accepted(), actual.getSuccess(), context);
                assertEquals(expected.state().term(), actual.getTerm(), context);
                if (actual.getSuccess()) {
                    assertEquals(expected.state().lastIndex(), actual.getMatchIndex(), context);
                }
            }
            case VoteOperation vote -> {
                VoteResponse actual = await(node.handleVoteRequest(vote.request()));
                assertEquals(expected.accepted(), actual.getVoteGranted(), context);
                assertEquals(expected.state().term(), actual.getTerm(), context);
            }
            default -> throw new IllegalStateException("Unmaterialized operation: " + operation);
        }
    }

    private static ExpectedResult applyReference(ReferenceState state, Operation operation) {
        return switch (operation) {
            case AppendOperation append -> applyAppend(state, append);
            case VoteOperation vote -> applyVote(state, vote);
            default -> throw new IllegalStateException("Unmaterialized operation: " + operation);
        };
    }

    private static ExpectedResult applyAppend(ReferenceState state, AppendOperation operation) {
        AppendEntriesRequest request = operation.request();
        if (request.getTerm() < state.term()) return new ExpectedResult(state, false);

        long term = request.getTerm();
        String vote = request.getTerm() > state.term() ? null : state.vote();
        if (!state.has(request.getPrevLogIndex())
                || state.termAt(request.getPrevLogIndex()) != request.getPrevLogTerm()) {
            return new ExpectedResult(state.withTermAndVote(term, vote), false);
        }

        List<ModelEntry> log = new ArrayList<>(state.log());
        long entryIndex = request.getPrevLogIndex() + 1;
        int incomingIndex = 0;
        while (incomingIndex < operation.entries().size() && entryIndex <= log.size()) {
            ModelEntry incoming = operation.entries().get(incomingIndex);
            if (log.get(Math.toIntExact(entryIndex - 1)).term() != incoming.term()) {
                log.subList(Math.toIntExact(entryIndex - 1), log.size()).clear();
                break;
            }
            entryIndex++;
            incomingIndex++;
        }
        while (incomingIndex < operation.entries().size()) {
            log.add(operation.entries().get(incomingIndex++));
        }

        long commit = Math.max(state.commitIndex(), Math.min(request.getLeaderCommit(), log.size()));
        Map<String, String> applied = new LinkedHashMap<>(state.applied());
        for (long index = state.lastApplied() + 1; index <= commit; index++) {
            ModelEntry entry = log.get(Math.toIntExact(index - 1));
            applied.put(entry.key(), entry.value());
        }
        return new ExpectedResult(new ReferenceState(
                term, vote, List.copyOf(log), commit, commit, Map.copyOf(applied)), true);
    }

    private static ExpectedResult applyVote(ReferenceState state, VoteOperation operation) {
        VoteRequest request = operation.request();
        if (request.getTerm() < state.term()) return new ExpectedResult(state, false);

        long term = request.getTerm();
        String vote = request.getTerm() > state.term() ? null : state.vote();
        boolean upToDate = request.getLastLogTerm() > state.lastTerm()
                || request.getLastLogTerm() == state.lastTerm()
                && request.getLastLogIndex() >= state.lastIndex();
        boolean granted = upToDate && (vote == null || vote.equals(request.getCandidateId()));
        if (granted) vote = request.getCandidateId();
        return new ExpectedResult(state.withTermAndVote(term, vote), granted);
    }

    private void assertNodeState(
            ReferenceState expected,
            TestRaftStorage storage,
            QraftStateStore stateStore,
            long seed,
            int index) {
        assertNodeState(expected, storage, stateStore, seed, index, 0);
    }

    private void assertNodeState(
            ReferenceState expected,
            TestRaftStorage storage,
            QraftStateStore stateStore,
            long seed,
            int index,
            int ambiguousTail) {
        String context = context(seed, index);
        assertEquals(expected.term(), node.getCurrentTerm(), context);
        assertEquals(expected.vote(), node.getVotedFor(), context);
        assertEquals(expected.commitIndex(), node.getCommitIndex(), context);
        assertEquals(expected.lastApplied(), node.getLastApplied(), context);
        assertEquals(expected.log().size() + 1, node.getLogSize(), context);

        List<RaftStorage.LogEntryData> persisted = storage.getLog();
        assertEquals(expected.log().size() + ambiguousTail, persisted.size(), context);
        for (int entryIndex = 0; entryIndex < expected.log().size(); entryIndex++) {
            ModelEntry entry = expected.log().get(entryIndex);
            assertEquals(entryIndex + 1L, persisted.get(entryIndex).index(), context);
            assertEquals(entry.term(), persisted.get(entryIndex).term(), context);
        }
        expected.applied().forEach((key, value) ->
                assertEquals(Optional.of(value), stateStore.findMetadata(key), context));
    }

    private static AppendOperation validAppend(
            ReferenceState state, int ordinal, String key, String value) {
        long term = Math.max(1, state.term());
        ModelEntry entry = new ModelEntry(term, key + ordinal, value + ordinal);
        return appendOperation(term, state.lastIndex(), state.lastTerm(),
                state.lastIndex(), List.of(entry));
    }

    private static AppendOperation appendOperation(
            long term, long prevIndex, long prevTerm, long leaderCommit, List<ModelEntry> entries) {
        AppendEntriesRequest.Builder request = AppendEntriesRequest.newBuilder()
                .setTerm(term)
                .setLeaderId("leader")
                .setPrevLogIndex(prevIndex)
                .setPrevLogTerm(prevTerm)
                .setLeaderCommit(leaderCommit);
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (ModelEntry entry : entries) {
            byte[] bytes = codec.serialize(new DistributedStateRaftCommand(
                    DistributedStateCommand.put(entry.key(), entry.value())));
            request.addEntries(dev.mars.qraft.controller.raft.grpc.LogEntry.newBuilder()
                    .setTerm(entry.term())
                    .setData(ByteString.copyFrom(bytes))
                    .build());
        }
        return new AppendOperation(request.build(), entries);
    }

    private static long selectedTerm(TermChoice choice, long currentTerm) {
        return switch (choice) {
            case STALE -> Math.max(0, currentTerm - 1);
            case SAME -> currentTerm;
            case HIGHER -> currentTerm + 1;
        };
    }

    private static long[] configuredSeeds() {
        String configured = System.getProperty("qraft.node.model.seed");
        return configured == null ? REGRESSION_SEEDS : new long[]{Long.decode(configured)};
    }

    private static String context(long seed, int operation) {
        return "seed=" + seed + ", operation=" + operation;
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(10, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private sealed interface Operation permits AppendTemplate, VoteTemplate, AppendOperation, VoteOperation {
        default Operation materialize(ReferenceState state, int ordinal, Random random) {
            return this;
        }
    }

    private record AppendTemplate(
            TermChoice termChoice, AppendShape shape, int entryCount, boolean commitAll)
            implements Operation {
        @Override
        public Operation materialize(ReferenceState state, int ordinal, Random random) {
            long term = selectedTerm(termChoice, state.term());
            long prevIndex = state.lastIndex();
            long prevTerm = state.lastTerm();
            List<ModelEntry> entries = new ArrayList<>();
            switch (shape) {
                case HEARTBEAT -> { }
                case INCONSISTENT_HEARTBEAT -> {
                    prevIndex = state.lastIndex() + 1;
                    prevTerm = term;
                }
                case EXTEND -> {
                    int count = Math.max(1, entryCount);
                    for (int i = 0; i < count; i++) {
                        entries.add(new ModelEntry(term, "model-" + ordinal + "-" + i,
                                "value-" + random.nextInt(10_000)));
                    }
                }
                case REPLACE_UNCOMMITTED -> {
                    prevIndex = Math.max(state.commitIndex(), state.lastIndex() - 1);
                    prevTerm = state.termAt(prevIndex);
                    entries.add(new ModelEntry(term, "model-replacement-" + ordinal,
                            "value-" + random.nextInt(10_000)));
                }
            }
            long leaderCommit = commitAll ? prevIndex + entries.size() : state.commitIndex();
            return appendOperation(term, prevIndex, prevTerm, leaderCommit, List.copyOf(entries));
        }
    }

    private record VoteTemplate(TermChoice termChoice, boolean upToDate, String candidate)
            implements Operation {
        @Override
        public Operation materialize(ReferenceState state, int ordinal, Random random) {
            long term = selectedTerm(termChoice, state.term());
            long lastIndex = upToDate ? state.lastIndex() : Math.max(0, state.lastIndex() - 1);
            long lastTerm = upToDate ? state.lastTerm() : 0;
            return new VoteOperation(VoteRequest.newBuilder()
                    .setTerm(term)
                    .setCandidateId(candidate)
                    .setLastLogIndex(lastIndex)
                    .setLastLogTerm(lastTerm)
                    .build());
        }
    }

    private record AppendOperation(AppendEntriesRequest request, List<ModelEntry> entries)
            implements Operation { }

    private record VoteOperation(VoteRequest request) implements Operation { }

    private enum TermChoice { STALE, SAME, HIGHER }

    private enum AppendShape { HEARTBEAT, INCONSISTENT_HEARTBEAT, EXTEND, REPLACE_UNCOMMITTED }

    private record ModelEntry(long term, String key, String value) { }

    private record ExpectedResult(ReferenceState state, boolean accepted) { }

    private record ReferenceState(
            long term,
            String vote,
            List<ModelEntry> log,
            long commitIndex,
            long lastApplied,
            Map<String, String> applied) {
        static ReferenceState initial() {
            return new ReferenceState(0, null, List.of(), 0, 0, Map.of());
        }

        long lastIndex() {
            return log.size();
        }

        long lastTerm() {
            return termAt(lastIndex());
        }

        boolean has(long index) {
            return index >= 0 && index <= log.size();
        }

        long termAt(long index) {
            return index == 0 ? 0 : log.get(Math.toIntExact(index - 1)).term();
        }

        ReferenceState withTermAndVote(long newTerm, String newVote) {
            return new ReferenceState(newTerm, newVote, log, commitIndex, lastApplied, applied);
        }
    }

    private record Fixture(TestRaftStorage storage, QraftStateStore stateStore) { }
}
