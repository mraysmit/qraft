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

import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The HTTP client of a command line, with TLS when it is given a CA (transport security list, Phase 4): Consul's
 * {@code -ca-file}, {@code -client-cert} and {@code -client-key}, as {@code --ca-file}, {@code --client-cert} and
 * {@code --client-key}. Consul also reads {@code CONSUL_CACERT} and its like; Qraft reads no environment variable,
 * as everywhere (transport security list, decision 9).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
final class CommandLineTls {
    /** The options every command that calls a server accepts. */
    static final Set<String> OPTIONS = Set.of("--ca-file", "--client-cert", "--client-key");
    private static final String LOCATION = "command line";

    private CommandLineTls() { }

    /**
     * The client for {@code option}'s values; refused, as misuse, for a certificate without its key or without a CA,
     * or for files that cannot be used.
     */
    static HttpClient client(Function<String, String> option) {
        String ca = option.apply("--ca-file");
        String cert = option.apply("--client-cert");
        String key = option.apply("--client-key");
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if ((cert == null) != (key == null)) {
            throw new IllegalArgumentException("--client-cert and --client-key must be given together");
        }
        if (ca == null) {
            if (cert != null) throw new IllegalArgumentException("--client-cert and --client-key need --ca-file");
            return builder.build();
        }
        TlsMaterial material;
        try {
            material = TlsMaterial.load(new TlsSettings(LOCATION, Path.of(ca), Optional.ofNullable(cert).map(Path::of),
                    Optional.ofNullable(key).map(Path::of), List.of("TLSv1.3", "TLSv1.2")));
        } catch (IllegalStateException unusable) {
            throw new IllegalArgumentException(unusable.getMessage()
                    .replace(LOCATION + ".caFile", "--ca-file")
                    .replace(LOCATION + ".certFile", "--client-cert")
                    .replace(LOCATION + ".keyFile", "--client-key"));
        }
        return builder.sslContext(material.clientContext()).sslParameters(material.clientParameters(true)).build();
    }
}
