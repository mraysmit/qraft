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

package dev.mars.qraft.server.session;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.server.health.ManualExpiryTimeHelper;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.session.Session;
import dev.mars.qraft.state.session.SessionBehavior;
import dev.mars.qraft.state.session.SessionCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests session TTLs on the leader (sessions list, decision 2, Phase 2), as Consul's {@code session_ttl.go}: a timer at
 * twice the TTL, from the session's creation or renewal, or from this server's leadership; a renewal resets it and is
 * not replicated; a lapsed session is destroyed through Raft at the leader's time; a session without a TTL never
 * lapses; and only the leader renews.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class SessionTtlExpiryTest {
    private static final Instant START = Instant.parse("2026-11-08T12:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(10);
    private final ManualExpiryTimeHelper time = new ManualExpiryTimeHelper(START);
    private final QraftStateStore store = new QraftStateStore();
    private final List<SessionCommand.Destroy> destroyed = new ArrayList<>();
    private long index;

    private CompletableFuture<RaftCommandResult<?>> propose(RaftCommand command) {
        if (command instanceof SessionCommand.Destroy destroy) destroyed.add(destroy);
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return CompletableFuture.completedFuture(result);
    }

    private SessionTtlExpiry expiry() {
        propose(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), START));
        return new SessionTtlExpiry(store.getSessions(), this::propose, time, time, Duration.ofSeconds(1));
    }

    private void session(String id, Duration ttl) {
        propose(SessionCommand.create(new Session(id, "host-a", id, SessionBehavior.RELEASE, ttl, Duration.ZERO, 0,
                time.instant())));
    }

    @Test
    void aSessionThatIsNotRenewedLapsesAtTwiceItsTtlAndIsDestroyedAtTheLeadersTime() {
        SessionTtlExpiry expiry = expiry();
        expiry.leadershipChanged(true);
        session("s1", TTL);

        time.advance(Duration.ofSeconds(19));
        assertTrue(store.getSessions().get("s1").isPresent(), "within twice the TTL");
        time.advance(Duration.ofSeconds(2));

        assertEquals(Optional.empty(), store.getSessions().get("s1"));
        assertEquals(1, destroyed.size());
        assertTrue(!destroyed.getFirst().at().isBefore(START.plusSeconds(20)), "stamped with the leader's time");
    }

    @Test
    void aRenewalResetsTheTimerAndASessionWithoutATtlNeverLapses() {
        SessionTtlExpiry expiry = expiry();
        expiry.leadershipChanged(true);
        session("s1", TTL);
        session("forever", Duration.ZERO);

        time.advance(Duration.ofSeconds(15));
        assertEquals("s1", expiry.renew("s1").orElseThrow().id());
        time.advance(Duration.ofSeconds(15));
        assertTrue(store.getSessions().get("s1").isPresent(), "twice the TTL has not passed since the renewal");
        time.advance(Duration.ofSeconds(6));
        assertEquals(Optional.empty(), store.getSessions().get("s1"));

        time.advance(Duration.ofHours(1));
        assertTrue(store.getSessions().get("forever").isPresent());
        assertEquals(Optional.empty(), expiry.renew("absent"), "a session that does not exist is not renewed");
    }

    @Test
    void aNewLeaderGivesEverySessionTwiceItsTtlAndOnlyTheLeaderRenews() {
        SessionTtlExpiry expiry = expiry();
        session("s1", TTL);
        assertThrows(IllegalStateException.class, () -> expiry.renew("s1"), "a follower does not renew");

        time.advance(Duration.ofMinutes(5));
        expiry.leadershipChanged(true);
        time.advance(Duration.ofSeconds(19));
        assertTrue(store.getSessions().get("s1").isPresent(), "a new leader starts it again at twice the TTL");
        time.advance(Duration.ofSeconds(2));
        assertEquals(Optional.empty(), store.getSessions().get("s1"));

        expiry.leadershipChanged(false);
        session("s2", TTL);
        time.advance(Duration.ofMinutes(5));
        assertTrue(store.getSessions().get("s2").isPresent(), "nothing lapses while this server does not lead");
        expiry.close();
    }
}
