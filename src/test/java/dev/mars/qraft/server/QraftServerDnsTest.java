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

package dev.mars.qraft.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a server's DNS interface (DNS discovery list, Phase 3, decisions 1 to 3): it answers from the server's own
 * catalog routes, so health and ACLs mean what they mean over HTTP, and it stops with the server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class QraftServerDnsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGEMENT = "6b1d3c3e-2f0a-4c55-9e4e-5d1b2b7f9a10";
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path directory;
    private RaftExecutorsFixture executors;
    private QraftServerService service;

    @AfterEach
    void stop() throws Exception {
        try {
            if (service != null) service.stop().get(30, TimeUnit.SECONDS);
        } finally {
            if (executors != null) executors.close().get(30, TimeUnit.SECONDS);
            http.close();
        }
    }

    private void start(Map<String, Object> dns, Map<String, Object> acl) throws Exception {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("id", "dns-server");
        server.put("http", Map.of("host", "127.0.0.1", "port", 0));
        server.put("apiGrpcPort", 0);
        server.put("raft", Map.of("port", 0, "electionTimeoutMs", 300, "heartbeatIntervalMs", 100,
                "storage", Map.of("path", directory.resolve("raft").toString())));
        server.put("dns", dns);
        if (acl != null) server.put("acl", acl);
        server.put("telemetry", Map.of("enabled", false));
        Path configuration = directory.resolve("server.json");
        JSON.writeValue(configuration.toFile(), Map.of("version", 1, "server", server));
        AppConfig config = AppConfig.fromFile(configuration);
        config.validate();
        executors = RaftExecutorsFixture.create();
        service = new QraftServerService(executors.stateLoop(), executors.blockingTasks(), config);
        service.start().get(30, TimeUnit.SECONDS);
    }

    private int send(String method, String path, String body, String token, String node) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + service.boundAddresses().get("http").getPort() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) request.header("X-Qraft-Token", token);
        if (node != null) request.header("X-Qraft-Node", node);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** Registers {@code node} with a service instance once the server leads, retrying until it does. */
    private void register(String node, String nodeAddress, String id, String address, int port, String token)
            throws Exception {
        String body = "{\"node\":{\"address\":\"" + nodeAddress + "\"},\"service\":{\"serviceId\":\"" + id
                + "\",\"serviceName\":\"web\",\"address\":\"" + address + "\",\"port\":" + port + "}}";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int status;
        do {
            status = send("PUT", "/v1/catalog/register", body, token, node);
            if (status == 200) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertEquals(200, status, "the registration of " + id);
    }

    private int dnsPort() {
        return service.boundAddresses().get("dns").getPort();
    }

    private Set<String> lookup(String name, String type) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns://127.0.0.1:" + dnsPort());
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
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
    }

    @Test
    void aServerAnswersLookupsFromItsCatalogAndStopsAnsweringWhenItStops() throws Exception {
        start(Map.of("host", "127.0.0.1", "port", 0, "ttlSeconds", 5), null);
        register("host-a", "10.0.0.1", "web-1", "10.0.0.5", 8080, null);
        register("host-b", "10.0.0.2", "web-2", "10.0.0.6", 8081, null);

        assertEquals(Set.of("10.0.0.5", "10.0.0.6"), lookup("web.service.qraft", "A"),
                "instances with no checks are answered, as Consul counts them passing");
        assertEquals(Set.of("1 1 8080 0a000005.addr.qraft.", "1 1 8081 0a000006.addr.qraft."),
                lookup("web.service.qraft", "SRV"));
        assertEquals(Set.of("10.0.0.2"), lookup("host-b.node.qraft", "A"));
        assertThrows(NameNotFoundException.class, () -> lookup("api.service.qraft", "A"));

        int port = dnsPort();
        service.stop().get(30, TimeUnit.SECONDS);
        service = null;
        try (DatagramSocket freed = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),
                port))) {
            assertTrue(freed.isBound(), "the DNS listener stops with the server");
        }
    }

    @Test
    void aServerWithAclsReadsUnderItsDnsToken() throws Exception {
        Path management = Files.writeString(directory.resolve("management"), MANAGEMENT);
        start(Map.of("host", "127.0.0.1", "port", 0, "tokenFile", management.toString()),
                Map.of("enabled", true, "defaultPolicy", "deny", "initialManagementTokenFile", management.toString()));
        register("host-a", "10.0.0.1", "web-1", "10.0.0.5", 8080, MANAGEMENT);
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + service.boundAddresses().get("http").getPort() + "/v1/health/service/web")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(anonymous.statusCode() != 200 || JSON.readTree(anonymous.body()).isEmpty(),
                "the anonymous token reads nothing: " + anonymous.statusCode() + " " + anonymous.body());

        assertEquals(Set.of("10.0.0.5"), lookup("web.service.qraft", "A"),
                "the DNS token may read what the anonymous token may not");
    }
}
