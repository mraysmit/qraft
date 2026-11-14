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

import java.util.List;

/**
 * The membership as one server sees it, captured on its state loop (UI-3 task list, decision 5). Only the leader
 * knows each server's progress: on the leader every member carries it and the quorum is given; on any other server
 * the progress fields and the quorum are null, and the view says it is a follower's.
 *
 * @param leaderView   whether the answering server leads, so the progress is known
 * @param answeredBy   the answering server's name
 * @param leaderId     the leader the answering server knows, or null
 * @param term         the answering server's term
 * @param commitIndex  its commit index
 * @param lastLogIndex its last log index
 * @param servers      each configured server, in name order
 * @param quorum       the quorum as the leader sees it, or null on a follower
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
public record RaftMembership(boolean leaderView, String answeredBy, String leaderId, long term, long commitIndex,
                             long lastLogIndex, List<Member> servers, Quorum quorum) {

    public RaftMembership {
        servers = List.copyOf(servers);
    }

    /**
     * One configured server. The progress fields are null where the answering server does not lead.
     *
     * @param matchIndex        the highest index the leader knows the server holds
     * @param nextIndex         the next index the leader will send it
     * @param lag               the leader's last log index less the match index
     * @param lastContactMillis how long since the server last answered the leader
     * @param reachable         whether it answered within an election timeout of heartbeat rounds, the leader's
     *                          check-quorum test (decision 7)
     * @param healthy           reachable and within {@link ClusterHealth#MAX_TRAILING_LOGS} of the leader's log
     */
    public record Member(String serverId, String name, String address, boolean voter, boolean leader, boolean self,
                         Long matchIndex, Long nextIndex, Long lag, Long lastContactMillis, Boolean reachable,
                         Boolean healthy) {
    }

    /**
     * The quorum as the leader sees it.
     *
     * @param voters           the voting servers
     * @param quorumSize       how many voters a quorum takes
     * @param reachableVoters  the voters the leader reaches, itself included
     * @param failureTolerance how many reachable voters could be lost with a quorum still reached
     * @param healthy          whether every server is healthy
     */
    public record Quorum(int voters, int quorumSize, int reachableVoters, int failureTolerance, boolean healthy) {
    }
}
