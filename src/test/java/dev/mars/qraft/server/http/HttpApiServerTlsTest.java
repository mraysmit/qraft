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

package dev.mars.qraft.server.http;

import dev.mars.qraft.common.tls.ServerTls;
import dev.mars.qraft.common.tls.TlsMaterial;
import dev.mars.qraft.common.tls.TlsSettings;
import dev.mars.qraft.server.ui.AdminUiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests HTTPS on the server's HTTP listener (transport security list, decisions 2 and 3, Phase 2): with a required
 * client certificate, a caller with one from the cluster's CA is answered, and one with none, with one from another CA,
 * or speaking plain HTTP is refused; without the requirement, a caller with no certificate is answered.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class HttpApiServerTlsTest {
    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    static Path fixture(String name) {
        try {
            return Path.of(HttpApiServerTlsTest.class.getResource("/tls/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException("test TLS fixture " + name + " is missing", e);
        }
    }

    static TlsMaterial material(String ca, String cert, String key) {
        return TlsMaterial.load(new TlsSettings("test.tls", fixture(ca),
                Optional.ofNullable(cert).map(HttpApiServerTlsTest::fixture),
                Optional.ofNullable(key).map(HttpApiServerTlsTest::fixture), List.of("TLSv1.3", "TLSv1.2")));
    }

    private int start(boolean requireClientCertificate) throws Exception {
        server = new HttpApiServer(0, null, null, Clock.systemUTC(), AdminUiConfig.disabled(), null,
                HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, AclHttpSettings.disabled(),
                Optional.of(new ServerTls(material("ca.pem", "server.pem", "server.key"), requireClientCertificate)));
        server.start().get();
        return server.port();
    }

    private static HttpResponse<String> live(TlsMaterial caller, String scheme, int port) throws Exception {
        HttpClient client = HttpClient.newBuilder().sslContext(caller.clientContext())
                .sslParameters(caller.clientParameters(true)).connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + "/health/live"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aCallerWithACertificateFromTheCaIsAnswered() throws Exception {
        int port = start(true);

        assertEquals(200, live(material("ca.pem", "client.pem", "client.key"), "https", port).statusCode());
    }

    @Test
    void aCallerWithoutACertificateIsRefused() throws Exception {
        int port = start(true);

        assertThrows(IOException.class, () -> live(material("ca.pem", null, null), "https", port));
    }

    @Test
    void aCallerWithACertificateFromAnotherCaIsRefused() throws Exception {
        int port = start(true);

        assertThrows(IOException.class,
                () -> live(material("ca.pem", "other-client.pem", "other-client.key"), "https", port));
    }

    @Test
    void plainHttpIsNotServed() throws Exception {
        int port = start(true);

        assertThrows(IOException.class, () -> live(material("ca.pem", "client.pem", "client.key"), "http", port));
    }

    @Test
    void withoutTheRequirementACallerWithoutACertificateIsAnswered() throws Exception {
        int port = start(false);

        assertEquals(200, live(material("ca.pem", null, null), "https", port).statusCode());
    }
}
