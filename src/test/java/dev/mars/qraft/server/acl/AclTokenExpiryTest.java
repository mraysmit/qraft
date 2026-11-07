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
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclState;
import dev.mars.qraft.state.acl.AclToken;
import dev.mars.qraft.state.acl.AclView;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the leader's deletion of expired tokens (ACL list, Phase 8), after Consul's {@code reapExpiredACLTokens} and on
 * the pattern of {@code LeaderHealthExpiry}: while this server leads, at each interval, the tokens whose expiry has
 * passed by the leader's clock are deleted through the replicated command, in batches; nothing is proposed when none
 * has expired, or once leadership ends.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class AclTokenExpiryTest {
    private static final Instant START = Instant.parse("2026-11-07T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final ManualExpiryTimeHelper time = new ManualExpiryTimeHelper(START);
    private final AclState state = new AclState();
    private final List<AclCommand.DeleteExpiredTokens> proposed = new ArrayList<>();

    @Test
    void whileLeadingTheExpiredTokensAreDeletedAtEachInterval() {
        String first = token(Duration.ofMinutes(1));
        String second = token(Duration.ofMinutes(2));
        String lasting = token(null);
        AclTokenExpiry expiry = expiry(AclTokenExpiry.BATCH_SIZE);

        expiry.leadershipChanged(true);
        time.runDue();
        assertTrue(proposed.isEmpty(), "nothing has expired yet");

        time.advance(Duration.ofSeconds(60));
        assertEquals(List.of(List.of(first)), deleted());
        assertTrue(AclView.of(state).token(first).isEmpty());
        assertTrue(AclView.of(state).token(second).isPresent());

        time.advance(Duration.ofSeconds(60));
        assertEquals(List.of(List.of(first), List.of(second)), deleted());
        assertTrue(AclView.of(state).token(lasting).isPresent(), "a token without an expiry stays");
        assertEquals(START.plusSeconds(120), proposed.getLast().asOf(), "stamped with the leader's time");
    }

    @Test
    void manyExpiredTokensAreDeletedInBatches() {
        List<String> expired = new ArrayList<>();
        for (int i = 0; i < 5; i++) expired.add(token(Duration.ofMinutes(1)));
        AclTokenExpiry expiry = expiry(2);
        expiry.leadershipChanged(true);

        time.advance(Duration.ofMinutes(1));

        assertEquals(List.of(2, 2, 1), deleted().stream().map(List::size).toList());
        assertTrue(expired.stream().allMatch(id -> AclView.of(state).token(id).isEmpty()));
    }

    @Test
    void nothingIsProposedOnceLeadershipEnds() {
        token(Duration.ofMinutes(1));
        AclTokenExpiry expiry = expiry(AclTokenExpiry.BATCH_SIZE);
        expiry.leadershipChanged(true);
        expiry.leadershipChanged(false);

        time.advance(Duration.ofMinutes(5));

        assertTrue(proposed.isEmpty());
        expiry.leadershipChanged(true);
        time.runDue();
        assertEquals(1, proposed.size(), "a new leadership deletes what expired meanwhile");
        expiry.close();
    }

    private AclTokenExpiry expiry(int batchSize) {
        return new AclTokenExpiry(AclView.of(state), this::apply, time, time, INTERVAL, batchSize);
    }

    private CompletableFuture<RaftCommandResult<?>> apply(RaftCommand command) {
        AclCommand.DeleteExpiredTokens deletion = assertInstanceOf(AclCommand.DeleteExpiredTokens.class, command);
        proposed.add(deletion);
        return CompletableFuture.completedFuture(state.deleteExpiredTokens(deletion.asOf(), deletion.accessorIds()));
    }

    private List<List<String>> deleted() {
        return proposed.stream().map(AclCommand.DeleteExpiredTokens::accessorIds).toList();
    }

    /** A token created now, expiring after {@code ttl}, or never; returns its accessor ID. */
    private String token(Duration ttl) {
        String accessor = UUID.randomUUID().toString();
        assertInstanceOf(RaftCommandResult.Success.class, state.createToken(new AclToken(accessor,
                AclSecrets.hash(UUID.randomUUID().toString()), "", List.of(), List.of(), List.of(), List.of(), START,
                ttl == null ? null : START.plus(ttl), false)));
        return accessor;
    }
}
