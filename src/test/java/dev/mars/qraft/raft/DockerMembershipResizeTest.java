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

import dev.mars.qraft.testing.fault.ExpectedDockerErrorsHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.ComposeContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static dev.mars.qraft.raft.DockerClusterApiHelper.cleanUp;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configuration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.configurationOrEmpty;
import static dev.mars.qraft.raft.DockerClusterApiHelper.everyNodeHas;
import static dev.mars.qraft.raft.DockerClusterApiHelper.exactlyOneLeader;
import static dev.mars.qraft.raft.DockerClusterApiHelper.joinerConfiguration;
import static dev.mars.qraft.raft.DockerClusterApiHelper.logsOf;
import static dev.mars.qraft.raft.DockerClusterApiHelper.register;
import static dev.mars.qraft.raft.DockerClusterApiHelper.removePeer;
import static dev.mars.qraft.raft.DockerClusterApiHelper.status;
import static dev.mars.qraft.raft.DockerClusterApiHelper.statusOrNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Growing a real three-server cluster to five and shrinking it back to three, in containers (membership Step 8),
 * while a client keeps registering services throughout:
 * <ol>
 *   <li>server4 starts empty and lists the cluster, so it asks to join. It first appears in the configuration as a
 *       non-voter, and the leader promotes it to a voter once it has caught up; server5 then does the same;</li>
 *   <li>with five voters, every registration acknowledged so far is on all five servers;</li>
 *   <li>server5, then server4, are removed with {@code operator raft remove-peer} and the cluster is again the
 *       original three voters, with one leader and an unchanged term although the removed servers keep
 *       campaigning;</li>
 *   <li>the client never loses an acknowledged registration, and every retained server converges on all of them.</li>
 * </ol>
 * The two added servers run as detached containers on the cluster's network, each on its own volume.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
@Tag("docker")
@ExpectedDockerErrorsHelper({
        // A server removed from the cluster keeps campaigning with its old configuration, and its vote requests fail
        // against the removed server beside it once that one is stopped at the end of the test.
        dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE
})
// Each wait is bounded; their worst-case sum is about fourteen minutes, so the method budget is twenty-five and a
// failure reports the condition that was not met rather than the module's default method timeout.
@Timeout(value = 25, unit = TimeUnit.MINUTES)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("shared-docker-clusters")
class DockerMembershipResizeTest {
    private static final String OPERATOR_TOKEN = "acceptance-operator-token";

