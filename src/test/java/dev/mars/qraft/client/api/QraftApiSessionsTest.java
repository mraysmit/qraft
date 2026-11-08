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

package dev.mars.qraft.client.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the client library's session calls (client library list, Phase 2, decision 4) against a real server: a session
 * created with its options and read back, renewed, used for a lock, and destroyed, after which a renewal answers that
 * it is gone; a session the server cannot bind is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftApiSessionsTest {
    private LibraryServerFixture fixture;
    private QraftApi api;

    @AfterEach
    void stop() throws Exception {
        try {
            if (api != null) api.close();
        } finally {
            if (fixture != null) fixture.close();
        }
    }

    @Test
    void aSessionIsCreatedReadRenewedUsedAndDestroyed() throws Exception {
        fixture = LibraryServerFixture.start();
        api = QraftApi.builder().address(fixture.address()).build();
        Sessions sessions = api.sessions();

        String id = sessions.create(SessionOptions.named("billing-leader").node("host-a").ttl(Duration.ofSeconds(10))
                .lockDelay(Duration.ZERO).behavior(SessionOptions.Behavior.DELETE));
        SessionInfo info = sessions.info(id).orElseThrow();
        assertEquals(id, info.id());
        assertEquals("billing-leader", info.name());
        assertEquals("host-a", info.node());
        assertEquals(Duration.ofSeconds(10), info.ttl());
        assertEquals(Duration.ZERO, info.lockDelay());
        assertEquals(SessionOptions.Behavior.DELETE, info.behavior());
        assertTrue(info.createIndex() > 0);

        assertTrue(sessions.renew(id));
        byte[] me = "me".getBytes(StandardCharsets.UTF_8);
        assertTrue(api.keyValue().acquire("service/billing/leader", me, id));
        assertEquals(id, api.keyValue().get("service/billing/leader").orElseThrow().session());

        assertTrue(sessions.destroy(id));
        assertEquals(Optional.empty(), sessions.info(id));
        assertFalse(sessions.renew(id), "a session that is gone");
        assertEquals(Optional.empty(), api.keyValue().get("service/billing/leader"), "its behaviour deleted its key");

        String plain = sessions.create(SessionOptions.named("plain").node("host-b"));
        SessionInfo defaults = sessions.info(plain).orElseThrow();
        assertEquals(Duration.ZERO, defaults.ttl(), "no TTL");
        assertEquals(Duration.ofSeconds(15), defaults.lockDelay(), "the server's default delay");
        assertEquals(SessionOptions.Behavior.RELEASE, defaults.behavior());
    }

    @Test
    void aSessionTheServerCannotBindIsRefused() throws Exception {
        fixture = LibraryServerFixture.start();
        api = QraftApi.builder().address(fixture.address()).build();
        QraftApiException noNode = assertThrows(QraftApiException.class,
                () -> api.sessions().create(SessionOptions.named("orphan")));
        assertEquals(400, noNode.status());
        QraftApiException unknown = assertThrows(QraftApiException.class,
                () -> api.sessions().create(SessionOptions.named("lost").node("host-z")));
        assertEquals("node_not_found", unknown.code());
    }
}
