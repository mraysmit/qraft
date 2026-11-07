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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests the agreement check that Docker tests wait on between faults: one leader, named by every server in the
 * leader's term. A restarted server can report from its recovered state before it has heard the current term, so a
 * wait that only finds a leader can let the next fault begin while that server is still behind.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class DockerHealthApiHelperTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void oneLeaderNamedByEveryServerInItsTermIsAgreed() {
        assertEquals("n3", DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, "n3"),
                status("FOLLOWER", "n2", 2, "n3"),
                status("LEADER", "n3", 2, "n3"))));
    }

    @Test
    void aServerStillInAnEarlierTermIsNotAgreement() {
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, "n3"),
                status("FOLLOWER", "n2", 1, "n3"),
                status("LEADER", "n3", 2, "n3"))));
    }

    @Test
    void aServerNamingNoLeaderOrAnotherIsNotAgreement() {
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, "n3"),
                status("FOLLOWER", "n2", 2, ""),
                status("LEADER", "n3", 2, "n3"))));
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, "n3"),
                status("FOLLOWER", "n2", 2, "n1"),
                status("LEADER", "n3", 2, "n3"))));
    }

    @Test
    void noLeaderOrTwoLeadersIsNotAgreement() {
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, ""),
                status("FOLLOWER", "n2", 2, ""))));
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("LEADER", "n1", 2, "n1"),
                status("LEADER", "n2", 3, "n2"))));
    }

    @Test
    void aServerThatDidNotAnswerIsNotAgreement() {
        assertNull(DockerHealthApiHelper.agreedLeader(List.of(
                status("FOLLOWER", "n1", 2, "n3"),
                MissingNode.getInstance(),
                status("LEADER", "n3", 2, "n3"))));
    }

    private static JsonNode status(String state, String nodeId, long term, String leaderId) {
        return JSON.createObjectNode()
                .put("state", state)
                .put("nodeId", nodeId)
                .put("term", term)
                .put("leaderId", leaderId);
    }
}
