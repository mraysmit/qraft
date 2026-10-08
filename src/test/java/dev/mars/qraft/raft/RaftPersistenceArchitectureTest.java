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

package dev.mars.qraft.raft;

import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.qraft.raft.RaftAwaitHelper.await;
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
class RaftPersistenceArchitectureTest {
    private JavaRuntime runtime;
    private TestRaftStorageFixture storage;
    private RaftPersistence persistence;

    @BeforeEach
    void setUp() {
        runtime = JavaRuntime.create();
        storage = new TestRaftStorageFixture();
        storage.open(null).join();
        persistence = new RaftPersistence(Optional.of(storage), Optional.of(storage));
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        runtime.shutdown().toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
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

}
