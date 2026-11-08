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

package dev.mars.qraft.client.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A Java application's access to Qraft (client library list, decision 1), through its client's local API or a server.
 * Each call sends the token, if one is given, as {@code X-Qraft-Token}, and the tenant and namespace, if given, as
 * {@code X-Qraft-Tenant} and {@code X-Qraft-Namespace}. The calls are synchronous and safe from any thread; a refusal
 * throws {@link QraftApiException} (decision 2).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class QraftApi implements AutoCloseable {
    /** A client's local API at its default port, {@code client.httpPort}, where an application finds it by default. */
    public static final URI LOCAL = URI.create("http://127.0.0.1:8080");
    /** How long a server holds a blocking read that names no wait. */
    private static final Duration SERVER_DEFAULT_WAIT = Duration.ofMinutes(5);
    static final ObjectMapper JSON = new ObjectMapper();

    private final URI address;
    private final String token;
    private final String tenant;
    private final String namespace;
    private final Duration requestTimeout;
    private final HttpClient http;

    private QraftApi(Builder builder) {
        this.address = builder.address;
        this.token = builder.token;
        this.tenant = builder.tenant;
        this.namespace = builder.namespace;
        this.requestTimeout = builder.requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(builder.requestTimeout).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public KeyValue keyValue() {
        return new KeyValue(this);
    }

    public Sessions sessions() {
        return new Sessions(this);
    }

    @Override
    public void close() {
        http.close();
    }

    /** A server's answer: its status, its {@code X-Qraft-Index} (0 without one), and its body. */
    record Response(int status, long index, byte[] body) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Sends {@code method} to {@code path} with {@code query}, whose {@code null} values are flags, and {@code body};
     * a read's {@code options} add the index, the wait and the consistency.
     */
    Response send(String method, String path, Map<String, String> query, byte[] body, QueryOptions options) {
        Map<String, String> parameters = new LinkedHashMap<>(query);
        Duration timeout = requestTimeout;
        if (options != null) {
            switch (options.consistency()) {
                case CONSISTENT -> parameters.put("consistent", null);
                case STALE -> parameters.put("stale", null);
                case DEFAULT -> { }
            }
            if (options.waitIndex() > 0) {
                parameters.put("index", Long.toString(options.waitIndex()));
                Duration wait = options.waitTime().isZero() ? SERVER_DEFAULT_WAIT : options.waitTime();
                if (!options.waitTime().isZero()) parameters.put("wait", options.waitTime().toMillis() + "ms");
                // The server may answer a little after the wait, as Consul's jitter of a sixteenth allows.
                timeout = timeout.plus(wait).plus(wait.dividedBy(16));
            }
        }
        StringJoiner encoded = new StringJoiner("&");
        parameters.forEach((name, value) -> encoded.add(value == null ? name : name + "=" + encode(value)));
        String base = address.toString().endsWith("/") ? address.toString().substring(0, address.toString().length() - 1)
                : address.toString();
        URI uri = URI.create(base + path + (encoded.length() == 0 ? "" : "?" + encoded));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) request.header("X-Qraft-Token", token);
        if (tenant != null) request.header("X-Qraft-Tenant", tenant);
        if (namespace != null) request.header("X-Qraft-Namespace", namespace);
        HttpResponse<byte[]> answer;
        try {
            answer = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException unanswered) {
            throw new QraftApiException(method + " " + path + " was not answered: " + unanswered.getMessage(),
                    unanswered);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QraftApiException(method + " " + path + " was interrupted", interrupted);
        }
        long index = answer.headers().firstValue("X-Qraft-Index").map(Long::parseLong).orElse(0L);
        return new Response(answer.statusCode(), index, answer.body());
    }

    /** The refusal {@code response} is, with the server's code and message when it gave them. */
    static QraftApiException refusal(Response response) {
        String code = "http_" + response.status();
        String message = response.text();
        try {
            JsonNode error = JSON.readTree(response.body());
            if (error != null && error.hasNonNull("code")) {
                code = error.get("code").asText();
                message = error.path("message").asText(message);
            }
        } catch (IOException notJson) {
            // The body is not the server's error form; it is kept as the message.
        }
        return new QraftApiException(response.status(), code, message);
    }

    /** {@code text} encoded for a query value or a path segment, with a space as {@code %20}. */
    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static final class Builder {
        private URI address = LOCAL;
        private String token;
        private String tenant;
        private String namespace;
        private Duration requestTimeout = Duration.ofSeconds(10);

        private Builder() { }

        public Builder address(URI address) {
            this.address = Objects.requireNonNull(address, "address");
            return this;
        }

        public Builder token(String token) {
            this.token = Objects.requireNonNull(token, "token");
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = Objects.requireNonNull(tenant, "tenant");
            return this;
        }

        public Builder namespace(String namespace) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            return this;
        }

        /** How long a call waits for its answer beyond any wait it asks the server for. */
        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public QraftApi build() {
            return new QraftApi(this);
        }
    }
}
