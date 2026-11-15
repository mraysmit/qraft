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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a forwarded write in flight (Jenkins build 80): it is either sent or withdrawn, never both, so a follower that
 * stops following its leader while the write still waits for a connection knows it was not applied.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
class ForwardCallTest {

    @Test
    void aCallMarkedSentCannotBeWithdrawn() {
        ForwardCall call = new ForwardCall();

        assertTrue(call.markSent());
        assertFalse(call.withdraw(), "a write that was sent may have been applied");
        assertFalse(call.isDone(), "its answer is still to come");
    }

    @Test
    void aWithdrawnCallIsNeverSentAndFailsAsNotSent() {
        ForwardCall call = new ForwardCall();

        assertTrue(call.withdraw());
        assertFalse(call.markSent(), "a withdrawn write is never sent");
        assertTrue(call.withdraw(), "withdrawing it again is the same");
        ExecutionException failed = assertThrows(ExecutionException.class, () -> call.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ForwardNotSentException.class, failed.getCause());
    }

    @Test
    void exactlyOneOfASendAndAWithdrawalMadeAtOnceWins() throws Exception {
        for (int attempt = 0; attempt < 500; attempt++) {
            ForwardCall call = new ForwardCall();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean sent = new AtomicBoolean();
            Thread sender = Thread.ofVirtual().start(() -> {
                await(start);
                sent.set(call.markSent());
            });
            start.countDown();
            boolean withdrawn = call.withdraw();
            sender.join(TimeUnit.SECONDS.toMillis(10));
            assertEquals(1, (sent.get() ? 1 : 0) + (withdrawn ? 1 : 0), "attempt " + attempt);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
