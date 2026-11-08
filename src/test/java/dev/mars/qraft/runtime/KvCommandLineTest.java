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
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.http.HttpApiServer;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@code qraft kv} (key/value list, Phase 5), on Consul's {@code consul kv}: {@code put} from an argument or a
 * file with flags and compare-and-set, {@code get} of a value, recursively, or of keys cut at a separator,
 * {@code delete} of a key or a prefix, a tenant and namespace, and Consul's messages and exit codes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class KvCommandLineTest {
    @TempDir
    Path directory;

    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private QraftStateStore store;
    private HttpApiServer server;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("kv-cli").clusterNodes(Set.of("kv-cli")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store);
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

    private int kv(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        String[] all = new String[args.length + 2];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--http-addr";
        all[args.length + 1] = "127.0.0.1:" + server.port();
        return KvCommandLine.run(all, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aValueIsPutAndGotBack() {
        assertEquals(0, kv("put", "app/name", "hello", "--flags", "5"), err());
        assertEquals("Success! Data written to: app/name\n", out().replace("\r\n", "\n"));
        assertEquals(5, store.getKv().get(KvScope.DEFAULT, "app/name").orElseThrow().flags());

        assertEquals(0, kv("get", "app/name"), err());
        assertEquals("hello\n", out().replace("\r\n", "\n"));
        assertEquals(1, kv("get", "app/absent"));
        assertEquals("Error! No key exists at: app/absent\n", err().replace("\r\n", "\n"));
    }

    @Test
    void aValueIsReadFromAFileWhenItNamesOneAsInConsul() throws Exception {
        byte[] content = {0, 1, 2, (byte) 0xff};
        Path file = Files.write(directory.resolve("value.bin"), content);

        assertEquals(0, kv("put", "app/blob", "@" + file), err());

        assertArrayEquals(content, store.getKv().get(KvScope.DEFAULT, "app/blob").orElseThrow().value());
    }

    @Test
    void recursiveAndKeyReadsListInKeyOrder() {
        kv("put", "app/b", "2");
        kv("put", "app/a", "1");
        kv("put", "app/config/x", "3");

        assertEquals(0, kv("get", "app/", "--recurse"), err());
        assertEquals("app/a:1\napp/b:2\napp/config/x:3\n", out().replace("\r\n", "\n"));
        assertEquals(0, kv("get", "app/", "--keys", "--separator", "/"), err());
        assertEquals("app/a\napp/b\napp/config/\n", out().replace("\r\n", "\n"));
    }

    @Test
    void compareAndSetRefusesAStaleWriteAndDeletesRemoveAKeyOrAPrefix() {
        kv("put", "lock", "a");
        assertEquals(1, kv("put", "lock", "b", "--cas", "0"));
        assertEquals("Error! Did not write to lock: CAS failed\n", err().replace("\r\n", "\n"));
        long index = store.getKv().get(KvScope.DEFAULT, "lock").orElseThrow().modifyIndex();
        assertEquals(0, kv("put", "lock", "b", "--cas", Long.toString(index)), err());

        assertEquals(0, kv("delete", "lock"), err());
        assertEquals("Success! Deleted key: lock\n", out().replace("\r\n", "\n"));
        kv("put", "tree/a", "1");
        kv("put", "tree/b", "2");
        assertEquals(0, kv("delete", "tree/", "--recurse"), err());
        assertEquals("Success! Deleted keys with prefix: tree/\n", out().replace("\r\n", "\n"));
        assertTrue(store.getKv().list(KvScope.DEFAULT, "").isEmpty());
    }

    @Test
    void aTenantAndNamespaceScopeTheKeys() throws Exception {
        node.submitCommand(dev.mars.qraft.state.tenancy.TenancyCommand.createTenant("acme", "")).get(10, TimeUnit.SECONDS);
        node.submitCommand(dev.mars.qraft.state.tenancy.TenancyCommand.createNamespace("acme", "prod", "")).get(10, TimeUnit.SECONDS);
        assertEquals(0, kv("put", "shared", "acme", "--tenant", "acme", "--namespace", "prod"), err());

        assertTrue(store.getKv().get(new KvScope("acme", "prod"), "shared").isPresent());
        assertEquals(1, kv("get", "shared"), "the default scope holds none");
    }

    @Test
    void misuseExitsTwoWithTheUsage() {
        assertEquals(2, kv());
        assertEquals(2, kv("put", "only-a-key"));
        assertEquals(2, kv("get", "a", "--raw"));
        assertTrue(err().contains("Usage: qraft kv"), err());
    }
}