    @Test
    void aClusterGrowsToFiveAndShrinksToThreeWhileAClientPublishes() throws Exception {
        ComposeContainer cluster = SharedDockerClusterFixture.startIsolatedThreeNodeCluster();
        String volume4 = SharedDockerClusterFixture.createVolume("resize4");
        String volume5 = SharedDockerClusterFixture.createVolume("resize5");
        Path tokenFile = Files.createTempFile("qraft-operator-token-", ".txt");
        Path secondTokenFile = Files.createTempFile("qraft-operator-token-", ".txt");
        List<SharedDockerClusterFixture.DetachedServer> added = new ArrayList<>();
        List<String> endpoints = SharedDockerClusterFixture.getNodeEndpoints(cluster, 3);
        Publisher publisher = new Publisher(endpoints);
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> exactlyOneLeader(endpoints));
            // A follower answers "not configured" until the leader has replicated the configuration to it.
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).size() == 3));
            Set<String> original = Set.copyOf(configuration(endpoints.get(0)).keySet());
            assertEquals(3, original.size());
            publisher.start();
            await().atMost(Duration.ofSeconds(30)).until(() -> publisher.acknowledged().size() >= 3);

            // Growth: both servers start together, so the leader meets the second join and promotion while the
            // first change may be uncommitted, and takes them one at a time. Each joins as a non-voter and is
            // promoted once it has caught up.
            SharedDockerClusterFixture.DetachedServer server4 = SharedDockerClusterFixture.startDetachedServer(
                    cluster, "server4", joinerConfiguration(4), "-v", volume4 + ":/app/data");
            added.add(server4);
            SharedDockerClusterFixture.DetachedServer server5 = SharedDockerClusterFixture.startDetachedServer(
                    cluster, "server5", joinerConfiguration(5), "-v", volume5 + ":/app/data");
            added.add(server5);
            List<String> joinedIds = joinedAsNonVoters(endpoints.get(0), List.of(server4, server5));
            String id4 = joinedIds.get(0);
            String id5 = joinedIds.get(1);

            List<String> everyServer = List.of(endpoints.get(0), endpoints.get(1), endpoints.get(2),
                    server4.endpoint(), server5.endpoint());
            Map<String, Boolean> five = configuration(endpoints.get(0));
            assertEquals(5, five.size(), five.toString());
            assertTrue(five.values().stream().allMatch(Boolean::booleanValue), "all five vote: " + five);
            // The leader logs a promotion when its commit completes, a moment after the configuration shows it.
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                String leaderLogs = logsOf(cluster, "server1") + logsOf(cluster, "server2")
                        + logsOf(cluster, "server3");
                return leaderLogs.contains("Promoted server4 to a voter")
                        && leaderLogs.contains("Promoted server5 to a voter");
            });

            List<String> acknowledgedBeforeShrink = publisher.acknowledged();
            await().atMost(Duration.ofSeconds(90)).until(() -> allPresent(everyServer, acknowledgedBeforeShrink));

            // Shrinkage: both removals are issued at once, each through a server that stays. The leader takes
            // them one at a time: a request that meets the other's uncommitted change is refused as "already in
            // progress" and repeated, and neither removal may undo the other.
            CompletableFuture<Integer> removeServer5 = CompletableFuture.supplyAsync(() ->
                    removeUntilDone(cluster, "server5", "server1", tokenFile));
            CompletableFuture<Integer> removeServer4 = CompletableFuture.supplyAsync(() ->
                    removeUntilDone(cluster, "server4", "server2", secondTokenFile));
            removeServer5.get(3, TimeUnit.MINUTES);
            removeServer4.get(3, TimeUnit.MINUTES);
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                Map<String, Boolean> now = configurationOrEmpty(endpoints.get(0));
                return !now.containsKey(id4) && !now.containsKey(id5);
            });
            Map<String, Boolean> three = configuration(endpoints.get(0));
            assertEquals(original, three.keySet(), "the original three servers remain");
            assertTrue(three.values().stream().allMatch(Boolean::booleanValue), "all three vote: " + three);

            // The removed servers keep their old configuration and go on campaigning, as the design expects; the
            // three that remain keep one leader and its term, and the client is not disturbed.
            // Neither removal was undone by the other: the configuration stays the original three.
            long term = status(endpoints.get(0)).path("term").asLong();
            await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(30)).until(() ->
                    exactlyOneLeader(endpoints) && term == status(endpoints.get(0)).path("term").asLong()
                            && configurationOrEmpty(endpoints.get(0)).keySet().equals(original));

            // The client published throughout; none of what it was told succeeded is lost, anywhere that remains.
            publisher.stop();
            assertNull(publisher.failure(), "the client was always able to register: " + publisher.failure());
            List<String> acknowledged = publisher.acknowledged();
            assertTrue(acknowledged.size() > acknowledgedBeforeShrink.size(),
                    "the client kept publishing through the shrink");
            await().atMost(Duration.ofSeconds(90)).until(() -> allPresent(endpoints, acknowledged));
            await().atMost(Duration.ofSeconds(30)).until(() -> endpoints.stream()
                    .allMatch(endpoint -> configurationOrEmpty(endpoint).keySet().equals(original)));
            assertFalse(status(endpoints.get(2)).path("fenced").asBoolean());
        } finally {
            publisher.stop();
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(secondTokenFile);
            cleanUp(cluster, added, volume4, volume5);
        }
    }

    /**
     * Waits for each joined server's own ID to appear in the configuration, requires that it first appears without a
     * vote, then waits for the leader to promote it. Returns the server IDs in the order of {@code servers}.
     */
    private static List<String> joinedAsNonVoters(String observer,
                                                 List<SharedDockerClusterFixture.DetachedServer> servers)
            throws Exception {
        List<String> ids = new ArrayList<>();
        for (SharedDockerClusterFixture.DetachedServer server : servers) {
            await().atMost(Duration.ofSeconds(60)).until(() -> statusOrNull(server.endpoint(), "serverId") != null);
            ids.add(status(server.endpoint()).path("serverId").asText());
        }
        Map<String, Boolean> firstSeen = new ConcurrentHashMap<>();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> {
            Map<String, Boolean> now = configurationOrEmpty(observer);
            for (String id : ids) {
                if (now.containsKey(id)) firstSeen.putIfAbsent(id, now.get(id));
            }
            return firstSeen.size() == ids.size();
        });
        for (String id : ids) {
            assertFalse(firstSeen.get(id), "a joining server is first a non-voter: " + id);
        }
        await().atMost(Duration.ofSeconds(180)).until(() -> {
            Map<String, Boolean> now = configurationOrEmpty(observer);
            return ids.stream().allMatch(id -> Boolean.TRUE.equals(now.get(id)));
        });
        return ids;
    }

    /**
     * Removes {@code name} through {@code via} until the command succeeds, repeating a removal the leader refused
     * because the other server's change was uncommitted. Any other refusal fails. Returns the number of attempts.
     */
    private static int removeUntilDone(ComposeContainer cluster, String name, String via, Path tokenFile) {
        for (int attempt = 1; attempt <= 15; attempt++) {
            try {
                SharedDockerClusterFixture.DockerCommandResult result = removePeer(cluster, name, via, tokenFile,
                        OPERATOR_TOKEN);
                assertTrue(result.exited(), result.output());
                if (result.exitCode() == 0) return attempt;
                assertTrue(result.output().contains("already in progress"),
                        "the only reason to refuse a removal here is the other change: " + result.output());
                Thread.sleep(1000);
            } catch (Exception failed) {
                throw new IllegalStateException("Could not remove " + name, failed);
            }
        }
        throw new AssertionError("the removal of " + name + " was refused 15 times");
    }

    private static boolean allPresent(List<String> servers, List<String> names) {
        return names.stream().allMatch(name -> everyNodeHas(servers, name));
    }

    /** A client that registers one new service at a time, and records those the cluster acknowledged. */
    private static final class Publisher {
        private final List<String> endpoints;
        private final List<String> acknowledged = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        Publisher(List<String> endpoints) {
            this.endpoints = endpoints;
        }

        void start() {
            executor.submit(() -> {
                int number = 0;
                while (running.get()) {
                    String name = "published-" + number++;
                    try {
                        register(endpoints, name);
                        acknowledged.add(name);
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception | AssertionError unregistered) {
                        failure.compareAndSet(null, unregistered);
                        return;
                    }
                }
            });
        }

        /** Stops after the registration in progress and waits for the client to finish. */
        void stop() throws InterruptedException {
            running.set(false);
            executor.shutdown();
            executor.awaitTermination(60, TimeUnit.SECONDS);
        }

        List<String> acknowledged() {
            return List.copyOf(acknowledged);
        }

        Throwable failure() {
            return failure.get();
        }
    }
}
