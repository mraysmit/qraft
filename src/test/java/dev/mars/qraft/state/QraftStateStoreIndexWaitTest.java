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

package dev.mars.qraft.state;

import dev.mars.qraft.state.distributed.DistributedStateCommand;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests waiting on the state store's indexes (consistency list, Phase 2), which blocking queries use: a wait answers
 * at once when the index has passed the one given, wakes when an applied entry passes it, and answers with the index
 * as it stands at its deadline; the key/value index wakes only on a key/value change.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreIndexWaitTest {
    private static void applyAt(QraftStateStore store, long index, dev.mars.qraft.raft.RaftCommand command) {
        store.apply(command);
        store.setLastAppliedIndex(index);
    }

    @Test
    void aWaitOnAnIndexAlreadyPassedAnswersAtOnce() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(7);

        long started = System.nanoTime();
        assertEquals(7, store.awaitAppliedIndex(6, Duration.ofSeconds(10)));
        assertEquals(7, store.awaitAppliedIndex(0, Duration.ofSeconds(10)), "an index of 0 answers at once");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void aWaitWakesWhenAnAppliedEntryPassesItsIndex() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(3);
        CompletableFuture<Long> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return store.awaitAppliedIndex(3, Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(100);
        assertFalse(waiting.isDone(), "nothing has passed index 3");

        applyAt(store, 4, new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));

        assertEquals(4, waiting.get(10, TimeUnit.SECONDS));
    }

    @Test
    void aWaitAnswersWithTheIndexAsItStandsAtItsDeadline() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(5);

        long started = System.nanoTime();
        assertEquals(5, store.awaitAppliedIndex(5, Duration.ofMillis(200)));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(200), "it waited its deadline");
    }

    @Test
    void theKeyValueIndexWakesOnlyOnAKeyValueChange() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.setLastAppliedIndex(1);
        applyAt(store, 2, KvCommand.set(KvScope.DEFAULT, "a", new byte[] {1}, 0, null));
        CompletableFuture<Long> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return store.awaitKvIndex(2, Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        applyAt(store, 3, new DistributedStateRaftCommand(DistributedStateCommand.put("k", "v")));
        Thread.sleep(100);
        assertFalse(waiting.isDone(), "an entry that is not key/value leaves the key/value index where it was");

        applyAt(store, 4, KvCommand.set(KvScope.DEFAULT, "a", new byte[] {2}, 0, null));
        assertEquals(4, waiting.get(10, TimeUnit.SECONDS));
    }
}
