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

package dev.mars.qraft.server.ui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Serves the administrative interface's files below its configured path on the server's HTTP listener (design
 * section 12.4.1).
 *
 * <p>Only the configured prefix is stripped before the lookup. A relative path with an empty, {@code .}, or
 * {@code ..} segment, a backslash, or a control character is refused, so a request can never leave the asset root.
 * Only {@code GET} and {@code HEAD} are answered.
 *
 * <p>The prefix itself, {@code index.html}, and any other path whose last segment, as sent, has no extension, so that
 * the interface can route on the client, are answered with {@code index.html} rendered for the request: the path becomes
 * the page's {@code <base>}, the {@link UiBootstrap} object is added before {@code </head>} in a
 * {@code <script type="application/json">} element that carries a fresh nonce, and the response is never stored. Paths below {@code assets/}, where the build puts its
 * content-hashed files, never fall back: a missing one is not found, and a present one is cached as immutable. Other
 * named files, such as the asset manifest, must be revalidated.
 *
 * <p>Every response is marked {@code nosniff}, sends no referrer, and carries a content security policy that allows
 * scripts, styles, images, fonts and requests only from the server's own origin, inline styles only with the
 * request's nonce, which Ant Design's runtime styles carry, and a base only on the same origin.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.2
 */
public final class AdminUiHandler implements HttpHandler {
    private static final String INDEX = "index.html";
    private static final String HASHED_ROOT = "assets";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
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
    private final UiBootstrap bootstrap;
    private final SecureRandom random = new SecureRandom();

    public AdminUiHandler(String path, UiAssets assets, UiBootstrap bootstrap) {
        this.path = Objects.requireNonNull(path, "path");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String nonce = nonce();
            Headers headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", policy(nonce));
            String method = exchange.getRequestMethod();
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (!head && !"GET".equalsIgnoreCase(method)) {
                headers.set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Optional<String> relative = relativePath(exchange.getRequestURI());
            Optional<byte[]> body = relative.isEmpty() ? Optional.empty() : read(relative.get(), nonce);
            if (body.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            headers.set("Content-Type", contentType(relative.get()));
            headers.set("Cache-Control", cacheControl(relative.get()));
            send(exchange, head, body.get());
        }
    }

    /**
     * The file a request is answered with, below the prefix: {@code index.html} for the prefix and for a path the
     * interface routes on the client; or empty when the request may not be served. The decoded path is checked and
     * looked up; whether its last segment names a file is read from the path as sent, so a view whose name holds a
     * dot, sent as {@code %2E}, is still a view.
     */
    private Optional<String> relativePath(java.net.URI uri) {
        String requestPath = uri.getPath();
        if (requestPath == null || !requestPath.startsWith(path)) return Optional.empty();
        String relative = requestPath.substring(path.length());
        if (relative.isEmpty()) return Optional.of(INDEX);
        if (relative.indexOf('\\') >= 0 || relative.chars().anyMatch(Character::isISOControl)) return Optional.empty();
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        if (rawPath.lastIndexOf('.') > rawPath.lastIndexOf('/') + 1) return Optional.of(relative);
        return segments[0].equals(HASHED_ROOT) ? Optional.empty() : Optional.of(INDEX);
    }

    private Optional<byte[]> read(String relative, String nonce) throws IOException {
        return relative.equals(INDEX) ? renderedIndex(nonce) : assets.read(relative);
    }

    /**
     * {@code index.html} rendered for a request. The interface's path becomes the page's base, first in the head: the
     * build links its assets relative to the page, since the path is configured, and a client-side route such as
     * {@code services/web} must still find them below the path. The bootstrap object goes last in the head. A template
     * without a head gets both at its start, after its doctype if it has one.
     */
    private Optional<byte[]> renderedIndex(String nonce) throws IOException {
        Optional<byte[]> template = assets.read(INDEX);
        if (template.isEmpty()) return Optional.empty();
        String html = new String(template.get(), StandardCharsets.UTF_8);
        String lower = html.toLowerCase(Locale.ROOT);
        String base = "<base href=\"" + escapeAttribute(path) + "\">";
        String element = "<script id=\"qraft-bootstrap\" type=\"application/json\" nonce=\"" + nonce + "\">"
                + bootstrap.toJson(nonce) + "</script>";
        int headStart = lower.indexOf("<head");
        int headOpenEnd = headStart < 0 ? -1 : lower.indexOf('>', headStart);
        int headEnd = lower.indexOf("</head>");
        if (headOpenEnd < 0 || headEnd < headOpenEnd) {
            int start = afterDoctype(html, lower);
            return Optional.of((html.substring(0, start) + base + element + html.substring(start))
                    .getBytes(StandardCharsets.UTF_8));
        }
        String rendered = html.substring(0, headOpenEnd + 1) + base + html.substring(headOpenEnd + 1, headEnd)
                + element + html.substring(headEnd);
        return Optional.of(rendered.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Where a template without a head may take additions: after a leading doctype, since anything before it puts the
     * browser into quirks mode; otherwise at the start.
     */
    private static int afterDoctype(String html, String lower) {
        int first = 0;
        while (first < html.length() && Character.isWhitespace(html.charAt(first))) first++;
        if (!lower.startsWith("<!doctype", first)) return 0;
        int end = lower.indexOf('>', first);
        return end < 0 ? 0 : end + 1;
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void send(HttpExchange exchange, boolean head, byte[] body) throws IOException {
        if (head) {
            exchange.getResponseHeaders().set("Content-Length", Long.toString(body.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }

    private String nonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String policy(String nonce) {
        return "default-src 'none'; script-src 'self'; style-src 'self' 'nonce-" + nonce + "'; "
                + "img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; "
                + "base-uri 'self'; form-action 'none'; frame-ancestors 'none'";
    }

    private static String cacheControl(String relativePath) {
        if (relativePath.equals(INDEX)) return "no-store";
        return relativePath.startsWith(HASHED_ROOT + "/") ? IMMUTABLE : "no-cache";
    }

    private static String contentType(String relativePath) {
        String extension = relativePath.substring(relativePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }
}
