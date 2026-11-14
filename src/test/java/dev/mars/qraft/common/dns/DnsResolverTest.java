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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests how DNS names are answered from the catalog's reads (DNS discovery list, Phase 2, decisions 4 to 7), over a
 * stub of the reads that answers what each test gives it and remembers what it was asked.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class DnsResolverTest {
    private final Map<String, DnsReads.Read> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final List<Map<String, String>> scopes = new ArrayList<>();
    private boolean noServer;
    private final DnsReads reads = (path, scope) -> {
        asked.add(path);
        scopes.add(scope);
        if (noServer) return CompletableFuture.completedFuture(Optional.empty());
        DnsReads.Read read = answers.get(path);
        return read == null ? new CompletableFuture<>() : CompletableFuture.completedFuture(Optional.of(read));
    };

    private DnsResolver resolver(boolean onlyPassing) {
        return new DnsResolver(reads, "qraft", 30, onlyPassing, Duration.ofMillis(500), new SplittableRandom(7));
    }

    private static String instance(String id, String address, int port, String health, String nodeStatus,
                                   String... tags) {
        String tagList = String.join(",", java.util.Arrays.stream(tags).map(tag -> "\"" + tag + "\"").toList());
        return "{\"service\":{\"serviceId\":\"" + id + "\",\"serviceName\":\"web\",\"nodeId\":\"n-" + id
                + "\",\"address\":\"" + address + "\",\"port\":" + port + ",\"tags\":[" + tagList + "],\"health\":\""
                + health + "\",\"tenantId\":\"default\",\"namespace\":\"default\"},\"checks\":[],\"nodeStatus\":\""
                + nodeStatus + "\"}";
    }

    private void health(String service, String... instances) {
        answers.put("/v1/health/service/" + service, new DnsReads.Read(200, "[" + String.join(",", instances) + "]"));
    }

    private static DnsQuery query(String name, int type) {
        return new DnsQuery(1, true, List.of(name.split("\\.")), type, DnsCodec.CLASS_IN, 0);
    }

    private DnsAnswer resolve(String name, int type) throws Exception {
        return resolve(resolver(false), name, type);
    }

    private static DnsAnswer resolve(DnsResolver resolver, String name, int type) throws Exception {
        return resolver.resolve(query(name, type)).get(5, TimeUnit.SECONDS);
    }

    private static Set<String> addresses(List<DnsRecord> records) {
        Set<String> found = new LinkedHashSet<>();
        for (DnsRecord record : records) {
            switch (record.data()) {
                case DnsRecord.A a -> found.add(a.address().getHostAddress());
                case DnsRecord.Aaaa aaaa -> found.add(aaaa.address().getHostAddress());
                default -> found.add(record.data().toString());
            }
        }
        return found;
    }

    private static void assertNegative(DnsAnswer answer, int rcode) {
        assertEquals(rcode, answer.rcode());
        assertTrue(answer.authoritative());
        assertTrue(answer.answers().isEmpty());
        assertEquals(1, answer.authority().size(), "the SOA");
        DnsRecord soa = answer.authority().getFirst();
        assertEquals("qraft", soa.name());
        DnsRecord.Soa data = assertInstanceOf(DnsRecord.Soa.class, soa.data());
        assertEquals("ns.qraft", data.primary());
        assertEquals("hostmaster.qraft", data.mailbox());
        assertEquals(30, data.minimum(), "a negative answer is kept as long as a positive one");
    }

    @Test
    void answersTheAddressesOfAServicesInstancesThatAreNotFailing() throws Exception {
        health("web",
                instance("a", "10.0.0.1", 80, "PASSING", "healthy"),
                instance("b", "10.0.0.2", 80, "WARNING", "healthy"),
                instance("c", "10.0.0.3", 80, "UNKNOWN", "registering"),
                instance("d", "10.0.0.4", 80, "CRITICAL", "healthy"),
                instance("e", "10.0.0.5", 80, "MAINTENANCE", "healthy"),
                instance("f", "10.0.0.6", 80, "FAILING", "healthy"),
                instance("g", "10.0.0.7", 80, "PASSING", "unreachable"));

        DnsAnswer answer = resolve("Web.Service.Qraft", DnsCodec.TYPE_A);

        assertEquals(DnsCodec.NOERROR, answer.rcode());
        assertTrue(answer.authoritative());
        assertEquals(List.of("/v1/health/service/web"), asked, "the name in lower case, as resolvers may mix it");
        assertEquals(Map.of(), scopes.getFirst(), "the reader's own scope");
        assertEquals(Set.of("10.0.0.1", "10.0.0.2", "10.0.0.3"), addresses(answer.answers()),
                "passing, warning and no checks; not critical, in maintenance, failing or on an unreachable node");
        for (DnsRecord record : answer.answers()) {
            assertEquals("web.service.qraft", record.name());
            assertEquals(30, record.ttl());
        }
    }

    @Test
    void leavesOutAWarningWhenOnlyPassingInstancesAreAnswered() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy"),
                instance("b", "10.0.0.2", 80, "WARNING", "healthy"));

        assertEquals(Set.of("10.0.0.1"), addresses(resolve(resolver(true), "web.service.qraft", DnsCodec.TYPE_A)
                .answers()));
    }

    @Test
    void answersOneRecordForAnAddressTwoInstancesShare() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy"),
                instance("b", "10.0.0.1", 81, "PASSING", "healthy"));

        assertEquals(1, resolve("web.service.qraft", DnsCodec.TYPE_A).answers().size());
    }

    @Test
    void answersOnlyTheInstancesWithATagNamedBeforeTheService() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy", "Primary"),
                instance("b", "10.0.0.2", 80, "PASSING", "healthy", "replica"));

        DnsAnswer answer = resolve("primary.web.service.qraft", DnsCodec.TYPE_A);

        assertEquals(Set.of("10.0.0.1"), addresses(answer.answers()), "tags match in any case");
        assertEquals("primary.web.service.qraft", answer.answers().getFirst().name());
        assertNegative(resolve("canary.web.service.qraft", DnsCodec.TYPE_A), DnsCodec.NXDOMAIN);
    }

    @Test
    void answersAaaaForIpv6InstancesAndBothForAny() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy"),
                instance("b", "fd00::1", 80, "PASSING", "healthy"));

        DnsAnswer aaaa = resolve("web.service.qraft", DnsCodec.TYPE_AAAA);
        assertEquals(1, aaaa.answers().size());
        assertInstanceOf(DnsRecord.Aaaa.class, aaaa.answers().getFirst().data());
        assertEquals(Set.of("10.0.0.1", "fd00:0:0:0:0:0:0:1"), addresses(resolve("web.service.qraft",
                DnsCodec.TYPE_ANY).answers()));
    }

    @Test
    void answersNoDataForAServiceWithoutRecordsOfTheTypeAsked() throws Exception {
        health("web", instance("b", "fd00::1", 80, "PASSING", "healthy"));

        assertNegative(resolve("web.service.qraft", DnsCodec.TYPE_A), DnsCodec.NOERROR);
        assertNegative(resolve("web.service.qraft", 16), DnsCodec.NOERROR); // TXT
    }

    @Test
    void answersNxDomainForAServiceWithNoInstanceToAnswer() throws Exception {
        health("web");
        health("down", instance("d", "10.0.0.4", 80, "CRITICAL", "healthy"));

        assertNegative(resolve("web.service.qraft", DnsCodec.TYPE_A), DnsCodec.NXDOMAIN);
        assertNegative(resolve("down.service.qraft", DnsCodec.TYPE_SRV), DnsCodec.NXDOMAIN);
    }

    @Test
    void answersSrvRecordsWithAddrTargetsAndTheirAddresses() throws Exception {
        health("web", instance("a", "10.0.0.7", 8080, "PASSING", "healthy"),
                instance("b", "fd00::1", 8443, "PASSING", "healthy"),
                instance("c", "web-3.example.com", 9090, "PASSING", "healthy"));

        DnsAnswer answer = resolve("web.service.qraft", DnsCodec.TYPE_SRV);

        Map<String, Integer> ports = new HashMap<>();
        for (DnsRecord record : answer.answers()) {
            DnsRecord.Srv srv = assertInstanceOf(DnsRecord.Srv.class, record.data());
            assertEquals(1, srv.priority());
            assertEquals(1, srv.weight());
            ports.put(srv.target(), srv.port());
        }
        assertEquals(Map.of("0a000007.addr.qraft", 8080, "fd000000000000000000000000000001.addr.qraft", 8443,
                "web-3.example.com", 9090), ports, "a host name is its own target");
        assertEquals(2, answer.additional().size(), "the addresses of the addr targets");
        Map<String, String> additional = new HashMap<>();
        for (DnsRecord record : answer.additional()) {
            additional.put(record.name(), addresses(List.of(record)).iterator().next());
        }
        assertEquals(Map.of("0a000007.addr.qraft", "10.0.0.7",
                "fd000000000000000000000000000001.addr.qraft", "fd00:0:0:0:0:0:0:1"), additional);
    }

    @Test
    void answersTheRfc2782FormWithItsTagAndTcpAsAnyTag() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy", "primary"),
                instance("b", "10.0.0.2", 81, "PASSING", "healthy"));

        DnsAnswer tagged = resolve("_web._primary.service.qraft", DnsCodec.TYPE_SRV);
        assertEquals(1, tagged.answers().size());
        assertEquals(80, ((DnsRecord.Srv) tagged.answers().getFirst().data()).port());
        assertEquals("_web._primary.service.qraft", tagged.answers().getFirst().name());
        assertEquals(2, resolve("_web._tcp.service.qraft", DnsCodec.TYPE_SRV).answers().size());
    }

    @Test
    void readsInTheNamespaceAndTenantTheNameGives() throws Exception {
        answers.put("/v1/health/service/web", new DnsReads.Read(200, "[" + instance("a", "10.0.0.1", 80, "PASSING",
                "healthy") + "]"));

        resolve("web.service.team.ns.acme.ap.qraft", DnsCodec.TYPE_A);
        resolve("web.service.team.ns.qraft", DnsCodec.TYPE_A);
        resolve("web.service.acme.ap.qraft", DnsCodec.TYPE_A);

        assertEquals(List.of(Map.of("X-Qraft-Namespace", "team", "X-Qraft-Tenant", "acme"),
                Map.of("X-Qraft-Namespace", "team"), Map.of("X-Qraft-Tenant", "acme")), scopes);
    }

    @Test
    void answersNxDomainForADatacenterOrAnUnknownFormWithoutAskingTheServers() throws Exception {
        for (String name : List.of("web.service.dc1.dc.qraft", "web.service.dc1.qraft", "a.b.web.service.qraft",
                "service.qraft", "web.query.qraft", "x.y.node.qraft", "node.qraft", "zz.addr.qraft",
                "0a00000g.addr.qraft", "_web.service.qraft")) {
            assertNegative(resolve(name, DnsCodec.TYPE_A), DnsCodec.NXDOMAIN);
        }
        assertEquals(List.of(), asked);
    }

    @Test
    void answersANodesAddressFromTheCatalogsNodes() throws Exception {
        answers.put("/v1/catalog/nodes", new DnsReads.Read(200, "[{\"name\":\"Server1\",\"id\":\"1\",\"address\":"
                + "\"10.1.0.1\",\"status\":\"healthy\"},{\"name\":\"client-7\",\"id\":\"7\",\"address\":\"fd00::7\","
                + "\"status\":\"unreachable\"}]"));

        DnsAnswer server = resolve("server1.node.qraft", DnsCodec.TYPE_A);
        assertEquals(Set.of("10.1.0.1"), addresses(server.answers()));
        assertEquals("server1.node.qraft", server.answers().getFirst().name());
        assertEquals(Set.of("fd00:0:0:0:0:0:0:7"), addresses(resolve("client-7.node.qraft", DnsCodec.TYPE_AAAA)
                .answers()), "a node is answered whatever its health, as Consul answers it");
        assertNegative(resolve("client-7.node.qraft", DnsCodec.TYPE_A), DnsCodec.NOERROR);
        assertNegative(resolve("nobody.node.qraft", DnsCodec.TYPE_A), DnsCodec.NXDOMAIN);
        assertEquals(Map.of(), scopes.getFirst());
    }

    @Test
    void answersAnAddrNameFromTheNameAlone() throws Exception {
        assertEquals(Set.of("10.0.0.7"), addresses(resolve("0A000007.addr.qraft", DnsCodec.TYPE_A).answers()));
        assertEquals(Set.of("fd00:0:0:0:0:0:0:1"), addresses(resolve("fd000000000000000000000000000001.addr.qraft",
                DnsCodec.TYPE_AAAA).answers()));
        assertNegative(resolve("0a000007.addr.qraft", DnsCodec.TYPE_AAAA), DnsCodec.NOERROR);
        assertEquals(List.of(), asked);
    }

    @Test
    void answersTheApexWithItsSoa() throws Exception {
        DnsAnswer soa = resolve("qraft", DnsCodec.TYPE_SOA);
        assertEquals(DnsCodec.NOERROR, soa.rcode());
        assertEquals(1, soa.answers().size());
        assertInstanceOf(DnsRecord.Soa.class, soa.answers().getFirst().data());
        assertNegative(resolve("qraft", DnsCodec.TYPE_A), DnsCodec.NOERROR);
    }

    @Test
    void refusesANameOutsideTheDomainOrClass() throws Exception {
        for (String name : List.of("example.com", "qraft.example.com", "webqraft")) {
            DnsAnswer answer = resolve(name, DnsCodec.TYPE_A);
            assertEquals(DnsCodec.REFUSED, answer.rcode(), name);
            assertFalse(answer.authoritative());
            assertTrue(answer.authority().isEmpty());
        }
        DnsQuery chaos = new DnsQuery(1, false, List.of("web", "service", "qraft"), DnsCodec.TYPE_A, 3, 0);
        assertEquals(DnsCodec.REFUSED, resolver(false).resolve(chaos).get(5, TimeUnit.SECONDS).rcode());
        assertEquals(List.of(), asked);
    }

    @Test
    void answersADomainOfSeveralLabels() throws Exception {
        health("web", instance("a", "10.0.0.1", 80, "PASSING", "healthy"));
        DnsResolver resolver = new DnsResolver(reads, "Consul.Example", 0, false, Duration.ofSeconds(1),
                new SplittableRandom(1));

        assertEquals(Set.of("10.0.0.1"), addresses(resolve(resolver, "web.service.consul.example", DnsCodec.TYPE_A)
                .answers()));
        assertEquals(DnsCodec.REFUSED, resolve(resolver, "web.service.qraft", DnsCodec.TYPE_A).rcode());
    }

    @Test
    void answersServFailWhenNoServerAnswersOrItFailsAndRefusedWhenTheTokenMayNotRead() throws Exception {
        noServer = true;
        assertEquals(DnsCodec.SERVFAIL, resolve("web.service.qraft", DnsCodec.TYPE_A).rcode());
        noServer = false;
        answers.put("/v1/health/service/busy", new DnsReads.Read(503, "{\"code\":\"not_ready\"}"));
        assertEquals(DnsCodec.SERVFAIL, resolve("busy.service.qraft", DnsCodec.TYPE_A).rcode());
        answers.put("/v1/health/service/secret", new DnsReads.Read(403, "{\"code\":\"permission_denied\"}"));
        assertEquals(DnsCodec.REFUSED, resolve("secret.service.qraft", DnsCodec.TYPE_A).rcode());
        answers.put("/v1/health/service/odd", new DnsReads.Read(400, "{\"code\":\"invalid_scope\"}"));
        assertNegative(resolve("odd.service.qraft", DnsCodec.TYPE_A), DnsCodec.NXDOMAIN);
        answers.put("/v1/health/service/garbled", new DnsReads.Read(200, "not json"));
        assertEquals(DnsCodec.SERVFAIL, resolve("garbled.service.qraft", DnsCodec.TYPE_A).rcode());
    }

    @Test
    void answersServFailWhenAReadTakesLongerThanItsTimeout() throws Exception {
        long started = System.nanoTime();
        assertEquals(DnsCodec.SERVFAIL, resolve("silent.service.qraft", DnsCodec.TYPE_A).rcode());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
    }

    @Test
    void aTimedOutReadIsAnsweredOnADeadlineThreadNotTheJdksSharedDelayScheduler() throws Exception {
        // PROJECT_STANDARDS.md bounds waits with Deadlines, never orTimeout: what follows an orTimeout expiry runs on
        // the JVM-wide delay scheduler, where a slow step delays every other timeout in the process. The administrative
        // interface's Step 6 scan found the resolver using orTimeout on 2026-11-14.
        String thread = resolver(false).resolve(query("silent.service.qraft", DnsCodec.TYPE_A))
                .thenApply(answer -> Thread.currentThread().getName())
                .get(5, TimeUnit.SECONDS);

        assertTrue(thread.startsWith("qraft-deadlines-expiry-"), "answered on " + thread);
    }

    @Test
    void shufflesEachAnswer() throws Exception {
        List<String> instances = new ArrayList<>();
        for (int i = 1; i <= 10; i++) instances.add(instance("i" + i, "10.0.0." + i, 80, "PASSING", "healthy"));
        health("web", instances.toArray(String[]::new));
        DnsResolver resolver = resolver(false);

        Set<List<String>> orders = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            orders.add(List.copyOf(addresses(resolve(resolver, "web.service.qraft", DnsCodec.TYPE_A).answers())));
        }

        assertTrue(orders.size() > 1, "the orders differ: " + orders);
        assertNotEquals(List.of("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5", "10.0.0.6", "10.0.0.7",
                "10.0.0.8", "10.0.0.9", "10.0.0.10"), orders.iterator().next());
    }

    @Test
    void encodesTheServiceNameInItsPath() throws Exception {
        resolve("my%svc.service.qraft", DnsCodec.TYPE_A);
        assertEquals(List.of("/v1/health/service/my%25svc"), asked);
    }
}
