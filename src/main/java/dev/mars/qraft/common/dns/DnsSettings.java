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
import dev.mars.qraft.common.config.JsonSettings;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The settings of a mode's DNS interface, {@code server.dns} or {@code client.dns} (DNS discovery list, decisions 1
 * and 3): it is off unless the section gives a port.
 *
 * @param host        the address to listen on
 * @param port        the port, UDP and TCP both; 0 lets the system pick one
 * @param domain      the domain answered, in lower case with no final dot
 * @param ttlSeconds  the time to live of every record and of a negative answer
 * @param onlyPassing whether an instance with a warning is left out
 * @param tokenFile   the file of the token a server reads under; a client reads under its default token
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public record DnsSettings(String host, int port, String domain, long ttlSeconds, boolean onlyPassing,
                          Optional<Path> tokenFile) {
    /** The domain answered when none is given. */
    public static final String DEFAULT_DOMAIN = "qraft";

    /**
     * The settings {@code dns} holds at {@code location}, such as {@code server.dns}; empty when it is absent.
     *
     * @param defaultHost    the host when none is given
     * @param allowTokenFile whether {@code tokenFile} may be given
     */
    public static Optional<DnsSettings> parse(JsonNode dns, String location, String defaultHost,
                                              boolean allowTokenFile) {
        if (dns == null || dns.isNull()) return Optional.empty();
        if (!dns.isObject()) throw new IllegalArgumentException(location + " must be an object");
        if (allowTokenFile) {
            JsonSettings.rejectUnknown(dns, location, "host", "port", "domain", "ttlSeconds", "onlyPassing",
                    "tokenFile");
        } else {
            JsonSettings.rejectUnknown(dns, location, "host", "port", "domain", "ttlSeconds", "onlyPassing");
        }
        if (!dns.has("port")) throw new IllegalArgumentException(location + ".port is required to serve DNS");
        int port = setting(location + ".port", () -> JsonSettings.requiredInt(dns, "port"));
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(location + ".port must be between 0 and 65535, got: " + port);
        }
        String host = text(dns, "host", location, defaultHost);
        String domainText = text(dns, "domain", location, DEFAULT_DOMAIN);
        String domain;
        try {
            domain = DnsResolver.normalisedDomain(domainText);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(location + ".domain must be one or more DNS labels: " + domainText);
        }
        long ttl = setting(location + ".ttlSeconds", () -> JsonSettings.optionalLong(dns, "ttlSeconds", 0));
        if (ttl < 0 || ttl > 0x7fff_ffffL) {
            throw new IllegalArgumentException(location + ".ttlSeconds must be between 0 and 2147483647, got: " + ttl);
        }
        boolean onlyPassing = setting(location + ".onlyPassing",
                () -> JsonSettings.optionalBoolean(dns, "onlyPassing", false));
        Optional<Path> tokenFile = dns.has("tokenFile")
                ? Optional.of(Path.of(text(dns, "tokenFile", location, null))) : Optional.empty();
        return Optional.of(new DnsSettings(host, port, domain, ttl, onlyPassing, tokenFile));
    }

    /** A text setting, which must not be blank; {@code fallback} when it is absent. */
    private static String text(JsonNode dns, String field, String location, String fallback) {
        JsonNode value = dns.get(field);
        if (value == null) return fallback;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(location + "." + field + " must be a non-blank string");
        }
        return value.textValue().strip();
    }

    /** Names the whole location of a setting that {@link JsonSettings} refuses by its field alone. */
    private static <T> T setting(String location, java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(location + ": " + invalid.getMessage());
        }
    }
}
