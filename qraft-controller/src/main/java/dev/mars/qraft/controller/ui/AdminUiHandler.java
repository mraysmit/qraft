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

package dev.mars.qraft.controller.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serves the administrative interface's files below its configured path on the server's HTTP listener.
 *
 * <p>Only the configured prefix is stripped before the lookup, and the prefix itself serves
 * {@code index.html}. A relative path with an empty, {@code .}, or {@code ..} segment, a backslash, or a
 * control character is refused, so a request can never leave the asset root. Only named files are served:
 * a path whose last segment has no extension is not found. Only {@code GET} and {@code HEAD} are answered.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public final class AdminUiHandler implements HttpHandler {
    private static final String INDEX = "index.html";
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private final String path;
    private final UiAssets assets;

    public AdminUiHandler(String path, UiAssets assets) {
        this.path = Objects.requireNonNull(path, "path");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (!head && !"GET".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Optional<String> relative = relativePath(exchange.getRequestURI().getPath());
            Optional<byte[]> asset = relative.isEmpty() ? Optional.empty() : assets.read(relative.get());
            if (asset.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = asset.get();
            exchange.getResponseHeaders().set("Content-Type", contentType(relative.get()));
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(body.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
        }
    }

    /** The asset path below the prefix, or empty when the request may not be served as a named file. */
    private Optional<String> relativePath(String requestPath) {
        if (requestPath == null || !requestPath.startsWith(path)) return Optional.empty();
        String relative = requestPath.substring(path.length());
        if (relative.isEmpty()) return Optional.of(INDEX);
        if (relative.indexOf('\\') >= 0 || relative.chars().anyMatch(Character::isISOControl)) return Optional.empty();
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return Optional.empty();
        }
        if (segments[segments.length - 1].lastIndexOf('.') <= 0) return Optional.empty();
        return Optional.of(relative);
    }

    private static String contentType(String relativePath) {
        String extension = relativePath.substring(relativePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
