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

import com.google.protobuf.ByteString;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import dev.mars.qraft.raft.grpc.ForwardRequest;
import dev.mars.qraft.raft.grpc.ForwardResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A write another server forwarded here, as an exchange the routes answer as they answer one from the listener (write
 * forwarding list, decision 2): the forwarded method, path, query, headers and body in, and the status, headers and
 * body the route writes captured for the answer.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class ForwardedExchange extends HttpExchange {
    private final ForwardRequest request;
    private final HttpContext context;
    private final InetSocketAddress local;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final Map<String, Object> attributes = new HashMap<>();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private InputStream in;
    private OutputStream out = captured;
    private int status = -1;

    ForwardedExchange(ForwardRequest request, HttpContext context, InetSocketAddress local) {
        this.request = Objects.requireNonNull(request, "request");
        this.context = Objects.requireNonNull(context, "context");
        this.local = local;
        this.uri = URI.create(request.getPathAndQuery());
        request.getHeadersMap().forEach(requestHeaders::set);
        this.in = new ByteArrayInputStream(request.getBody().toByteArray());
    }

    /** The answer the route wrote. */
    ForwardResponse response() {
        ForwardResponse.Builder answer = ForwardResponse.newBuilder().setStatus(status < 0 ? 500 : status)
                .setBody(ByteString.copyFrom(captured.toByteArray()));
        responseHeaders.forEach((name, values) -> {
            if (!values.isEmpty()) answer.putHeaders(name, String.join(", ", values));
        });
        return answer.build();
    }

    @Override
    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return request.getMethod();
    }

    @Override
    public HttpContext getHttpContext() {
        return context;
    }

    @Override
    public void close() {
        try {
            in.close();
            out.close();
        } catch (java.io.IOException ignored) {
            // In-memory streams do not fail to close.
        }
    }

    @Override
    public InputStream getRequestBody() {
        return in;
    }

    @Override
    public OutputStream getResponseBody() {
        return out;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
        this.status = rCode;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    @Override
    public int getResponseCode() {
        return status;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return local;
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (value == null) attributes.remove(name);
        else attributes.put(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        if (i != null) this.in = i;
        if (o != null) this.out = o;
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return null;
    }

    /** The headers a hop between servers does not carry. */
    static final List<String> HOP_BY_HOP = List.of("host", "connection", "content-length", "transfer-encoding",
            "keep-alive", "upgrade", "expect", "te", "trailer", "proxy-authorization", "proxy-connection", "date");
}
