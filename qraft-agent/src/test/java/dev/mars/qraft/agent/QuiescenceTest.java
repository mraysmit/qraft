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

package dev.mars.qraft.agent;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link Quiescence} completes only once every owned executor and HTTP client has actually
 * terminated, so a task that is mid-run at shutdown cannot outlive a completed shutdown, and that the
 * wait is bounded when a task never exits.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class QuiescenceTest {

    @Test
    void completesOnlyAfterATaskThatWasRunningAtShutdownHasExited() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> holdIgnoringInterrupts(running, release));
        assertTrue(running.await(10, TimeUnit.SECONDS));

        CompletableFuture<Boolean> quiescent = Quiescence.shutdownNowAndAwait(
                List.of(executor), List.of(), Duration.ofSeconds(30));

        assertFalse(quiescent.isDone(), "a task still running means the executor has not terminated");
        release.countDown();
        assertTrue(quiescent.get(10, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
    }

    @Test
    void theWaitIsBoundedWhenATaskNeverExits() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> holdIgnoringInterrupts(running, release));
        assertTrue(running.await(10, TimeUnit.SECONDS));
        try {
            assertEquals(false, Quiescence.shutdownNowAndAwait(List.of(executor), List.of(), Duration.ofMillis(100))
                    .get(10, TimeUnit.SECONDS), "an executor that cannot terminate is reported, not waited on forever");
        } finally {
            release.countDown();
        }
    }

    @Test
    void httpClientsAreShutDownAndAwaited() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        assertTrue(Quiescence.shutdownNowAndAwait(List.of(), List.of(client), Duration.ofSeconds(30))
                .get(10, TimeUnit.SECONDS));
        assertTrue(client.isTerminated());
    }

    /** A task that ignores interruption until released, as a task mid-way through its work can. */
    private static void holdIgnoringInterrupts(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        boolean interrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
