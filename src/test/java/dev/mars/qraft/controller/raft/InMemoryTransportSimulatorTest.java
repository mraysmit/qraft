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

import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.TRANSPORT_HANDLER_FAILURE;

/**
 * Tests the in-memory network fake the Raft tests rely on. A crashed node receives nothing. Every request
 * ends in a response or a failure: a delayed delivery that throws fails its request, and stopping a
 * transport fails the requests it still holds, so no caller waits forever. Its chaos comes from a seed,
 * so the same seed repeats the same decisions for the same sequence of requests.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class InMemoryTransportSimulatorTest {
    private final List<InMemoryTransportSimulatorFixture> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        transports.forEach(InMemoryTransportSimulatorFixture::stop);
        InMemoryTransportSimulatorFixture.clearAllTransports();
    }

    @Test
    void aCrashedNodeReceivesNothing() {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        InMemoryTransportSimulatorFixture crashed = start("crashed", 1);
        crashed.setFailureMode(InMemoryTransportSimulatorFixture.FailureMode.CRASH);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> vote(sender, "crashed").get(10, TimeUnit.SECONDS));

        assertTrue(failure.getCause().getMessage().contains("crashed"), failure.getCause().toString());
    }

    @Test
    void aDelayedDeliveryThatThrowsFailsItsRequest() throws Exception {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        InMemoryTransportSimulatorFixture target = new InMemoryTransportSimulatorFixture("target", 1);
        transports.add(target);
        target.start(message -> { throw new InjectedFaultFixture(TRANSPORT_HANDLER_FAILURE, "handler failed"); });
        sender.setReorderingConfig(true, 1.0, 1);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> vote(sender, "target").get(10, TimeUnit.SECONDS));

        assertEquals("handler failed", failure.getCause().getMessage());
    }

    @Test
    void stoppingATransportFailsTheRequestsItStillHolds() throws Exception {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        start("target", 1);
        sender.setReorderingConfig(true, 1.0, 600_000);
        CompletableFuture<VoteResponse> held = vote(sender, "target");
        awaitQueued(sender);

        sender.stop();

        assertTrue(held.isCompletedExceptionally(), "a request still queued when its transport stops fails");
    }

    @Test
    void simulatedLatencyHoldsTheRequestInsteadOfOccupyingAThread() {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        start("target", 1);
        sender.setChaosConfig(60_000, 60_000, 0);

        CompletableFuture<VoteResponse> inTransit = vote(sender, "target");

        awaitQueued(sender);
        sender.stop();
        assertTrue(inTransit.isCompletedExceptionally(), "a request still in transit when its transport stops fails");
    }

    @Test
    void aThrottledRequestIsHeldAndThenDelivered() throws Exception {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        start("target", 1);
        sender.setThrottlingConfig(true, 1);

        CompletableFuture<VoteResponse> throttled = vote(sender, "target");

        awaitQueued(sender);
        assertEquals(1, throttled.get(10, TimeUnit.SECONDS).getTerm(), "the throttled request is delivered");
    }

    @Test
    void everyRequestInTransitWhenATransportStopsFails() throws Exception {
        InMemoryTransportSimulatorFixture sender = start("sender", 1);
        start("target", 1);
        sender.setChaosConfig(60_000, 60_000, 0);
        List<CompletableFuture<VoteResponse>> inTransit = new ArrayList<>();
        for (int request = 0; request < 50; request++) inTransit.add(vote(sender, "target"));

        sender.stop();

        CompletableFuture<Void> all = CompletableFuture.allOf(inTransit.toArray(CompletableFuture[]::new));
        assertThrows(ExecutionException.class, () -> all.get(10, TimeUnit.SECONDS),
                "every request ends, and in failure");
        assertTrue(inTransit.stream().allMatch(CompletableFuture::isCompletedExceptionally));
        awaitNoThreads(sender);
    }

    @Test
    void aTransportThatIsNeverStartedHoldsNoThread() {
        InMemoryTransportSimulatorFixture unstarted = new InMemoryTransportSimulatorFixture("never-started", 1);

        assertEquals(List.of(), threadsOf(unstarted), "a transport a node never starts must not leak a thread");
    }

    @Test
    void theSameSeedRepeatsTheSameChaos() throws Exception {
        assertEquals(dropPattern(42), dropPattern(42));
        assertTrue(dropPattern(42).contains(true) && dropPattern(42).contains(false),
                "a drop rate of one half both drops and delivers within twenty requests");
    }

    private List<Boolean> dropPattern(long seed) throws Exception {
        InMemoryTransportSimulatorFixture.clearAllTransports();
        InMemoryTransportSimulatorFixture sender = start("sender-" + seed, seed);
        start("target", seed);
        sender.setChaosConfig(0, 0, 0.5);
        List<Boolean> dropped = new ArrayList<>();
        for (int request = 0; request < 20; request++) {
            try {
                vote(sender, "target").get(10, TimeUnit.SECONDS);
                dropped.add(false);
            } catch (ExecutionException failure) {
                dropped.add(failure.getCause().getMessage().contains("dropped"));
            }
        }
        sender.stop();
        transports.remove(sender);
        return dropped;
    }

    private InMemoryTransportSimulatorFixture start(String nodeId, long seed) {
        InMemoryTransportSimulatorFixture transport = new InMemoryTransportSimulatorFixture(nodeId, seed);
        transport.start(message -> { });
        transports.add(transport);
        return transport;
    }

    private static CompletableFuture<VoteResponse> vote(InMemoryTransportSimulatorFixture sender, String target) {
        return sender.sendVoteRequest(target, VoteRequest.newBuilder()
                        .setTerm(1).setCandidateId("sender").setLastLogIndex(0).setLastLogTerm(0).build())
                .toCompletionStage().toCompletableFuture();
    }

    /** A stopped transport's threads exit; the bound only diagnoses one that never does. */
    private static void awaitNoThreads(InMemoryTransportSimulatorFixture transport) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!threadsOf(transport).isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(List.of(), threadsOf(transport), "a stopped transport leaves no thread behind");
    }

    private static List<String> threadsOf(InMemoryTransportSimulatorFixture transport) {
        return Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                .filter(name -> name.startsWith(transport.threadPrefix())).toList();
    }

    private static void awaitQueued(InMemoryTransportSimulatorFixture transport) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (transport.queuedMessages() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(1, transport.queuedMessages(), "the request is held for delayed delivery");
    }
}
