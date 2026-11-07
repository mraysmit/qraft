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

package dev.mars.qraft.server.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code server.tls} (transport security list, decisions 1 to 4, Phase 2): no TLS by default; the material and
 * each listener's switch are read; a required client certificate and hostname verification are the defaults; a
 * listener cannot be enabled without a certificate; and the warning that tokens cross the network unencrypted names
 * only the listeners without TLS.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class AppConfigTlsTest {
    private static final String MATERIAL = "\"caFile\":\"/tls/ca.pem\",\"certFile\":\"/tls/s.pem\",\"keyFile\":\"/tls/s.key\"";
    private static final String ACL = "\"acl\":{\"enabled\":true,\"defaultPolicy\":\"allow\"}";

    @Test
    void withoutServerTlsNoListenerUsesIt() {
        AppConfig config = config("");

        assertEquals(Optional.empty(), config.getTlsSettings());
        assertFalse(config.isHttpsEnabled());
        assertFalse(config.isGrpcTlsEnabled());
        assertFalse(config.isRaftTlsEnabled());
    }

    @Test
    void readsTheMaterialAndEachListenerWithItsDefaults() {
        AppConfig config = config("\"tls\":{" + MATERIAL + ",\"https\":{\"enabled\":true},\"grpc\":{\"enabled\":true},"
                + "\"raft\":{\"enabled\":true}}");

        assertEquals(Path.of("/tls/ca.pem"), config.getTlsSettings().orElseThrow().caFile());
        assertEquals("server.tls", config.getTlsSettings().orElseThrow().location());
        assertTrue(config.isHttpsEnabled());
        assertTrue(config.isHttpsVerifyIncoming(), "a client certificate is required by default (decision 3)");
        assertTrue(config.isGrpcTlsEnabled());
        assertTrue(config.isGrpcVerifyIncoming());
        assertTrue(config.isRaftTlsEnabled());
        assertTrue(config.isRaftVerifyServerHostname(), "decision 4");
    }

    @Test
    void eachDefaultCanBeTurnedOff() {
        AppConfig config = config("\"tls\":{" + MATERIAL + ",\"https\":{\"enabled\":true,\"verifyIncoming\":false},"
                + "\"grpc\":{\"enabled\":true,\"verifyIncoming\":false},"
                + "\"raft\":{\"enabled\":true,\"verifyServerHostname\":false}}");

        assertFalse(config.isHttpsVerifyIncoming());
        assertFalse(config.isGrpcVerifyIncoming());
        assertFalse(config.isRaftVerifyServerHostname());
    }

    @Test
    void materialAloneEnablesNoListener() {
        AppConfig config = config("\"tls\":{" + MATERIAL + "}");

        assertTrue(config.getTlsSettings().isPresent());
        assertFalse(config.isHttpsEnabled());
        assertFalse(config.isGrpcTlsEnabled());
        assertFalse(config.isRaftTlsEnabled());
    }

    @Test
    void aListenerCannotServeTlsWithoutACertificate() {
        for (String listener : List.of("https", "grpc", "raft")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> config(
                    "\"tls\":{\"caFile\":\"/tls/ca.pem\",\"" + listener + "\":{\"enabled\":true}}"));
            assertEquals("server.tls.certFile and server.tls.keyFile are required for server.tls." + listener,
                    refused.getMessage());
        }
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> config("\"tls\":{" + MATERIAL + ",\"http\":{}}"));
        assertThrows(IllegalArgumentException.class,
                () -> config("\"tls\":{" + MATERIAL + ",\"https\":{\"verifyOutgoing\":true}}"));
        assertThrows(IllegalArgumentException.class,
                () -> config("\"tls\":{" + MATERIAL + ",\"raft\":{\"verifyIncoming\":false}}"));
    }

    @Test
    void theUnencryptedWarningNamesOnlyTheListenersWithoutTls() {
        assertEquals(List.of("ACLs are enabled, but no listener uses TLS: tokens cross the network unencrypted. "
                + "Set server.tls.https and server.tls.grpc"), warnings(config(ACL)));
        assertEquals(List.of("ACLs are enabled, but the API gRPC listener does not use TLS: tokens sent to it cross "
                        + "the network unencrypted. Set server.tls.grpc"),
                warnings(config(ACL + ",\"tls\":{" + MATERIAL + ",\"https\":{\"enabled\":true}}")));
        assertEquals(List.of("ACLs are enabled, but the HTTP listener does not use TLS: tokens sent to it cross the "
                        + "network unencrypted. Set server.tls.https"),
                warnings(config(ACL + ",\"tls\":{" + MATERIAL + ",\"grpc\":{\"enabled\":true}}")));
        assertEquals(List.of(), warnings(config(ACL + ",\"tls\":{" + MATERIAL
                + ",\"https\":{\"enabled\":true},\"grpc\":{\"enabled\":true}}")));
    }

    private static List<String> warnings(AppConfig config) {
        config.validate();
        Logger logger = (Logger) LoggerFactory.getLogger(AppConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            config.logConfiguration();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static AppConfig config(String server) {
        return AppConfig.fromJson("{\"version\":1,\"server\":{" + server + "}}");
    }
}
