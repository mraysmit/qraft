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
import dev.mars.qraft.server.health.ManualExpiryTimeHelper;
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how a leader installs the management token from its file (ACL list, proposal 12, Phase 3), after Consul's
 * {@code initializeACLs}: through the bootstrap command while no bootstrap has been applied, so that it closes the
 * bootstrap, and through a token creation afterwards; marked as installed from the file, with the
 * {@code global-management} policy and the leader's time; not again while a token has that secret, so that doing it
 * twice changes nothing; again at the next leadership once that token is deleted; tried again after a failure while
 * this server stays leader, and not once it is not.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
class ManagementTokenInstallerTest {
    private static final String SECRET = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
    private static final Instant START = Instant.parse("2026-11-05T09:00:00Z");
    private static final Duration RETRY = Duration.ofSeconds(1);

    private final QraftStateStore store = new QraftStateStore();
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
    private final ManualExpiryTimeHelper time = new ManualExpiryTimeHelper(START);
    private final List<RaftCommand> proposed = new ArrayList<>();

    @Test
    void aNewLeaderInstallsTheTokenThroughTheBootstrapAndSoClosesIt() {
        ManagementTokenInstaller installer = installer(this::apply);

        installer.leadershipChanged(true);

        assertInstanceOf(AclCommand.Bootstrap.class, proposed.getFirst());
        AclToken token = store.getAcl().tokenBySecret(SECRET).orElseThrow();
        assertEquals(List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), token.policyIds());
        assertTrue(token.installedFromFile(), "the token says it came from the file");
        assertEquals(START, token.createTime(), "stamped by the leader");
        assertEquals("Initial Management Token", token.description(), "Consul's description");
        assertEquals(1, store.getAcl().bootstrapIndex(), "the bootstrap is used up, as in Consul");
    }

    @Test
    void afterABootstrapTheTokenIsCreatedInstead() {
        apply(AclCommand.bootstrap(token("6ba7b810-9dad-41d1-80b4-00c04fd430c8",
                "3f2504e1-4f89-41d3-9a0c-0305e82c3301"), 0));
        proposed.clear();

        installer(this::apply).leadershipChanged(true);

        assertInstanceOf(AclCommand.CreateToken.class, proposed.getFirst());
        assertTrue(store.getAcl().tokenBySecret(SECRET).orElseThrow().installedFromFile());
        assertEquals(1, store.getAcl().bootstrapIndex());
    }

    @Test
    void nothingIsProposedWhileATokenHasTheSecretSoDoingItTwiceChangesNothing() {
        ManagementTokenInstaller installer = installer(this::apply);
        installer.leadershipChanged(true);
        String before = snapshot();

        installer.leadershipChanged(true);
        installer.leadershipChanged(false);
        installer.leadershipChanged(true);
        installer(this::apply).leadershipChanged(true);

        assertEquals(1, proposed.size(), "only the first leadership installed it");
        assertEquals(before, snapshot());
    }

    @Test
    void aDeletedTokenIsInstalledAgainAtTheNextLeadershipWhileTheFileHoldsItsSecret() {
        ManagementTokenInstaller installer = installer(this::apply);
        installer.leadershipChanged(true);
        AclToken first = store.getAcl().tokenBySecret(SECRET).orElseThrow();
        apply(AclCommand.createToken(token("6ba7b811-9dad-41d1-80b4-00c04fd430c8",
                "3f2504e2-4f89-41d3-9a0c-0305e82c3301")));
        assertInstanceOf(RaftCommandResult.Success.class, apply(AclCommand.deleteToken(first.accessorId())).join(),
                "another token holds acl:write, so the deletion is no lock-out");

        installer.leadershipChanged(false);
        installer.leadershipChanged(true);

        AclToken again = store.getAcl().tokenBySecret(SECRET).orElseThrow();
        assertTrue(!again.accessorId().equals(first.accessorId()), "a new token, under a new accessor ID");
        assertInstanceOf(AclCommand.CreateToken.class, proposed.getLast(), "the bootstrap stays used up");
    }

    @Test
    void aBootstrapThatAnotherCommandBeatsFallsBackToACreation() {
        // The bootstrap endpoint's command is applied between this leader's check and its own bootstrap.
        ManagementTokenInstaller installer = installer(command -> {
            if (proposed.isEmpty()) {
                store.apply(AclCommand.bootstrap(token("6ba7b810-9dad-41d1-80b4-00c04fd430c8",
                        "3f2504e1-4f89-41d3-9a0c-0305e82c3301"), 0));
            }
            return apply(command);
        });

        installer.leadershipChanged(true);

        assertInstanceOf(AclCommand.Bootstrap.class, proposed.getFirst());
        assertInstanceOf(AclCommand.CreateToken.class, proposed.getLast());
        assertTrue(store.getAcl().tokenBySecret(SECRET).isPresent());
    }

    @Test
    void aFailedProposalIsTriedAgainWhileLeaderAndNotOnceLeadershipEnds() {
        List<Boolean> fail = new ArrayList<>(List.of(true, true));
        ManagementTokenInstaller installer = installer(command -> fail.isEmpty() || !fail.removeFirst()
                ? apply(command)
                : record(command, CompletableFuture.failedFuture(new IllegalStateException("Not the leader"))));

        installer.leadershipChanged(true);
        assertEquals(1, proposed.size());
        assertTrue(store.getAcl().tokenBySecret(SECRET).isEmpty());

        time.advance(RETRY);
        assertEquals(2, proposed.size(), "tried again after the delay");
        installer.leadershipChanged(false);
        time.advance(RETRY.multipliedBy(10));
        assertEquals(2, proposed.size(), "a server that is no longer leader stops trying");

        installer.leadershipChanged(true);
        assertTrue(store.getAcl().tokenBySecret(SECRET).isPresent(), "the next leadership installs it");
        assertEquals(0, time.pendingTasks());
    }

    @Test
    void aClosedInstallerDoesNothing() {
        ManagementTokenInstaller installer = installer(this::apply);
        installer.close();

        installer.leadershipChanged(true);

        assertEquals(List.of(), proposed);
    }

    private ManagementTokenInstaller installer(Function<RaftCommand, CompletableFuture<RaftCommandResult<?>>> proposer) {
        return new ManagementTokenInstaller(store.getAcl(), SECRET, proposer, time, time, RETRY);
    }

    /** Applies {@code command} as a committed entry would be, through the log's encoding. */
    private CompletableFuture<RaftCommandResult<?>> apply(RaftCommand command) {
        return record(command, CompletableFuture.completedFuture(store.apply(codec.deserialize(codec.serialize(command)))));
    }

    private CompletableFuture<RaftCommandResult<?>> record(RaftCommand command,
                                                           CompletableFuture<RaftCommandResult<?>> outcome) {
        proposed.add(command);
        return outcome;
    }

    private String snapshot() {
        return new String(store.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static AclToken token(String accessor, String secret) {
        return new AclToken(accessor, AclSecrets.hash(secret), "", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID),
                List.of(), List.of(), List.of(), START, null, false);
    }
}
