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

package dev.mars.qraft.server.acl;

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclRefusal;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Installs the management token from {@code server.acl.initialManagementTokenFile} whenever this server becomes leader
 * and no token has that secret (ACL list, proposal 12), as Consul's {@code initializeACLs} installs
 * {@code acl.tokens.initial_management}:
 *
 * <ul>
 *   <li>through the bootstrap command while no bootstrap has been applied, so that the file's token closes the
 *       bootstrap endpoint, and through a token creation once it has, including when another bootstrap is applied
 *       first;</li>
 *   <li>with the {@code global-management} policy, Consul's description, the leader's time, a new accessor ID, and the
 *       mark that it came from the file;</li>
 *   <li>not while a token has the secret, so that installing twice changes nothing; a deleted token therefore returns
 *       at the next leadership while a server's file still holds its secret, which is how Consul's token
 *       behaves;</li>
 *   <li>again after {@code retryDelay} when a proposal fails while this server stays leader, as Consul's leader loop
 *       retries establishing leadership, and not once leadership ends.</li>
 * </ul>
 *
 * <p>The secret stays in memory, as the configuration holds it, to look its token up; it is never logged, and the
 * command carries only its hash. The accessor ID is logged.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public final class ManagementTokenInstaller implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ManagementTokenInstaller.class);
    /** Consul's description for the token it installs from {@code initial_management}. */
    static final String DESCRIPTION = "Initial Management Token";

    private final AclView acl;
    private final String secret;
    private final String secretHash;
    private final Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer;
    private final ExpiryScheduler scheduler;
    private final Clock clock;
    private final Duration retryDelay;
    private Runnable detach = () -> { };
    private boolean leader;
    private boolean closed;
    private long term;
    private ExpiryScheduler.Cancellable retry;

    ManagementTokenInstaller(AclView acl, String secret,
                             Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                             ExpiryScheduler scheduler, Clock clock, Duration retryDelay) {
        this.acl = Objects.requireNonNull(acl, "acl");
        if (!AclSecrets.isUuid(secret)) throw new IllegalArgumentException("the management secret must be a UUID");
        this.secret = secret;
        this.secretHash = AclSecrets.hash(secret);
        this.proposer = Objects.requireNonNull(proposer, "proposer");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    }

    /**
     * An installer that follows {@code node}'s leadership, for the file's {@code secret}; closing it removes the
     * node's state-change listener.
     */
    public static ManagementTokenInstaller attach(RaftNode node, AclView acl, String secret,
                                                  Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer,
                                                  ExpiryScheduler scheduler, Clock clock, Duration retryDelay) {
        ManagementTokenInstaller installer = new ManagementTokenInstaller(acl, secret, proposer, scheduler, clock,
                retryDelay);
        Consumer<RaftNode.State> listener = state -> installer.leadershipChanged(state == RaftNode.State.LEADER);
        node.addStateChangeListener(listener);
        installer.detach = () -> node.removeStateChangeListener(listener);
        // A node that became leader before the listener was added is not notified again.
        installer.leadershipChanged(node.isLeader());
        return installer;
    }

    /** Installs the token on gaining leadership, and stops trying on losing it. Repeats are ignored. */
    public void leadershipChanged(boolean nowLeader) {
        long attempt;
        synchronized (this) {
            if (closed || nowLeader == leader) return;
            leader = nowLeader;
            term++;
            cancelRetry();
            if (!nowLeader) return;
            attempt = term;
        }
        install(attempt);
    }

    @Override
    public void close() {
        detach.run();
        synchronized (this) {
            closed = true;
            leader = false;
            term++;
            cancelRetry();
        }
    }

    private void install(long attempt) {
        if (!current(attempt) || acl.tokenBySecret(secret).isPresent()) return;
        AclToken token = new AclToken(UUID.randomUUID().toString(), secretHash, DESCRIPTION,
                List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(), List.of(), List.of(), clock.instant(), null, true);
        boolean bootstrap = acl.bootstrapIndex() == 0;
        RaftCommand command = bootstrap ? AclCommand.bootstrap(token, 0) : AclCommand.createToken(token);
        CompletableFuture<RaftCommandResult<?>> outcome;
        try {
            outcome = proposer.apply(command);
        } catch (RuntimeException e) {
            outcome = CompletableFuture.failedFuture(e);
        }
        outcome.whenComplete((result, failure) -> completed(attempt, token, bootstrap, result, failure));
    }

    private void completed(long attempt, AclToken token, boolean bootstrap, RaftCommandResult<?> result,
                           Throwable failure) {
        if (failure == null && result instanceof RaftCommandResult.Success<?>) {
            LOG.info("Installed the management token from server.acl.initialManagementTokenFile through the {}: "
                    + "accessor {}", bootstrap ? "bootstrap" : "creation of a token", token.accessorId());
            return;
        }
        if (failure == null && result instanceof RaftCommandResult.Refused<?> refused) {
            if (refused.code().equals(AclRefusal.SECRET_IN_USE.code())) return;
            if (refused.code().equals(AclRefusal.ACL_BOOTSTRAPPED.code())) {
                // Another bootstrap was applied first; the token is created instead, as Consul falls back.
                install(attempt);
                return;
            }
        }
        String reason = failure != null ? String.valueOf(rootCause(failure).getMessage()) : String.valueOf(result);
        synchronized (this) {
            if (closed || attempt != term) return;
            LOG.warn("Could not install the management token from server.acl.initialManagementTokenFile; trying "
                    + "again in {} ms while this server leads: {}", retryDelay.toMillis(), reason);
            retry = scheduler.schedule(() -> install(attempt), retryDelay);
        }
    }

    private synchronized boolean current(long attempt) {
        return !closed && leader && attempt == term;
    }

    private void cancelRetry() {
        if (retry != null) retry.cancel();
        retry = null;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause;
    }
}
