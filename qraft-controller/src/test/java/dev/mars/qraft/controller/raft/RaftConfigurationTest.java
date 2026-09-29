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

import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RaftConfiguration}, the set of servers a Raft cluster replicates to: it keeps its servers in
 * name order, so every server that builds the same set holds an equal configuration; it refuses a set with no
 * voter, a repeated ID or name, or a blank field; a quorum is a majority of its voters alone; and it finds a
 * server by ID or name.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class RaftConfigurationTest {
    private static final Server A = new Server("id-a", "a", "a:9080", true);
    private static final Server B = new Server("id-b", "b", "b:9080", true);
    private static final Server C = new Server("id-c", "c", "c:9080", true);
    private static final Server LEARNER = new Server("id-d", "d", "d:9080", false);

    @Test
    void serversAreKeptInNameOrderSoTheSameSetIsTheSameConfiguration() {
        RaftConfiguration shuffled = new RaftConfiguration(List.of(C, A, B));

        assertEquals(List.of(A, B, C), shuffled.servers());
        assertEquals(new RaftConfiguration(List.of(A, B, C)), shuffled);
    }

    @Test
    void aQuorumIsAMajorityOfTheVotersAlone() {
        RaftConfiguration three = new RaftConfiguration(List.of(A, B, C, LEARNER));

        assertEquals(2, three.quorum());
        assertEquals(Set.of("id-a", "id-b", "id-c"), three.voterIds());
        assertTrue(three.hasQuorum(Set.of("id-a", "id-c")));
        assertFalse(three.hasQuorum(Set.of("id-a", "id-d")), "a non-voter does not count");
        assertFalse(three.hasQuorum(Set.of("id-a", "stranger")), "an ID outside the configuration does not count");
        assertEquals(1, new RaftConfiguration(List.of(A)).quorum());
        assertEquals(2, new RaftConfiguration(List.of(A, B)).quorum(), "two voters need both");
        assertEquals(3, new RaftConfiguration(List.of(A, B, C, new Server("id-e", "e", "e:9080", true))).quorum());
    }

    @Test
    void aServerIsFoundByIdOrByName() {
        RaftConfiguration configuration = new RaftConfiguration(List.of(A, B, LEARNER));

        assertEquals(Optional.of(B), configuration.server("id-b"));
        assertEquals(Optional.of(B), configuration.serverNamed("b"));
        assertEquals(Optional.empty(), configuration.server("id-c"));
        assertEquals(Optional.empty(), configuration.serverNamed("c"));
        assertTrue(configuration.isVoter("id-a"));
        assertFalse(configuration.isVoter("id-d"), "a non-voter is a member but not a voter");
        assertFalse(configuration.isVoter("id-c"));
    }

    @Test
    void anInvalidSetOfServersIsRefusedWithTheReason() {
        assertRefused(List.of(), "a configuration needs at least one voter");
        assertRefused(List.of(LEARNER), "a configuration needs at least one voter");
        assertRefused(List.of(A, new Server("id-a", "other", "o:9080", true)), "server ID id-a appears twice");
        assertRefused(List.of(A, new Server("id-x", "a", "x:9080", true)), "server name a appears twice");
        for (Server blank : List.of(new Server(" ", "x", "x:1", true), new Server("id", "", "x:1", true),
                new Server("id", "x", " ", true))) {
            assertRefused(List.of(A, blank), "a server needs an ID, a name, and an address");
        }
    }

    private static void assertRefused(List<Server> servers, String reason) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new RaftConfiguration(servers));
        assertEquals(reason, refused.getMessage());
    }
}
