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

import dev.mars.qraft.controller.raft.grpc.JoinRequest;
import dev.mars.qraft.controller.raft.grpc.MembershipResponse;
import dev.mars.qraft.controller.raft.grpc.MembershipResponse.Status;
import dev.mars.qraft.controller.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.controller.runtime.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Serves joins and removals on every server, as Consul's servers serve {@code retry_join} and
 * {@code operator raft remove-peer}:
 * <ul>
 *   <li>the leader makes the change through {@link RaftNode#admit} or {@link RaftNode#removeServer};</li>
 *   <li>any other server forwards the request to the leader it knows over the Raft port, marked as forwarded,
 *       and returns the leader's answer. A forwarded request is never forwarded again, so two servers that
 *       each think the other leads cannot pass it back and forth;</li>
 *   <li>a removal needs the operator token. The server that receives it checks the token before forwarding,
 *       and the leader checks it again, so the Raft port does not bypass it. With no token configured, every
 *       removal is refused. Joining needs no token, as joining gives a server no vote until the leader
 *       promotes it. A join that collides with another server ID is refused until an operator removes it.</li>
 * </ul>
 * The node's refusals, such as the quorum rule, come back as {@link Status#REFUSED} with the node's reason.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public final class MembershipService {
    private static final Logger logger = LoggerFactory.getLogger(MembershipService.class);
    /** How long a change waits to commit, or a forwarded request for the leader's answer. */
    public static final long TIMEOUT_SECONDS = 10;

    private final RaftNode node;
    private final RaftTransport forwarder;
    private final byte[] operatorToken;

    /**
     * @param forwarder the transport that carries a request to the leader
     * @param operatorToken the token a removal must carry; null or blank refuses every removal
     */
    public MembershipService(RaftNode node, RaftTransport forwarder, String operatorToken) {
        this.node = Objects.requireNonNull(node, "node");
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
        this.operatorToken = operatorToken == null || operatorToken.isBlank()
                ? null : operatorToken.getBytes(StandardCharsets.UTF_8);
    }

    /** Admits {@code request}'s server as a non-voter on the leader, or forwards the request to it. */
    public Future<MembershipResponse> join(JoinRequest request) {
        Objects.requireNonNull(request, "request");
        RaftConfiguration.Server joining;
        try {
            joining = new RaftConfiguration.Server(
                    request.getServerId(), request.getName(), request.getAddress(), false);
        } catch (IllegalArgumentException invalid) {
            return Future.succeededFuture(response(Status.REFUSED, invalid.getMessage()));
        }
        if (!node.isLeader()) {
            return forward(request.getForwarded(), leader -> forwarder.join(leader,
                    request.toBuilder().setForwarded(true).build()));
        }
        return outcome(node.admit(joining).map(result -> switch (result) {
            case JOINED -> response(Status.JOINED, "Added " + joining.name() + " as a non-voter");
            case ALREADY_MEMBER -> response(Status.ALREADY_MEMBER, joining.name() + " is already a member");
            case ADDRESS_UPDATED -> response(Status.ALREADY_MEMBER,
                    joining.name() + " is already a member; its address is now " + joining.address());
            case REPLACING -> response(Status.REPLACING,
                    "Removed the old entry at " + joining.name() + "'s name or address; ask again to join");
        }));
    }

    /** Removes the server {@code request} names, by ID or by name, on the leader, or forwards it there. */
    public Future<MembershipResponse> remove(RemoveServerRequest request) {
        Objects.requireNonNull(request, "request");
        if (operatorToken == null) {
            return Future.succeededFuture(response(Status.UNAUTHORIZED,
                    "Removal refused: no operator token is configured on " + node.getNodeId()));
        }
        if (!MessageDigest.isEqual(operatorToken, request.getToken().getBytes(StandardCharsets.UTF_8))) {
            logger.warn("Refused a server removal with a missing or wrong operator token");
            return Future.succeededFuture(response(Status.UNAUTHORIZED, "The operator token is missing or wrong"));
        }
        if (!node.isLeader()) {
            return forward(request.getForwarded(), leader -> forwarder.removeServer(leader,
                    request.toBuilder().setForwarded(true).build()));
        }
        if (request.getServerId().isBlank() && request.getName().isBlank()) {
            return Future.succeededFuture(response(Status.REFUSED, "Name the server to remove by ID or by name"));
        }
        Optional<RaftConfiguration.Server> removed = node.getConfiguration().flatMap(current ->
                request.getServerId().isBlank()
                        ? current.serverNamed(request.getName())
                        : current.server(request.getServerId()));
        if (removed.isEmpty()) {
            return Future.succeededFuture(response(Status.NOT_FOUND, "No configured server is "
                    + (request.getServerId().isBlank() ? "named " + request.getName() : request.getServerId())));
        }
        RaftConfiguration.Server server = removed.get();
        logger.info("Removing server {} ({}) at an operator's request", server.name(), server.serverId());
        return outcome(node.removeServer(server.serverId()).map(ignored ->
                response(Status.REMOVED, "Removed " + server.name() + " (" + server.serverId() + ")")));
    }

    private Future<MembershipResponse> forward(boolean alreadyForwarded,
                                               java.util.function.Function<String, Future<MembershipResponse>> send) {
        String leader = node.getLeaderId();
        if (alreadyForwarded || leader == null || leader.isBlank()) {
            return Future.succeededFuture(response(Status.NO_LEADER,
                    node.getNodeId() + " is not the leader and knows of none; try again"));
        }
        Future<MembershipResponse> answer;
        try {
            answer = send.apply(leader);
        } catch (RuntimeException unaddressable) {
            // A leader this server has heard of but cannot address yet, for one.
            answer = Future.failedFuture(unaddressable);
        }
        return answer
                .timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .recover(unreachable -> Future.succeededFuture(response(Status.NO_LEADER,
                        "The leader " + leader + " could not be reached: " + unreachable.getMessage())));
    }

    /** The node's answer, or its refusal as a status: leadership lost or no commit in time is worth retrying. */
    private static Future<MembershipResponse> outcome(Future<MembershipResponse> change) {
        return change.timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).recover(error -> {
            boolean retry = error instanceof CommandOutcomeUnknownException || error instanceof TimeoutException
                    || String.valueOf(error.getMessage()).startsWith("A configuration change waits until")
                    || String.valueOf(error.getMessage()).startsWith("Not the leader");
            return Future.succeededFuture(response(retry ? Status.NO_LEADER : Status.REFUSED,
                    String.valueOf(error.getMessage())));
        });
    }

    private static MembershipResponse response(Status status, String message) {
        return MembershipResponse.newBuilder().setStatus(status).setMessage(message).build();
    }
}
