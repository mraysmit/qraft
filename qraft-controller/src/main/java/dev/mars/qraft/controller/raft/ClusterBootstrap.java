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

import dev.mars.qraft.controller.raft.grpc.DescribeResponse;
import dev.mars.qraft.controller.raft.grpc.JoinRequest;
import dev.mars.qraft.controller.raft.grpc.MembershipResponse;
import dev.mars.qraft.controller.runtime.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a server with no Raft state may bootstrap a new cluster, and if so does it. One {@link #attempt}
 * asks every server this server lists to describe itself, and bootstraps only when all of these hold:
 * <ul>
 *   <li>every listed server answers;</li>
 *   <li>none of them holds Raft state, since one that does belongs to a cluster already;</li>
 *   <li>each answers under the name this server lists it by, and lists exactly the same servers.</li>
 * </ul>
 * The configuration is built from each server's own answer, its server ID and address, so every server that
 * bootstraps writes the identical entry. A server that finds an existing cluster does not bootstrap. It asks its
 * members, in turn, to add it, as Consul's {@code retry_join} does, and a leader of that cluster replicates the configuration
 * to it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public final class ClusterBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(ClusterBootstrap.class);

    /** The result of one attempt. */
    public enum Outcome {
        /** This attempt wrote the initial configuration. */
        BOOTSTRAPPED,
        /** The node already has a configuration, so there is nothing to do. */
        CONFIGURED,
        /** A listed server already belongs to a cluster; this server asked to join, and waits for its leader. */
        JOINING_EXISTING,
        /** A listed server could not be reached; another attempt may succeed. */
        WAITING,
        /** The listed servers do not all list the same servers; no attempt succeeds until they do. */
        LISTS_DISAGREE
    }

    private final RaftNode node;
    private final RaftTransport transport;

    public ClusterBootstrap(RaftNode node, RaftTransport transport) {
        this.node = Objects.requireNonNull(node, "node");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Future<Outcome> attempt() {
        if (node.getConfiguration().isPresent()) return Future.succeededFuture(Outcome.CONFIGURED);
        return node.describe().compose(self -> {
            if (self.getHasState()) return Future.succeededFuture(Outcome.CONFIGURED);
            List<String> peers = self.getListedServersList().stream()
                    .map(listed -> listed.substring(0, listed.indexOf('=')))
                    .filter(name -> !name.equals(self.getName()))
                    .toList();
            List<Future<Optional<DescribeResponse>>> answers = new ArrayList<>();
            for (String peer : peers) {
                answers.add(transport.describe(peer).map(Optional::of)
                        .recover(unreachable -> {
                            logger.debug("Bootstrap: {} did not answer: {}", peer, unreachable.getMessage());
                            return Future.succeededFuture(Optional.empty());
                        }));
            }
            return Future.all(answers).compose(ignored -> decide(self, peers, answers));
        });
    }

    /**
     * Asks the {@code members} that belong to a cluster, in turn, to add this server, as Consul's
     * {@code retry_join} tries every address. Each forwards the request to its leader. It stops at the first
     * that answers for a leader; a member that cannot be reached, or knows no leader, as a server that removed
     * itself does not, is passed over. Whatever the answer, this server waits for the leader to replicate the
     * configuration to it, and the next attempt asks again, as when an old entry for it had to be removed first.
     */
    private Future<Outcome> askToJoin(DescribeResponse self, List<String> members) {
        JoinRequest request = JoinRequest.newBuilder().setServerId(self.getServerId()).setName(self.getName())
                .setAddress(self.getAddress()).build();
        return askToJoin(request, members, 0);
    }

    private Future<Outcome> askToJoin(JoinRequest request, List<String> members, int next) {
        if (next == members.size()) return Future.succeededFuture(Outcome.JOINING_EXISTING);
        String member = members.get(next);
        Future<MembershipResponse> answer;
        try {
            answer = transport.join(member, request);
        } catch (RuntimeException unaddressable) {
            answer = Future.failedFuture(unaddressable);
        }
        return answer.map(Optional::of)
                .recover(unreachable -> {
                    logger.info("Bootstrap: could not ask {} to join its cluster: {}",
                            member, unreachable.getMessage());
                    return Future.succeededFuture(Optional.<MembershipResponse>empty());
                })
                .compose(response -> {
                    if (response.isEmpty()) return askToJoin(request, members, next + 1);
                    MembershipResponse.Status status = response.get().getStatus();
                    if (status == MembershipResponse.Status.NO_LEADER
                            || status == MembershipResponse.Status.UNSPECIFIED) {
                        logger.info("Bootstrap: {} could not take the request to join: {}",
                                member, response.get().getMessage());
                        return askToJoin(request, members, next + 1);
                    }
                    logger.info("Bootstrap: asked {} to join its cluster: {} {}",
                            member, status, response.get().getMessage());
                    return Future.succeededFuture(Outcome.JOINING_EXISTING);
                });
    }

    private Future<Outcome> decide(
            DescribeResponse self, List<String> peers, List<Future<Optional<DescribeResponse>>> answers) {
        List<String> members = new ArrayList<>();
        for (int i = 0; i < peers.size(); i++) {
            Optional<DescribeResponse> answer = answers.get(i).result();
            if (answer.isPresent() && answer.get().getHasState()) members.add(peers.get(i));
        }
        if (!members.isEmpty()) return askToJoin(self, members);

        List<DescribeResponse> described = new ArrayList<>();
        described.add(self);
        boolean everyoneAnswered = true;
        for (int i = 0; i < peers.size(); i++) {
            Optional<DescribeResponse> answer = answers.get(i).result();
            if (answer.isEmpty()) {
                everyoneAnswered = false;
                continue;
            }
            DescribeResponse peer = answer.get();
            if (!peer.getName().equals(peers.get(i)) || !peer.getListedServersList().equals(self.getListedServersList())) {
                logger.error("Bootstrap refused: {} answers as {} and lists {}, but this server lists {}",
                        peers.get(i), peer.getName(), peer.getListedServersList(), self.getListedServersList());
                return Future.succeededFuture(Outcome.LISTS_DISAGREE);
            }
            described.add(peer);
        }
        if (!everyoneAnswered) return Future.succeededFuture(Outcome.WAITING);

        RaftConfiguration configuration = new RaftConfiguration(described.stream()
                .map(server -> new RaftConfiguration.Server(
                        server.getServerId(), server.getName(), server.getAddress(), true))
                .toList());
        return node.bootstrap(configuration)
                .map(ignored -> Outcome.BOOTSTRAPPED)
                .recover(error -> node.getConfiguration().isPresent()
                        // A leader replicated to this server while it was deciding.
                        ? Future.succeededFuture(Outcome.CONFIGURED)
                        : Future.failedFuture(error));
    }
}
