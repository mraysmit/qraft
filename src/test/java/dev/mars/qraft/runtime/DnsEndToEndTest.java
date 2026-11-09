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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.QraftClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end test of DNS discovery (DNS discovery list, Phase 3): a server launched from its configuration file, a
 * client running a service with an HTTP check, and a second client whose DNS interface answers for it. The second
 * client's lookups find the instance while its check passes, stop finding it once the check is critical, and find it
 * again once it recovers; the server's own DNS interface answers the same.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
@Tag("e2e")
class DnsEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOT_FOUND = "<NameNotFoundException>";

    @TempDir
    Path directory;
    private final List<QraftClient> clients = new ArrayList<>();
    private final AtomicInteger workloadStatus = new AtomicInteger(200);
    private RuntimeLifecycle server;
    private HttpServer workload;

    @AfterEach
    void stop() throws Exception {
        try {
            for (QraftClient client : clients.reversed()) client.shutdown().get(30, TimeUnit.SECONDS);
            if (server != null) server.close();
        } finally {
            if (workload != null) workload.stop(0);
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

    private static Set<String> await(int port, String name, Set<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Set<String> found = lookup(port, name, "A");
        while (!found.equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            found = lookup(port, name, "A");
        }
        return found;
    }

    private QraftClient client(String name, int serverHttp, Map<String, Object> extra) throws Exception {
        Map<String, Object> client = new java.util.LinkedHashMap<>(Map.of("nodeName", name,
                "dataDirectory", directory.resolve(name).toString(), "address", "127.0.0.1", "httpPort", 0,
                "heartbeatIntervalMs", 200, "shutdownTimeoutMs", 5000));
        Map<String, Object> catalog = new java.util.LinkedHashMap<>(Map.of("registrationRetryMinMs", 25,
                "registrationRetryMaxMs", 200));
        if (extra.containsKey("dns")) client.put("dns", extra.get("dns"));
        if (extra.containsKey("services")) catalog.put("services", extra.get("services"));
        Path config = directory.resolve(name + ".json");
        JSON.writeValue(config.toFile(), Map.of("version", 1, "client", client,
                "servers", Map.of("urls", List.of("http://127.0.0.1:" + serverHttp), "requestTimeoutMs", 5000),
                "catalog", catalog, "logging", Map.of("directory", directory.resolve("logs").toString())));
        QraftClient started = QraftClient.launch(config);
        clients.add(started);
        return started;
    }

    @Test
    void aClientsDnsFollowsAnotherClientsInstanceThroughItsHealth() throws Exception {
        workload = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workload.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(workloadStatus.get(), -1);
            exchange.close();
        });
        workload.start();
        URI health = URI.create("http://127.0.0.1:" + workload.getAddress().getPort() + "/health");

        Path serverConfig = directory.resolve("server.json");
        JSON.writeValue(serverConfig.toFile(), Map.of("version", 1, "server", Map.of(
                "id", "dns-e2e", "http", Map.of("host", "127.0.0.1", "port", 0), "apiGrpcPort", 0,
                "raft", Map.of("port", 0, "electionTimeoutMs", 500, "heartbeatIntervalMs", 50,
                        "storage", Map.of("path", directory.resolve("raft").toString())),
                "health", Map.of("expiryIntervalMs", 50),
                "dns", Map.of("host", "127.0.0.1", "port", 0),
                "telemetry", Map.of("enabled", false)),
                "logging", Map.of("directory", directory.resolve("logs").toString())));
        server = QraftRuntimeApplication.launch(new String[] {"server", "--config", serverConfig.toString()});
        int serverHttp = server.boundPorts().get("http");
        int serverDns = server.boundPorts().get("dns");

        client("app-client", serverHttp, Map.of("services", List.of(Map.of("id", "web-1", "name", "web",
                "address", "10.0.0.31", "port", 8080, "checks", List.of(Map.of("id", "http", "type", "http",
                        "url", health.toString(), "intervalMs", 200, "timeoutMs", 200, "ttlMs", 5000))))));
        int clientDns = client("dns-client", serverHttp, Map.of("dns", Map.of("host", "127.0.0.1", "port", 0)))
                .dnsPort().orElseThrow();

        assertEquals(Set.of("10.0.0.31"), await(clientDns, "web.service.qraft", Set.of("10.0.0.31")),
                "the instance, once its check passes, through the other client");
        assertEquals(Set.of("10.0.0.31"), lookup(serverDns, "web.service.qraft", "A"), "and through the server");

        workloadStatus.set(503);
        assertEquals(Set.of(NOT_FOUND), await(clientDns, "web.service.qraft", Set.of(NOT_FOUND)),
                "a critical instance is not answered");
        assertEquals(Set.of(NOT_FOUND), lookup(serverDns, "web.service.qraft", "A"));

        workloadStatus.set(200);
        assertEquals(Set.of("10.0.0.31"), await(clientDns, "web.service.qraft", Set.of("10.0.0.31")),
                "and is answered again once it recovers");
        assertEquals(Set.of("127.0.0.1"), lookup(clientDns, "app-client.node.qraft", "A"));
    }
}
