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

package dev.mars.qraft.agent.health;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Issues one HTTP GET and classifies the status code; the response body is discarded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class HttpProbe implements Probe {
    private final HttpCheck check;
    private final HttpClient client;

    HttpProbe(HttpCheck check, HttpClient client) {
        this.check = Objects.requireNonNull(check, "check");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public CompletableFuture<Outcome> run() {
        CompletableFuture<HttpResponse<Void>> exchange;
        try {
            // The runner's scheduled timeout bounds the attempt and cancels this exchange.
            HttpRequest request = HttpRequest.newBuilder(check.url()).GET().build();
            exchange = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(failed(failure));
        }
        return Probe.linked(exchange, exchange.handle((response, failure) ->
                failure == null ? classify(response.statusCode()) : failed(failure)));
    }

    private static Outcome classify(int statusCode) {
        CheckStatus status;
        if (statusCode >= 200 && statusCode < 300) status = CheckStatus.PASSING;
        else if (statusCode == 429) status = CheckStatus.WARNING;
        else status = CheckStatus.CRITICAL;
        return new Outcome(status, "HTTP " + statusCode);
    }

    private static Outcome failed(Throwable failure) {
        return new Outcome(CheckStatus.CRITICAL, "HTTP GET failed: " + Probe.describe(failure));
    }
}
