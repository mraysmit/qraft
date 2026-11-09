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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * Answers DNS queries for a domain from the catalog's HTTP reads (DNS discovery list, decisions 4 to 7):
 * <pre>
 * [tag.]&lt;service&gt;.service[.&lt;namespace&gt;.ns][.&lt;tenant&gt;.ap].&lt;domain&gt;   A, AAAA, SRV
 * _&lt;service&gt;._&lt;tag&gt;.service[...].&lt;domain&gt;                         SRV, where _tcp means any tag
 * &lt;node&gt;.node.&lt;domain&gt;                                            A, AAAA
 * &lt;hex address&gt;.addr.&lt;domain&gt;                                     A, AAAA
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public final class DnsResolver {
    private static final Logger LOG = LoggerFactory.getLogger(DnsResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ANSWERED = Set.of("passing", "warning", "unknown");
    private static final Set<String> ANSWERED_ONLY_PASSING = Set.of("passing", "unknown");

    private final DnsReads reads;
    private final List<String> domain;
    private final String domainName;
    private final long ttl;
    private final boolean onlyPassing;
    private final Duration timeout;
    private final RandomGenerator random;

    /**
     * @param reads       the reads the answers come from
     * @param domain      the domain answered, such as {@code qraft}
     * @param ttlSeconds  the time to live of every record, and of a negative answer
     * @param onlyPassing whether an instance with a warning is left out
     * @param timeout     how long a read may take before the query is answered SERVFAIL
     * @param random      shuffles each answer
     */
    public DnsResolver(DnsReads reads, String domain, long ttlSeconds, boolean onlyPassing, Duration timeout,
                       RandomGenerator random) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.domainName = normalisedDomain(domain);
        this.domain = List.of(domainName.split("\\."));
        if (ttlSeconds < 0 || ttlSeconds > 0x7fff_ffffL) throw new IllegalArgumentException("ttl out of range");
        this.ttl = ttlSeconds;
        this.onlyPassing = onlyPassing;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** {@code domain} in lower case with no final dot; it must be one or more DNS labels. */
    static String normalisedDomain(String domain) {
        Objects.requireNonNull(domain, "domain");
        String name = domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain;
        name = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")) {
            throw new IllegalArgumentException("The DNS domain must be one or more DNS labels: " + domain);
        }
        return name;
    }

    /** The answer to {@code query}. */
    public CompletableFuture<DnsAnswer> resolve(DnsQuery query) {
        try {
            return answer(query).exceptionally(failed -> {
                LOG.debug("A DNS lookup of {} failed: {}", query.name(), String.valueOf(failed));
                return DnsAnswer.of(DnsCodec.SERVFAIL);
            });
        } catch (RuntimeException failed) {
            LOG.debug("A DNS lookup of {} failed: {}", query.name(), String.valueOf(failed));
            return CompletableFuture.completedFuture(DnsAnswer.of(DnsCodec.SERVFAIL));
        }
    }

    private CompletableFuture<DnsAnswer> answer(DnsQuery query) {
        List<String> labels = query.labels().stream().map(label -> label.toLowerCase(Locale.ROOT)).toList();
        if ((query.qclass() != DnsCodec.CLASS_IN && query.qclass() != DnsCodec.CLASS_ANY)
                || labels.size() < domain.size()
                || !labels.subList(labels.size() - domain.size(), labels.size()).equals(domain)) {
            return done(DnsAnswer.of(DnsCodec.REFUSED));
        }
        List<String> rest = new ArrayList<>(labels.subList(0, labels.size() - domain.size()));
        String name = query.name();
        if (rest.isEmpty()) {
            boolean soa = query.type() == DnsCodec.TYPE_SOA || query.type() == DnsCodec.TYPE_ANY;
            return done(soa ? new DnsAnswer(DnsCodec.NOERROR, true, List.of(soa()), List.of(), List.of())
                    : negative(DnsCodec.NOERROR));
        }
        Map<String, String> scope = new LinkedHashMap<>();
        if (rest.size() >= 2 && rest.getLast().equals("ap")) {
            scope.put("X-Qraft-Tenant", rest.get(rest.size() - 2));
            rest = rest.subList(0, rest.size() - 2);
        }
        if (rest.size() >= 2 && rest.getLast().equals("ns")) {
            scope.put("X-Qraft-Namespace", rest.get(rest.size() - 2));
            rest = rest.subList(0, rest.size() - 2);
        }
        if (rest.isEmpty()) return done(negative(DnsCodec.NXDOMAIN));
        String kind = rest.getLast();
        List<String> before = rest.subList(0, rest.size() - 1);
        switch (kind) {
            case "service" -> {
                return service(query, name, before, scope);
            }
            case "node" -> {
                if (before.size() != 1 || !scope.isEmpty()) return done(negative(DnsCodec.NXDOMAIN));
                return node(query, name, before.getFirst());
            }
            case "addr" -> {
                if (before.size() != 1 || !scope.isEmpty()) return done(negative(DnsCodec.NXDOMAIN));
                Optional<InetAddress> address = addrAddress(before.getFirst());
                if (address.isEmpty()) return done(negative(DnsCodec.NXDOMAIN));
                return done(addresses(query, name, List.of(address.get())));
            }
            default -> {
                return done(negative(DnsCodec.NXDOMAIN));
            }
        }
    }

    private CompletableFuture<DnsAnswer> service(DnsQuery query, String name, List<String> before,
                                                 Map<String, String> scope) {
        String service;
        Optional<String> tag;
        if (before.size() == 2 && before.get(0).startsWith("_") && before.get(1).startsWith("_")) {
            service = before.get(0).substring(1);
            String protocol = before.get(1).substring(1);
            tag = protocol.equals("tcp") ? Optional.empty() : Optional.of(protocol);
        } else if (before.size() == 1 && !before.getFirst().startsWith("_")) {
            service = before.getFirst();
            tag = Optional.empty();
        } else if (before.size() == 2 && !before.get(0).startsWith("_") && !before.get(1).startsWith("_")) {
            service = before.get(1);
            tag = Optional.of(before.get(0));
        } else {
            return done(negative(DnsCodec.NXDOMAIN));
        }
        if (service.isEmpty() || tag.filter(String::isEmpty).isPresent()) return done(negative(DnsCodec.NXDOMAIN));
        String path = "/v1/health/service/" + URLEncoder.encode(service, StandardCharsets.UTF_8).replace("+", "%20");
        return read(path, scope, body -> {
            List<Endpoint> endpoints = new ArrayList<>();
            for (JsonNode entry : body) {
                JsonNode instance = entry.path("service");
                String health = instance.path("health").asText("").toLowerCase(Locale.ROOT);
                if (!(onlyPassing ? ANSWERED_ONLY_PASSING : ANSWERED).contains(health)) continue;
                if (entry.path("nodeStatus").asText("").equalsIgnoreCase("unreachable")) continue;
                if (tag.isPresent() && !hasTag(instance.path("tags"), tag.get())) continue;
                String address = instance.path("address").asText("");
                int port = instance.path("port").asInt(-1);
                if (address.isEmpty() || port < 0 || port > 65_535) continue;
                endpoints.add(new Endpoint(address, port));
            }
            if (endpoints.isEmpty()) return negative(DnsCodec.NXDOMAIN);
            if (query.type() == DnsCodec.TYPE_SRV) return srv(name, endpoints);
            List<InetAddress> addresses = new ArrayList<>();
            for (Endpoint endpoint : endpoints) ipAddress(endpoint.address()).ifPresent(addresses::add);
            return addresses(query, name, addresses);
        });
    }

    private CompletableFuture<DnsAnswer> node(DnsQuery query, String name, String node) {
        return read("/v1/catalog/nodes", Map.of(), body -> {
            for (JsonNode entry : body) {
                if (!entry.path("name").asText("").equalsIgnoreCase(node)) continue;
                List<InetAddress> addresses = new ArrayList<>();
                ipAddress(entry.path("address").asText("")).ifPresent(addresses::add);
                return addresses(query, name, addresses);
            }
            return negative(DnsCodec.NXDOMAIN);
        });
    }

    private CompletableFuture<DnsAnswer> read(String path, Map<String, String> scope,
                                              java.util.function.Function<JsonNode, DnsAnswer> answer) {
        return reads.read(path, Map.copyOf(scope)).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(read -> {
                    if (read.isEmpty()) {
                        LOG.debug("A DNS lookup's read of {} found no server to ask", path);
                        return DnsAnswer.of(DnsCodec.SERVFAIL);
                    }
                    int status = read.get().status();
                    if (status == 401 || status == 403) return DnsAnswer.of(DnsCodec.REFUSED);
                    if (status == 400 || status == 404) return negative(DnsCodec.NXDOMAIN);
                    if (status < 200 || status >= 300) {
                        LOG.debug("A DNS lookup's read of {} was answered {}", path, status);
                        return DnsAnswer.of(DnsCodec.SERVFAIL);
                    }
                    JsonNode body;
                    try {
                        body = JSON.readTree(read.get().body());
                    } catch (java.io.IOException notJson) {
                        LOG.debug("A DNS lookup's read of {} was not JSON: {}", path, notJson.getMessage());
                        return DnsAnswer.of(DnsCodec.SERVFAIL);
                    }
                    if (body == null || !body.isArray()) return DnsAnswer.of(DnsCodec.SERVFAIL);
                    return answer.apply(body);
                });
    }

    private static boolean hasTag(JsonNode tags, String tag) {
        for (JsonNode value : tags) {
            if (value.asText("").equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    /** The A and AAAA records of {@code found} that answer the type asked, shuffled; NODATA when none does. */
    private DnsAnswer addresses(DnsQuery query, String name, List<InetAddress> found) {
        boolean a = query.type() == DnsCodec.TYPE_A || query.type() == DnsCodec.TYPE_ANY;
        boolean aaaa = query.type() == DnsCodec.TYPE_AAAA || query.type() == DnsCodec.TYPE_ANY;
        List<DnsRecord> records = new ArrayList<>();
        for (InetAddress address : new LinkedHashSet<>(found)) {
            if (a && address instanceof Inet4Address v4) records.add(new DnsRecord(name, ttl, new DnsRecord.A(v4)));
            if (aaaa && address instanceof Inet6Address v6) {
                records.add(new DnsRecord(name, ttl, new DnsRecord.Aaaa(v6)));
            }
        }
        if (records.isEmpty()) return negative(DnsCodec.NOERROR);
        shuffle(records);
        return new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(), List.of());
    }

    private DnsAnswer srv(String name, List<Endpoint> endpoints) {
        List<DnsRecord> records = new ArrayList<>();
        Map<String, DnsRecord> additional = new LinkedHashMap<>();
        Set<Endpoint> seen = new LinkedHashSet<>();
        for (Endpoint endpoint : endpoints) {
            if (!seen.add(endpoint)) continue;
            Optional<InetAddress> ip = ipAddress(endpoint.address());
            String target;
            if (ip.isPresent()) {
                target = HexFormat.of().formatHex(ip.get().getAddress()) + ".addr." + domainName;
                additional.computeIfAbsent(target, owner -> new DnsRecord(owner, ttl,
                        ip.get() instanceof Inet4Address v4 ? new DnsRecord.A(v4)
                                : new DnsRecord.Aaaa((Inet6Address) ip.get())));
            } else {
                target = endpoint.address().endsWith(".") ? endpoint.address().substring(0,
                        endpoint.address().length() - 1) : endpoint.address();
            }
            records.add(new DnsRecord(name, ttl, new DnsRecord.Srv(1, 1, endpoint.port(), target)));
        }
        shuffle(records);
        return new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(), List.copyOf(additional.values()));
    }

    private void shuffle(List<DnsRecord> records) {
        for (int i = records.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            DnsRecord swapped = records.get(i);
            records.set(i, records.get(j));
            records.set(j, swapped);
        }
    }

    /** An IP address written as one; a host name is not looked up, and so is not one. */
    private static Optional<InetAddress> ipAddress(String text) {
        try {
            if (text.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                byte[] octets = new byte[4];
                String[] parts = text.split("\\.");
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet > 255) return Optional.empty();
                    octets[i] = (byte) octet;
                }
                return Optional.of(InetAddress.getByAddress(octets));
            }
            // With a colon the JDK reads an IPv6 literal, and never looks the text up.
            if (text.contains(":") && text.matches("[0-9A-Fa-f:.]+")) return Optional.of(InetAddress.getByName(text));
        } catch (UnknownHostException notAnAddress) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** The address an {@code addr} label spells: 8 hex digits for IPv4, 32 for IPv6. */
    private static Optional<InetAddress> addrAddress(String label) {
        if ((label.length() != 8 && label.length() != 32) || !label.matches("[0-9a-f]+")) return Optional.empty();
        try {
            return Optional.of(InetAddress.getByAddress(HexFormat.of().parseHex(label)));
        } catch (UnknownHostException impossible) {
            return Optional.empty();
        }
    }

    private DnsRecord soa() {
        long serial = (System.currentTimeMillis() / 1000) & 0xffff_ffffL;
        return new DnsRecord(domainName, ttl, new DnsRecord.Soa("ns." + domainName, "hostmaster." + domainName, serial,
                3600, 600, 86_400, ttl));
    }

    private DnsAnswer negative(int rcode) {
        return new DnsAnswer(rcode, true, List.of(), List.of(soa()), List.of());
    }

    private static CompletableFuture<DnsAnswer> done(DnsAnswer answer) {
        return CompletableFuture.completedFuture(answer);
    }

    private record Endpoint(String address, int port) {
    }
}
