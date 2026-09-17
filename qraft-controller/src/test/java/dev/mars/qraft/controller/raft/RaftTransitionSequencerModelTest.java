/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.testsupport.RemediationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@RemediationTest(phase = "6-model", scenarioPrefix = "RAFT-MODEL-HISTORY")
class RaftTransitionSequencerModelTest {
    private static final long[] REGRESSION_SEEDS = {
            0x5141_4654L, 0x5EED_0001L, 0x5EED_0002L, 0x5EED_0003L
    };

    private JavaRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.shutdown().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void generatedHistoriesMatchTheSerializedReferenceModelAndDrainAtShutdown() throws Exception {
        String configured = System.getProperty("qraft.model.seed");
        long[] seeds = configured == null
                ? REGRESSION_SEEDS
                : new long[]{Long.decode(configured)};
        for (long seed : seeds) {
            try {
                verifyHistory(seed);
            } catch (Throwable error) {
                throw new AssertionError(
                        "Reproduce with -Dqraft.model.seed=" + seed, error);
            }
        }
    }

    private void verifyHistory(long seed) throws Exception {
        Random random = new Random(seed);
        List<Operation> operations = generatedOperations(random, 64);
        List<ModelState> expectedBefore = new ArrayList<>();
        List<ModelState> expectedAfter = new ArrayList<>();
        ModelState reference = ModelState.initial();
        for (Operation operation : operations) {
            expectedBefore.add(reference);
            reference = applyReference(operation, reference);
            expectedAfter.add(reference);
        }

        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 128);
        AtomicReference<ModelState> actual = new AtomicReference<>(ModelState.initial());
        List<String> history = new ArrayList<>();
        List<Future<ModelState>> accepted = new ArrayList<>();

        for (int index = 0; index < operations.size(); index++) {
            int position = index;
            Operation operation = operations.get(index);
            accepted.add(sequencer.submit(
                    operation.type().name().toLowerCase() + "-" + index,
                    RaftTransitionSequencer.FailurePolicy.FENCE,
                    () -> {
                        assertSame(runtime, JavaRuntime.currentContext(), "seed=" + seed);
                        assertEquals(expectedBefore.get(position), actual.get(),
                                "stale preparation; seed=" + seed + ", operation=" + position);
                        history.add("prepare:" + position + ":" + operation.type());
                        ModelState captured = actual.get();
                        return Future.fromCompletionStage(CompletableFuture.supplyAsync(
                                () -> executeSystemOperation(operation, captured)));
                    },
                    persisted -> {
                        assertSame(runtime, JavaRuntime.currentContext(), "seed=" + seed);
                        assertEquals(expectedAfter.get(position), persisted,
                                "persistence plan diverged; seed=" + seed + ", operation=" + position);
                        actual.set(persisted);
                        history.add("apply:" + position + ":" + operation.type());
                        return persisted;
                    }));
        }

        Future<Void> drained = sequencer.drain();
        List<Future<Void>> rejected = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            rejected.add(sequencer.submit("after-shutdown-" + index, Future::succeededFuture));
        }

        for (Future<ModelState> result : accepted) await(result);
        await(drained);
        assertEquals(reference, actual.get(), "final state; seed=" + seed);
        assertEquals(expectedHistory(operations), history, "event history; seed=" + seed);
        for (Future<Void> result : rejected) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> await(result), "seed=" + seed);
            assertInstanceOf(RaftTransitionSequencer.DrainingException.class, failure.getCause(),
                    "seed=" + seed);
        }
    }

    private static List<Operation> generatedOperations(Random random, int count) {
        List<Operation> operations = new ArrayList<>(count);
        operations.add(new Operation(Type.COMMAND, 1));
        operations.add(new Operation(Type.VOTE, 1));
        operations.add(new Operation(Type.HIGHER_TERM, 1));
        operations.add(new Operation(Type.FOLLOWER_REPLACEMENT, 1));
        operations.add(new Operation(Type.SNAPSHOT, 0));
        while (operations.size() < count) {
            Type type = Type.values()[random.nextInt(Type.values().length)];
            operations.add(new Operation(type, 1 + random.nextInt(3)));
        }
        return operations;
    }

    private static List<String> expectedHistory(List<Operation> operations) {
        List<String> expected = new ArrayList<>(operations.size() * 2);
        for (int index = 0; index < operations.size(); index++) {
            expected.add("prepare:" + index + ":" + operations.get(index).type());
            expected.add("apply:" + index + ":" + operations.get(index).type());
        }
        return expected;
    }

    private static ModelState applyReference(Operation operation, ModelState state) {
        int value = operation.value();
        return switch (operation.type()) {
            case COMMAND -> new ModelState(state.term(), state.vote(),
                    state.lastIndex() + value, state.lastIndex() + value,
                    state.snapshotIndex(), state.timerGeneration());
            case VOTE -> new ModelState(state.term(), "candidate-" + value,
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    state.timerGeneration());
            case HIGHER_TERM -> new ModelState(state.term() + value, null,
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    state.timerGeneration() + 1);
            case FOLLOWER_REPLACEMENT -> {
                long retainedIndex = Math.max(state.snapshotIndex(), state.lastIndex() - value);
                yield new ModelState(state.term(), state.vote(), retainedIndex, retainedIndex,
                        state.snapshotIndex(), state.timerGeneration());
            }
            case SNAPSHOT -> new ModelState(state.term(), state.vote(),
                    state.lastIndex(), state.lastApplied(), state.lastApplied(),
                    state.timerGeneration());
            case TIMER -> new ModelState(state.term(), state.vote(),
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    state.timerGeneration() + value);
        };
    }

    private static ModelState executeSystemOperation(Operation operation, ModelState state) {
        return switch (operation.type()) {
            case COMMAND -> new ModelState(state.term(), state.vote(),
                    Math.addExact(state.lastIndex(), operation.value()),
                    Math.addExact(state.lastIndex(), operation.value()),
                    state.snapshotIndex(), state.timerGeneration());
            case VOTE -> new ModelState(state.term(), candidateId(operation.value()),
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    state.timerGeneration());
            case HIGHER_TERM -> new ModelState(
                    Math.addExact(state.term(), operation.value()), null,
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    Math.addExact(state.timerGeneration(), 1));
            case FOLLOWER_REPLACEMENT -> {
                long retainedIndex = state.lastIndex() - operation.value();
                if (retainedIndex < state.snapshotIndex()) retainedIndex = state.snapshotIndex();
                yield new ModelState(state.term(), state.vote(), retainedIndex, retainedIndex,
                        state.snapshotIndex(), state.timerGeneration());
            }
            case SNAPSHOT -> new ModelState(state.term(), state.vote(),
                    state.lastIndex(), state.lastApplied(), state.lastApplied(),
                    state.timerGeneration());
            case TIMER -> new ModelState(state.term(), state.vote(),
                    state.lastIndex(), state.lastApplied(), state.snapshotIndex(),
                    Math.addExact(state.timerGeneration(), operation.value()));
        };
    }

    private static String candidateId(int value) {
        return "candidate-" + value;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.timeout(10, TimeUnit.SECONDS).toCompletionStage().toCompletableFuture().join();
    }

    private enum Type {
        COMMAND,
        VOTE,
        HIGHER_TERM,
        FOLLOWER_REPLACEMENT,
        SNAPSHOT,
        TIMER
    }

    private record Operation(Type type, int value) {}

    private record ModelState(
            long term,
            String vote,
            long lastIndex,
            long lastApplied,
            long snapshotIndex,
            long timerGeneration) {
        static ModelState initial() {
            return new ModelState(0, null, 0, 0, 0, 0);
        }
    }
}
