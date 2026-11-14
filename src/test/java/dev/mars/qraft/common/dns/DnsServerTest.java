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

package dev.mars.qraft.common.dns;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.OperationNotSupportedException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the DNS listener (DNS discovery list, Phase 2) with the JDK's own DNS client, JNDI, which shares no code with
 * Qraft's codec: over UDP, over TCP when an answer is truncated, and with the codes a resolver sees.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class DnsServerTest {
    private final Map<String, DnsReads.Read> answers = new HashMap<>();
    private DnsServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private DnsServer start() throws Exception {
        DnsReads reads = (path, scope) -> {
            DnsReads.Read read = answers.get(path);
            return CompletableFuture.completedFuture(read == null ? Optional.empty() : Optional.of(read));
        };
        server = DnsServer.start("127.0.0.1", 0, new DnsResolver(reads, "qraft", 0, false, Duration.ofSeconds(2),
                new SplittableRandom(3)));
        return server;
    }

    private void instances(String service, int count, int port) {
        List<String> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entries.add("{\"service\":{\"address\":\"10.0." + (i / 250) + "." + (i % 250 + 1) + "\",\"port\":" + port
                    + ",\"tags\":[],\"health\":\"PASSING\"},\"nodeStatus\":\"healthy\"}");
        }
        answers.put("/v1/health/service/" + service, new DnsReads.Read(200, "[" + String.join(",", entries) + "]"));
    }

    private DirContext jndi() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns://127.0.0.1:" + server.port());
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        return new InitialDirContext(env);
    }

    private Set<String> lookup(String name, String type) throws NamingException {
        DirContext context = jndi();
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
    void answersAnAddressLookupOverUdp() throws Exception {
        start();
        instances("web", 2, 8080);

        assertEquals(Set.of("10.0.0.2", "10.0.0.3"), lookup("web.service.qraft", "A"));
        assertEquals(Set.of("1 1 8080 0a000002.addr.qraft.", "1 1 8080 0a000003.addr.qraft."),
                lookup("web.service.qraft", "SRV"));
    }

    @Test
    void answersInFullOverTcpWhatUdpTruncates() throws Exception {
        start();
        instances("big", 60, 80);

        assertEquals(60, lookup("big.service.qraft", "A").size(),
                "JNDI asks again over TCP on the same port when the UDP answer is truncated");
    }

    @Test
    void givesTheCodesAResolverActsOn() throws Exception {
        start();
        instances("web", 1, 80);
        answers.put("/v1/health/service/secret", new DnsReads.Read(403, "{}"));
        answers.put("/v1/health/service/nothing", new DnsReads.Read(200, "[]"));

        assertThrows(NameNotFoundException.class, () -> lookup("nothing.service.qraft", "A"));
        assertThrows(ServiceUnavailableException.class, () -> lookup("unanswered.service.qraft", "A"),
                "SERVFAIL: no server could be asked");
        // JNDI reports REFUSED, as it does NOTIMP, as an unsupported operation; its message tells them apart.
        assertTrue(assertThrows(OperationNotSupportedException.class, () -> lookup("secret.service.qraft", "A"))
                .getMessage().toLowerCase(java.util.Locale.ROOT).contains("refused"));
        assertTrue(assertThrows(OperationNotSupportedException.class, () -> lookup("example.com", "A"))
                .getMessage().toLowerCase(java.util.Locale.ROOT).contains("refused"), "not this domain");
    }

    @Test
    void answersQueriesOnOneTcpConnectionInTurn() throws Exception {
        start();
        instances("web", 1, 80);

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            for (int id = 1; id <= 2; id++) {
                byte[] query = query(id, "web.service.qraft");
                out.writeShort(query.length);
                out.write(query);
                out.flush();
                byte[] answer = new byte[in.readUnsignedShort()];
                in.readFully(answer);
                assertEquals(id, ((answer[0] & 0xff) << 8) | (answer[1] & 0xff));
                assertEquals(1, answer[7], "one answer record");
            }
        }
    }

    @Test
    void answersFormErrToAQueryItCannotReadAndKeepsAnswering() throws Exception {
        start();
        instances("web", 1, 80);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            InetSocketAddress to = new InetSocketAddress("127.0.0.1", server.port());
            byte[] twoQuestions = query(9, "web.service.qraft");
            twoQuestions[5] = 2;
            socket.send(new DatagramPacket(twoQuestions, twoQuestions.length, to));
            byte[] buffer = new byte[512];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            assertEquals(12, reply.getLength());
            assertEquals(DnsCodec.FORMERR, buffer[3] & 0xf);

            socket.send(new DatagramPacket(new byte[5], 5, to));
            socket.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[512], 512)),
                    "a packet shorter than a header is dropped");
        }
        assertEquals(Set.of("10.0.0.2"), lookup("web.service.qraft", "A"));
    }

    @Test
    void aPortHeldForUdpIsLeftForTheNextCandidate() throws Exception {
        // On 2026-11-13 every retry of a picked port was refused for UDP: Windows gives bind(0) ports in sequence, so
        // a run of ports another process held for UDP refused them all. Each attempt now takes a port of its own.
        // After the two held ports the candidates are random, as in production, and every one tried is recorded, so
        // the test does not rely on a port staying free between choosing it and the server's bind.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket first = new DatagramSocket(new InetSocketAddress(loopback, 0));
             DatagramSocket second = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            List<Integer> tried = new ArrayList<>();
            java.util.function.IntSupplier candidates = () -> {
                int candidate = switch (tried.size()) {
                    case 0 -> first.getLocalPort();
                    case 1 -> second.getLocalPort();
                    default -> java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(DnsServer.DYNAMIC_FIRST, DnsServer.DYNAMIC_LAST + 1);
                };
                tried.add(candidate);
                return candidate;
            };

            server = DnsServer.start("127.0.0.1", 0, resolver(), candidates);

            assertEquals(List.of(first.getLocalPort(), second.getLocalPort()), tried.subList(0, 2), "tried first");
            assertTrue(tried.size() >= 3, "the two ports held for UDP were passed over: " + tried);
            assertEquals(tried.getLast(), server.port(), "the server listens on the last port it tried");
        }
    }

    @Test
    void aFailureToListenNamesThePortsItTried() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket first = new DatagramSocket(new InetSocketAddress(loopback, 0));
             DatagramSocket second = new DatagramSocket(new InetSocketAddress(loopback, 0))) {
            int[] held = {first.getLocalPort(), second.getLocalPort()};
            int[] next = {0};

            java.net.BindException failed = assertThrows(java.net.BindException.class,
                    () -> DnsServer.start("127.0.0.1", 0, resolver(), () -> held[next[0]++ % 2]));

            assertTrue(failed.getMessage().contains(String.valueOf(held[0]))
                    && failed.getMessage().contains(String.valueOf(held[1])), failed.getMessage());
        }
    }

    private DnsResolver resolver() {
        return new DnsResolver((path, scope) -> CompletableFuture.completedFuture(Optional.empty()), "qraft", 0, false,
                Duration.ofSeconds(2), new SplittableRandom(3));
    }

    @Test
    void freesItsPortWhenClosed() throws Exception {
        int port = start().port();
        server.close();
        server = null;

        try (DatagramSocket udp = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
             ServerSocket tcp = new ServerSocket()) {
            tcp.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            assertTrue(udp.isBound() && tcp.isBound());
        }
    }

    private static byte[] query(int id, String name) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) (id >> 8), (byte) id, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.writeBytes(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        out.writeBytes(new byte[] {0, 0, 1, 0, 1});
        return out.toByteArray();
    }
}
