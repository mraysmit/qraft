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

import dev.mars.qraft.common.async.RaftExecutorsFixture;
import dev.mars.qraft.raft.PeerlessTransportFixture;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the server's request metrics (observability list, Phase 2, decision 3): every request is counted by its route's
 * registered prefix, not its path, by method and by status, and timed by route and method; a method the server does not
 * know is counted as {@code OTHER}, so no label grows without bound.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class HttpApiServerRequestMetricsTest {
    private final InMemoryMetricReader reader = InMemoryMetricReader.create();
    private final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
    private RaftExecutorsFixture runtime;
    private RaftNode node;
    private HttpApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() throws Exception {
        try {
            if (server != null) server.close();
            if (node != null) node.stop().get(10, TimeUnit.SECONDS);
        } finally {
            if (runtime != null) runtime.close().get(10, TimeUnit.SECONDS);
            http.close();
            provider.close();
        }
    }

    private void start() throws Exception {
        runtime = RaftExecutorsFixture.create();
        QraftStateStore store = new QraftStateStore();
        node = RaftNode.builder().stateLoop(runtime.stateLoop()).blockingTasks(runtime.blockingTasks())
                .nodeId("metrics-node").clusterNodes(Set.of("metrics-node")).transport(new PeerlessTransportFixture())
                .stateMachine(store).commandCodec(new ProtobufRaftCommandCodec()).mode(RaftNodeMode.volatileMode())
                .electionTimeout(25).heartbeatInterval(10_000).build();
        node.start().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        server = new HttpApiServer(0, node, store, Clock.systemUTC(), AdminUiConfig.disabled(), null);
        server.useMeter(provider.get(HttpApiServer.METER_NAME));
        server.start().get(10, TimeUnit.SECONDS);
    }

    private int send(String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void requestsAreCountedByRouteMethodAndStatusAndTimedByRouteAndMethod() throws Exception {
        start();
        assertEquals(404, send("GET", "/v1/kv/app/absent", null));
        assertEquals(200, send("PUT", "/v1/kv/app/one", "1"));
        assertEquals(200, send("PUT", "/v1/kv/app/two", "2"));
        assertEquals(200, send("GET", "/health/live", null));
        assertEquals(405, send("BREW", "/v1/kv/app/one", null));

        // A request is counted once its handler returns, which is after the client has its answer, so the test waits
        // for the counts rather than reading them as the last answer arrives.
        awaitCount(() -> timed("/v1/kv", "PUT") + requests("/v1/kv", "OTHER", "405") + requests("/health/live", "GET",
                "200") + requests("/v1/kv", "GET", "404"), 5);
        assertEquals(1, requests("/v1/kv", "GET", "404"), "counted by its route, not its path");
        assertEquals(2, requests("/v1/kv", "PUT", "200"));
        assertEquals(1, requests("/health/live", "GET", "200"));
        assertEquals(1, requests("/v1/kv", "OTHER", "405"), "an unknown method is OTHER");
        assertEquals(2, timed("/v1/kv", "PUT"));
        assertTrue(metrics().stream().noneMatch(metric -> metric.getData().getPoints().stream()
                .anyMatch(point -> point.getAttributes().asMap().values().stream()
                        .anyMatch(value -> value.toString().contains("app/")))), "no label holds a key");
    }

    private void awaitCount(java.util.function.LongSupplier count, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count.getAsLong() < expected && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(expected, count.getAsLong(), () -> "every request was counted; recorded: " + recorded());
    }

    /** Every request and duration point recorded, for a failure to show what was counted. */
    private String recorded() {
        StringBuilder points = new StringBuilder();
        for (MetricData metric : metrics()) {
            if (!metric.getName().startsWith("qraft.http.")) continue;
            metric.getData().getPoints().forEach(point -> points.append(metric.getName()).append(' ')
                    .append(point.getAttributes()).append("; "));
        }
        return points.toString();
    }

    private Collection<MetricData> metrics() {
        return reader.collectAllMetrics();
    }

    private long requests(String route, String method, String status) {
        Attributes attributes = Attributes.of(AttributeKey.stringKey("route"), route,
                AttributeKey.stringKey("method"), method, AttributeKey.stringKey("status"), status);
        return metrics().stream().filter(metric -> metric.getName().equals("qraft.http.requests"))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .filter(point -> point.getAttributes().equals(attributes)).mapToLong(LongPointData::getValue).sum();
    }

    private long timed(String route, String method) {
        Attributes attributes = Attributes.of(AttributeKey.stringKey("route"), route,
                AttributeKey.stringKey("method"), method);
        return metrics().stream().filter(metric -> metric.getName().equals("qraft.http.request.duration"))
                .flatMap(metric -> metric.getHistogramData().getPoints().stream())
                .filter(point -> point.getAttributes().equals(attributes)).mapToLong(HistogramPointData::getCount).sum();
    }
}
