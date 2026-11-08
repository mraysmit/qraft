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

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The session calls (client library list, decision 4), Consul's {@code Session()}. A TTL and a lock delay are sent as
 * durations the server reads, such as {@code 15000ms}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class Sessions {
    /** A Go duration as the server writes one: hours, minutes and seconds with a fraction, each optional. */
    private static final Pattern GO_DURATION = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?");

    private final QraftApi api;

    Sessions(QraftApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /** Creates a session with {@code options}; its ID. */
    public String create(SessionOptions options) {
        Objects.requireNonNull(options, "options");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Name", options.name());
        if (options.node() != null) request.put("Node", options.node());
        if (options.ttl() != null) request.put("TTL", options.ttl().toMillis() + "ms");
        if (options.lockDelay() != null) request.put("LockDelay", options.lockDelay().toMillis() + "ms");
        request.put("Behavior", options.behavior().apiName());
        byte[] body;
        try {
            body = QraftApi.JSON.writeValueAsBytes(request);
        } catch (IOException unwritable) {
            throw new IllegalArgumentException("The session's options cannot be written: " + options, unwritable);
        }
        QraftApi.Response answer = api.send("PUT", "/v1/session/create", Map.of(), body, null);
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        return parse(answer).path("ID").asText();
    }

    /** Renews the session's TTL; false when the session is gone. */
    public boolean renew(String id) {
        QraftApi.Response answer = api.send("PUT", "/v1/session/renew/" + QraftApi.encode(id(id)), Map.of(),
                new byte[0], null);
        if (answer.status() == 404 && QraftApi.refusal(answer).code().equals("session_not_found")) return false;
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        return true;
    }

    /** Destroys the session, releasing or deleting its keys by its behaviour. */
    public boolean destroy(String id) {
        QraftApi.Response answer = api.send("PUT", "/v1/session/destroy/" + QraftApi.encode(id(id)), Map.of(),
                new byte[0], null);
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        return Boolean.parseBoolean(answer.text().trim());
    }

    /** The session, or empty when it is gone. */
    public Optional<SessionInfo> info(String id) {
        QraftApi.Response answer = api.send("GET", "/v1/session/info/" + QraftApi.encode(id(id)), Map.of(), null,
                QueryOptions.DEFAULT);
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        JsonNode sessions = parse(answer);
        if (!sessions.isArray() || sessions.isEmpty()) return Optional.empty();
        JsonNode session = sessions.get(0);
        return Optional.of(new SessionInfo(session.path("ID").asText(), session.path("Name").asText(),
                session.path("Node").asText(), goDuration(session.path("TTL").asText("")),
                Duration.ofNanos(session.path("LockDelay").asLong()),
                SessionOptions.Behavior.valueOf(session.path("Behavior").asText("release").toUpperCase(Locale.ROOT)),
                session.path("CreateIndex").asLong()));
    }

    private static String id(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("A session ID is required");
        return id;
    }

    /** {@code text}, a Go duration such as {@code 1m30s}, or {@link Duration#ZERO} when it is empty. */
    static Duration goDuration(String text) {
        if (text.isEmpty()) return Duration.ZERO;
        Matcher parts = GO_DURATION.matcher(text);
        if (!parts.matches()) throw new IllegalArgumentException("Not a duration the server writes: " + text);
        Duration duration = Duration.ZERO;
        if (parts.group(1) != null) duration = duration.plusHours(Long.parseLong(parts.group(1)));
        if (parts.group(2) != null) duration = duration.plusMinutes(Long.parseLong(parts.group(2)));
        if (parts.group(3) != null) {
            duration = duration.plusNanos(new java.math.BigDecimal(parts.group(3)).movePointRight(9).longValueExact());
        }
        return duration;
    }

    private static JsonNode parse(QraftApi.Response answer) {
        try {
            return QraftApi.JSON.readTree(answer.body());
        } catch (IOException notJson) {
            throw new QraftApiException(answer.status(), "invalid_answer", "The answer is not JSON: " + answer.text());
        }
    }
}
