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
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that {@link RaftPersistence} accepts WAL mutations only with the ownership capability of an
 * active {@link RaftTransitionSequencer} transition, which expires on completion.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-15
 * @version 1.0
 */
@RemediationTest(phase = "6-architecture", scenarioPrefix = "RAFT-PERSISTENCE-ARCH")
class RaftPersistenceArchitectureTest {
    private JavaRuntime runtime;
    private TestRaftStorage storage;
    private RaftPersistence persistence;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new TestRaftStorage();
        storage.open(null).join();
        persistence = new RaftPersistence(Optional.of(storage), Optional.of(storage));
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        runtime.shutdown().toCompletionStage().toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void activeTransitionCapabilityAuthorizesWalMutation() {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);

        Future<Void> result = sequencer.submit("append", () -> {
            RaftTransitionSequencer.Ownership ownership = sequencer.currentOwnership();
            return Future.fromCompletionStage(persistence.appendEntries(
                    ownership, List.of(new LogEntryData(1, 1, new byte[]{1}))));
        });

        await(result);
        assertEquals(1, storage.replayLog().join().size());
    }

    @Test
    void capabilityExpiresWhenItsTransitionCompletes() {
        RaftTransitionSequencer sequencer = new RaftTransitionSequencer(runtime, 4);
        AtomicReference<RaftTransitionSequencer.Ownership> captured = new AtomicReference<>();

        await(sequencer.submit("capture", () -> {
            captured.set(sequencer.currentOwnership());
            return Future.succeededFuture();
        }));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> persistence.updateMetadata(captured.get(), 2, Optional.empty()));
        assertEquals("Raft transition ownership has expired", error.getMessage());
    }

    private static <T> T await(Future<T> future) {
        return future.timeout(5, TimeUnit.SECONDS)
                .toCompletionStage().toCompletableFuture().join();
    }
}
