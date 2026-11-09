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

package dev.mars.qraft.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.QraftServerService;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a client's DNS interface (DNS discovery list, Phase 3, decisions 1 to 3): it answers from the servers, which
 * it forwards its reads to as its local API does, so it holds no copy of the catalog; and it stops with the client.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class QraftClientDnsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;
    private RaftExecutorsFixture executors;
    private QraftServerService server;
    private QraftClient client;

    @AfterEach
    void stop() throws Exception {
        try {
            if (client != null) client.shutdown().get(30, TimeUnit.SECONDS);
        } finally {
            try {
                if (server != null) server.stop().get(30, TimeUnit.SECONDS);
            } finally {
                if (executors != null) executors.close().get(30, TimeUnit.SECONDS);
            }
        }
    }

    private static Set<String> lookup(int port, String name, String type) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns://127.0.0.1:" + port);
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            DirContext context = new InitialDirContext(env);
            try {
                Attribute attribute = context.getAttributes(name, new String[] {type}).get(type);
                Set<String> values = new HashSet<>();
                if (attribute == null) return values;
                NamingEnumeration<?> all = attribute.getAll();
                while (all.hasMore()) values.add(String.valueOf(all.next()));
                return values;
            } finally {
                context.close();
            }
        } catch (NamingException notAnswered) {
            return Set.of("<" + notAnswered.getClass().getSimpleName() + ">");
        }
    }

    private static Set<String> await(int port, String name, String type, Set<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Set<String> found = lookup(port, name, type);
        while (!found.equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            found = lookup(port, name, type);
        }
        return found;
    }

    @Test
    void aClientAnswersLookupsFromTheServersAndStopsAnsweringWhenItStops() throws Exception {
        Path serverConfig = directory.resolve("server.json");
        JSON.writeValue(serverConfig.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "dns-client-server", "http", Map.of("host", "127.0.0.1", "port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "telemetry", Map.of("enabled", false))));
        AppConfig config = AppConfig.fromFile(serverConfig);
        config.validate();
        executors = RaftExecutorsFixture.create();
        server = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        server.start().get(30, TimeUnit.SECONDS);
        int http = server.boundAddresses().get("http").getPort();

        client = new QraftClient(ClientConfiguration.fromJson(JSON.writeValueAsString(Map.of("version", 1,
                "client", Map.of("nodeName", "dns-client", "dataDirectory", directory.resolve("client").toString(),
                        "address", "127.0.0.1", "httpPort", 0, "heartbeatIntervalMs", 200,
                        "dns", Map.of("host", "127.0.0.1", "port", 0)),
                "servers", Map.of("urls", List.of("http://127.0.0.1:" + http)),
                "catalog", Map.of("registrationRetryMinMs", 25, "registrationRetryMaxMs", 200,
                        "services", List.of(Map.of("id", "web-1", "name", "web", "address", "10.0.0.21",
                                "port", 8080)))))));
        client.start();
        int dns = client.dnsPort().orElseThrow();

        assertEquals(Set.of("10.0.0.21"), await(dns, "web.service.qraft", "A", Set.of("10.0.0.21")));
        assertEquals(Set.of("1 1 8080 0a000015.addr.qraft."), lookup(dns, "web.service.qraft", "SRV"));
        assertEquals(Set.of("127.0.0.1"), lookup(dns, "dns-client.node.qraft", "A"));

        assertTrue(client.shutdown().get(30, TimeUnit.SECONDS));
        client = null;
        try (DatagramSocket freed = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                dns))) {
            assertTrue(freed.isBound(), "the DNS listener stops with the client");
        }
    }
}
