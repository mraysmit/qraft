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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the command lines against an HTTPS server that requires a client certificate (transport security list,
 * Phase 4): {@code qraft operator raft} and {@code qraft acl} take {@code --ca-file}, {@code --client-cert} and
 * {@code --client-key}, as Consul's
 * take theirs; without a certificate they cannot reach the server; and a certificate without a CA is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class CommandLineTlsTest {
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    static Path fixture(String name) {
        try {
            return Path.of(CommandLineTlsTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder()
                .stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("tls-node").clusterNodes(Set.of("tls-node"))
                .transport(new PeerlessTransportFixture()).stateMachine(store)
                .commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(),
                AdminUiConfig.disabled(), null, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled(),
                Optional.of(new ServerTls(TlsMaterial.load(new TlsSettings("server.tls", fixture("ca.pem"),
                        Optional.of(fixture("server.pem")), Optional.of(fixture("server.key")),
                        List.of("TLSv1.3", "TLSv1.2"))), true)));
        server.start().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            server.close();
            node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            runtime.close().get(10, TimeUnit.SECONDS);
        }
    }

    private String address() {
        return "https://localhost:" + server.port();
    }

    private List<String> withCertificate(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--ca-file", fixture("ca.pem").toString(), "--client-cert", fixture("client.pem").toString(),
                "--client-key", fixture("client.key").toString()));
        return all;
    }

    private int raft(List<String> args) {
        return RaftOperatorCommand.run(args.toArray(String[]::new), print(out), print(err));
    }

    private int acl(List<String> args) {
        return AclCommandLine.run(args.toArray(String[]::new), print(out), print(err));
    }

    private static PrintStream print(ByteArrayOutputStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    @Test
    void theRaftCommandListsPeersOverHttpsWithItsCertificate() {
        int code = raft(withCertificate("raft", "list-peers", "--http-addr", address()));

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("tls-node"), out.toString(StandardCharsets.UTF_8));
    }


    @Test
    void withoutACertificateTheServerCannotBeReached() {
        int code = raft(List.of("raft", "list-peers", "--http-addr", address(), "--ca-file",
                fixture("ca.pem").toString()));

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("Could not reach " + address()),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aCertificateWithoutACaIsRefusedAsMisuse() {
        int code = raft(List.of("raft", "list-peers", "--http-addr", address(), "--client-cert",
                fixture("client.pem").toString(), "--client-key", fixture("client.key").toString()));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("--client-cert and --client-key need --ca-file"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theAclCommandReachesTheServerOverHttpsWithItsCertificate() {
        int code = acl(withCertificate("token", "list", "--http-addr", address()));

        assertTrue(code == 0 || code == 1, "not refused as misuse: " + err.toString(StandardCharsets.UTF_8));
        assertFalse(err.toString(StandardCharsets.UTF_8).contains("Could not reach"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theAclCommandWithoutACertificateCannotReachTheServer() {
        int code = acl(List.of("token", "list", "--http-addr", address(), "--ca-file", fixture("ca.pem").toString()));

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("Could not reach " + address()),
                err.toString(StandardCharsets.UTF_8));
    }
}
