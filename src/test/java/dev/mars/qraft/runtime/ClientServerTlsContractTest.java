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

import dev.mars.qraft.client.QraftClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.common.tls.ServerTls;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.AclHttpSettings;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.testing.fault.IntentionalErrorFixture;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a client calling a server over HTTPS (transport security list, Phase 3): with {@code client.tls} naming the
 * CA and its certificate it registers and stays healthy against a server that requires a client certificate; without
 * a certificate, or trusting another CA, it cannot, says so once, and is not ready.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ClientServerTlsContractTest {
    @TempDir
    Path dataRoot;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private QraftClient client;

    @AfterEach
    void closeResources() throws Exception {
        try {
            if (client != null) client.shutdown().get(10, TimeUnit.SECONDS);
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    static Path fixture(String name) {
        try {
            return Path.of(ClientServerTlsContractTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    private static TlsSettings settings(String ca, String cert, String key) {
        return new TlsSettings("client.tls", fixture(ca), Optional.ofNullable(cert).map(ClientServerTlsContractTest::fixture),
                Optional.ofNullable(key).map(ClientServerTlsContractTest::fixture), List.of("TLSv1.3", "TLSv1.2"));
    }

    private QraftStateStore startServer() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("tls-node")
                .clusterNodes(Set.of("tls-node"))
                .transport(new PeerlessTransportFixture())
                .stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(25)
                .heartbeatInterval(10_000)
                .build();
        node.start().get(10, TimeUnit.SECONDS);
        waitUntil(node::isLeader);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled(),
                Optional.of(new ServerTls(TlsMaterial.load(new TlsSettings("server.tls", fixture("ca.pem"),
                        Optional.of(fixture("server.pem")), Optional.of(fixture("server.key")),
                        List.of("TLSv1.3", "TLSv1.2"))), true)));
        server.start().get(10, TimeUnit.SECONDS);
        return store;
    }

    private QraftClient startClient(TlsSettings tls) throws Exception {
        ClientConfiguration configuration = ClientConfiguration.builder()
                .nodeName("tls-client")
                .dataDirectory(dataRoot.resolve("client"))
                .address("127.0.0.1")
                .clientPort(0)
                .serverUrl("https://localhost:" + server.port())
                .heartbeatInterval(25)
                .requestTimeoutMs(1_000)
                .registrationRetryMinMs(50)
                .registrationRetryMaxMs(100)
                .tls(tls)
                .build();
        client = new QraftClient(configuration);
        client.start().get(10, TimeUnit.SECONDS);
        return client;
    }

    @Test
    void aClientWithItsCertificateRegistersAndStaysHealthyOverHttps() throws Exception {
        QraftStateStore store = startServer();
        startClient(settings("ca.pem", "client.pem", "client.key"));

        waitUntil(() -> client.localApi().isReady());
        waitUntil(() -> store.findNode("tls-client")
                .filter(info -> info.status() == NodeStatus.HEALTHY && info.lastHeartbeat() != null).isPresent());
    }

    @Test
    void aClientWithoutACertificateCannotRegisterAndSaysSoOnce() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.CLIENT_TLS_REFUSED, 1);
        QraftStateStore store = startServer();
        startClient(settings("ca.pem", null, null));

        Thread.sleep(1_500);
        assertFalse(client.localApi().isReady());
        assertTrue(store.findNode("tls-client").isEmpty());
    }

    @Test
    void aClientThatTrustsAnotherCaRefusesTheServerAndSaysSoOnce() throws Exception {
        IntentionalErrorsHelper.expect(IntentionalErrorFixture.CLIENT_TLS_REFUSED, 1);
        QraftStateStore store = startServer();
        startClient(settings("other-ca.pem", "client.pem", "client.key"));

        Thread.sleep(1_500);
        assertFalse(client.localApi().isReady());
        assertEquals(Optional.empty(), store.findNode("tls-client"));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition was not met before the deadline");
    }
}
