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

package dev.mars.qraft.server.acl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.state.acl.AuthMethod;
import dev.mars.qraft.state.acl.KubernetesConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Map;

/**
 * Reviews a login's service account token with the Kubernetes API (Kubernetes auth method list, decisions 3 and 4), as
 * Consul's {@code kubernetes} auth method does: {@code POST {host}/apis/authentication.k8s.io/v1/tokenreviews} under the
 * method's reviewer token, over HTTPS with the method's CA as the only one trusted. An authenticated
 * {@code system:serviceaccount:<namespace>:<name>} answers {@code serviceaccount.namespace}, {@code serviceaccount.name}
 * and {@code serviceaccount.uid}; a token the review does not authenticate, or that is not a service account's, is
 * refused with {@link IllegalArgumentException}; and an API that cannot be reached, is not trusted, or does not answer
 * a review is {@link Unavailable}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KubernetesTokenReviewer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String REVIEWS = "/apis/authentication.k8s.io/v1/tokenreviews";
    private static final String SERVICE_ACCOUNT = "system:serviceaccount:";

    private KubernetesTokenReviewer() { }

    /** The Kubernetes API could not review the token; the login may be tried again. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /** What the review says of {@code bearerToken}, a service account token, for {@code method}. */
    public static JwtValidator.Mapped review(String bearerToken, AuthMethod method) {
        if (!(method.config() instanceof KubernetesConfig config)) {
            throw new IllegalArgumentException("The auth method " + method.name() + " is not a kubernetes method");
        }
        if (bearerToken == null || bearerToken.isBlank()) throw new IllegalArgumentException("A bearer token is required");
        String host = config.host().endsWith("/") ? config.host().substring(0, config.host().length() - 1)
                : config.host();
        byte[] request;
        try {
            request = JSON.writeValueAsBytes(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "TokenReview",
                    "spec", Map.of("token", bearerToken)));
        } catch (IOException unwritable) {
            throw new IllegalArgumentException("The token review cannot be written", unwritable);
        }
        HttpResponse<byte[]> answer;
        try (HttpClient http = HttpClient.newBuilder().sslContext(trusting(config.caCertPem()))
                .connectTimeout(TIMEOUT).build()) {
            answer = http.send(HttpRequest.newBuilder(URI.create(host + REVIEWS)).timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + config.serviceAccountJwt())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(request)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException unreachable) {
            throw new Unavailable("The Kubernetes API at " + config.host() + " could not be reached: "
                    + unreachable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Unavailable("The token review was interrupted");
        }
        if (answer.statusCode() != 200 && answer.statusCode() != 201) {
            throw new Unavailable("The Kubernetes API at " + config.host() + " answered the token review with "
                    + answer.statusCode());
        }
        JsonNode status;
        try {
            status = JSON.readTree(answer.body()).path("status");
        } catch (IOException notJson) {
            throw new Unavailable("The Kubernetes API at " + config.host() + " answered no token review");
        }
        if (!status.path("authenticated").asBoolean(false)) {
            throw new IllegalArgumentException("The token is not authenticated: "
                    + status.path("error").asText("no reason given"));
        }
        String username = status.path("user").path("username").asText("");
        String[] parts = username.startsWith(SERVICE_ACCOUNT)
                ? username.substring(SERVICE_ACCOUNT.length()).split(":", -1) : new String[0];
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("The token is not a service account's: " + username);
        }
        return new JwtValidator.Mapped(Map.of("serviceaccount.namespace", parts[0], "serviceaccount.name", parts[1],
                "serviceaccount.uid", status.path("user").path("uid").asText("")), Map.of());
    }

    /** A context that trusts the certificates in {@code caCertPem} and no others. */
    private static SSLContext trusting(String caCertPem) {
        try {
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            int index = 0;
            for (Certificate certificate : java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(caCertPem.getBytes(StandardCharsets.UTF_8)))) {
                trusted.setCertificateEntry("ca-" + index++, certificate);
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException unusable) {
            throw new Unavailable("The kubernetes auth method's caCert cannot be used: " + unusable.getMessage());
        }
    }
}
