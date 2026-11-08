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

package dev.mars.qraft.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.TlsReloader;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a client watches its {@code client.tls} files (certificate reloading list, Phase 2, decision 2): a renewed
 * certificate is reloaded while the client runs, and shutting the client down stops the checks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ClientTlsReloadTest {
    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftClient client;
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TlsReloader.class);

    @AfterEach
    void stop() throws Exception {
        try {
            logger.detachAppender(log);
            if (client != null) client.shutdown().get(10, TimeUnit.SECONDS);
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    private static Path fixture(String name) throws Exception {
        return Path.of(ClientTlsReloadTest.class.getResource("/tls/" + name).toURI());
    }

    private static boolean reloaderRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().equals("qraft-tls-reloader"));
    }

    @Test
    void aRenewedClientCertificateIsReloadedWhileTheClientRuns() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("reload-server").clusterNodes(Set.of("reload-server"))
                .transport(new PeerlessTransportFixture()).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.start().get(10, TimeUnit.SECONDS);

        Path tls = Files.createDirectories(dataRoot.resolve("tls"));
        Path ca = Files.copy(fixture("ca.pem"), tls.resolve("ca.pem"));
        Path cert = Files.copy(fixture("client.pem"), tls.resolve("client.pem"));
        Path key = Files.copy(fixture("client.key"), tls.resolve("client.key"));
        client = new QraftClient(ClientConfiguration.builder()
                .nodeName("reload-client").dataDirectory(dataRoot.resolve("client")).address("127.0.0.1")
                .clientPort(0).serverUrl("http://127.0.0.1:" + server.port()).heartbeatInterval(50)
                .requestTimeoutMs(5_000).registrationRetryMinMs(20).registrationRetryMaxMs(100)
                .tls(new TlsSettings("client.tls", ca, Optional.of(cert), Optional.of(key),
                        List.of("TLSv1.3", "TLSv1.2")))
                .build());
        client.useTlsReloadInterval(Duration.ofMillis(200));
        log.start();
        logger.addAppender(log);
        client.start().get(10, TimeUnit.SECONDS);
        assertTrue(reloaderRunning(), "the client checks its files");

        Files.copy(fixture("client.pem"), cert, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(cert, FileTime.from(Instant.now().plusSeconds(60)));
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && log.list.stream()
                .noneMatch(event -> event.getFormattedMessage().startsWith("client.tls reloaded: certificate"))) {
            Thread.sleep(50);
        }
        assertTrue(log.list.stream().anyMatch(event -> event.getFormattedMessage()
                .startsWith("client.tls reloaded: certificate")), "the renewed certificate was reloaded: " + log.list);

        client.shutdown().get(10, TimeUnit.SECONDS);
        client = null;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reloaderRunning() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(!reloaderRunning(), "shutting the client down stops the checks");
    }
}
