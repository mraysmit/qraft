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

package dev.mars.qraft.common.tls;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mars.qraft.common.config.JsonSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The TLS material a {@code tls} object names (transport security list, decision 1): Consul's {@code ca_file},
 * {@code cert_file}, {@code key_file} and {@code tls_min_version}, as {@code caFile}, {@code certFile},
 * {@code keyFile} and {@code minVersion}. TLS 1.2 is the lowest version allowed, as in Consul.
 *
 * @param location  where the object is in its document, such as {@code client.tls}, for messages
 * @param caFile    the PEM bundle of the CA certificates that peers' certificates must chain to
 * @param certFile  the PEM certificate chain this side presents, if any
 * @param keyFile   the PKCS #8 PEM private key of that certificate, if any
 * @param protocols the TLS versions allowed, newest first
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public record TlsSettings(String location, Path caFile, Optional<Path> certFile, Optional<Path> keyFile,
                          List<String> protocols) {
    private static final String[] MATERIAL_KEYS = {"caFile", "certFile", "keyFile", "minVersion"};

    public TlsSettings {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(caFile, "caFile");
        Objects.requireNonNull(certFile, "certFile");
        Objects.requireNonNull(keyFile, "keyFile");
        protocols = List.copyOf(protocols);
        if (protocols.isEmpty()) throw new IllegalArgumentException("at least one TLS version is required");
        if (certFile.isPresent() != keyFile.isPresent()) {
            throw new IllegalArgumentException(location + ".certFile and " + location + ".keyFile must be set together");
        }
    }

    /**
     * The settings of {@code tls}, or none when it is absent. {@code callerKeys} are the other keys the caller
     * reads from the same object, such as a server's {@code https}; any key outside both is refused.
     */
    public static Optional<TlsSettings> parse(JsonNode tls, String location, String... callerKeys) {
        if (tls == null || tls.isNull()) return Optional.empty();
        if (!tls.isObject()) throw new IllegalArgumentException(location + " must be an object");
        JsonSettings.rejectUnknown(tls, location,
                Stream.concat(Stream.of(MATERIAL_KEYS), Stream.of(callerKeys)).toArray(String[]::new));
        Path caFile = file(tls, location, "caFile")
                .orElseThrow(() -> new IllegalArgumentException(location + ".caFile is required"));
        Optional<Path> certFile = file(tls, location, "certFile");
        Optional<Path> keyFile = file(tls, location, "keyFile");
        JsonNode version = tls.get("minVersion");
        String minVersion = version == null || version.isNull() ? "TLSv1.2" : version.asText();
        List<String> protocols = switch (minVersion) {
            case "TLSv1.2" -> List.of("TLSv1.3", "TLSv1.2");
            case "TLSv1.3" -> List.of("TLSv1.3");
            default -> throw new IllegalArgumentException(location + ".minVersion must be TLSv1.2 or TLSv1.3");
        };
        return Optional.of(new TlsSettings(location, caFile, certFile, keyFile, protocols));
    }

    private static Optional<Path> file(JsonNode tls, String location, String field) {
        JsonNode value = tls.get(field);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual()) throw new IllegalArgumentException(location + "." + field + " must be a string");
        if (value.asText().isBlank()) throw new IllegalArgumentException(location + "." + field + " must not be blank");
        return Optional.of(Path.of(value.asText()));
    }
}
