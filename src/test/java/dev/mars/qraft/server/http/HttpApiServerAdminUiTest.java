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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.server.config.SecurityProfile;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.server.ui.ClasspathUiAssets;
import dev.mars.qraft.server.ui.DirectoryUiAssets;
import dev.mars.qraft.server.ui.UiAssets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the administrative interface's route contract on the server's HTTP listener: it is registered at
 * its configured path only when enabled, serves named files from classpath resources or a development
 * directory, refuses to leave its asset root, answers only {@code GET} and {@code HEAD}, leaves every other
 * route untouched, and warns once at startup that it is unauthenticated.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class HttpApiServerAdminUiTest {
    private static final UiAssets FIXTURE = new ClasspathUiAssets(
            HttpApiServerAdminUiTest.class.getClassLoader(), "ui-fixture/");
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<script id=\"qraft-bootstrap\" type=\"application/json\" nonce=\"[^\"]+\">(.*?)</script>");

    @TempDir
    Path temporaryDirectory;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
        http.close();
    }

    @Test
    void anEnabledInterfaceServesItsIndexAndNamedAssetsFromTheClasspath() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> index = get("/ui/");
        HttpResponse<String> script = get("/ui/assets/app-3f2a1b.js");
        HttpResponse<String> style = get("/ui/assets/app-3f2a1b.css");

        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("Qraft fixture"));
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElse(""));
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(""));
        assertEquals("text/css; charset=utf-8", style.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void aMissingNamedAssetIsNotFound() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        assertEquals(404, get("/ui/assets/missing-000000.js").statusCode());
        assertEquals(404, get("/ui/assets").statusCode(), "a directory is never served");
    }

    @Test
    void aRequestCannotLeaveTheAssetRoot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><title>dev</title>");
        Files.writeString(temporaryDirectory.resolve("secret.txt"), "secret");
        start(new AdminUiConfig(true, "/ui/", Optional.of(root)), new DirectoryUiAssets(root));

        for (String path : List.of("/ui/../secret.txt", "/ui/%2e%2e/secret.txt", "/ui/..%2fsecret.txt",
                "/ui/assets/..%5c..%5csecret.txt")) {
            HttpResponse<String> response = get(path);
            assertTrue(response.statusCode() == 404 || response.statusCode() == 400, path + " -> " + response.statusCode());
            assertTrue(!response.body().contains("secret"), path);
        }
        assertEquals(200, get("/ui/").statusCode(), "the development directory serves its own index");
    }

    @Test
    void onlyGetAndHeadAreAnswered() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(uri("/ui/")).timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri("/ui/")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, head.statusCode());
        assertEquals(405, post.statusCode());
    }

    @Test
    void aDisabledInterfaceRegistersNoRouteAndOtherRoutesAreUntouched() throws Exception {
        start(new AdminUiConfig(false, "/ui/", Optional.empty()), FIXTURE);

        assertEquals(404, get("/ui/").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void anEnabledInterfaceLeavesTheApiAndHealthRoutesAlone() throws Exception {
        start(new AdminUiConfig(true, "/console/", Optional.empty()), FIXTURE);

        assertEquals(200, get("/console/").statusCode());
        assertEquals(404, get("/ui/").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void anEnabledInterfaceWarnsOnceAtStartupThatItIsUnauthenticated() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpApiServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.stream().filter(event ->
                event.getFormattedMessage().contains("/ui/")
                        && event.getFormattedMessage().contains("unauthenticated")).count());
    }

    @Test
    void withAclsEnabledTheInterfacesFilesNeedNoToken() throws Exception {
        // ACL list, proposal 11: the static files need no token; the interface's API calls carry one (UI-5).
        server = new HttpApiServer(0, null, null, Clock.systemUTC(), new AdminUiConfig(true, "/ui/", Optional.empty()),
                FIXTURE, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, temporaryDirectory,
                        dev.mars.qraft.common.acl.AclDefaultPolicy.DENY));
        server.start().get(10, TimeUnit.SECONDS);

        assertEquals(200, get("/ui/").statusCode());
        assertEquals(200, get("/ui/assets/app-3f2a1b.js").statusCode());
    }

    @Test
    void theIndexCarriesThePublicSettingsAndAFreshNonceThatThePolicyNames() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> first = get("/ui/");
        HttpResponse<String> second = get("/ui/");

        JsonNode bootstrap = bootstrap(first.body());
        assertEquals("/", bootstrap.path("apiBasePath").asText());
        assertEquals("/ui/", bootstrap.path("uiPath").asText());
        assertTrue(bootstrap.path("features").isObject());
        assertEquals("none", bootstrap.path("authMode").asText());
        assertEquals("development", bootstrap.path("securityProfile").asText());
        String nonce = bootstrap.path("cspNonce").asText();
        assertTrue(nonce.matches("[A-Za-z0-9+/]{22,}={0,2}"), "a base64 nonce of at least 128 bits: " + nonce);
        assertTrue(first.body().contains("nonce=\"" + nonce + "\""), "the bootstrap element carries the nonce");
        String policy = first.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(policy.contains("style-src 'self' 'nonce-" + nonce + "'"), policy);
        assertTrue(first.body().contains("Qraft fixture"), "the rest of the template is kept");
        assertNotEquals(nonce, bootstrap(second.body()).path("cspNonce").asText(), "each request gets its own");
    }

    @Test
    void withAclsEnabledTheBootstrapAsksForATokenNamesTheProfileAndHoldsNoSecret() throws Exception {
        server = new HttpApiServer(0, null, null, Clock.systemUTC(), new AdminUiConfig(true, "/ui/", Optional.empty()),
                FIXTURE, HttpApiServer.DEFAULT_RAFT_TIMEOUT, null, new AclHttpSettings(true, temporaryDirectory,
                        dev.mars.qraft.common.acl.AclDefaultPolicy.DENY, "management-secret-hash"),
                Optional.empty(), SecurityProfile.PRODUCTION);
        server.start().get(10, TimeUnit.SECONDS);

        HttpResponse<String> index = get("/ui/");

        JsonNode bootstrap = bootstrap(index.body());
        assertEquals("token", bootstrap.path("authMode").asText());
        assertEquals("production", bootstrap.path("securityProfile").asText());
        assertTrue(!index.body().contains("management-secret-hash"), "no secret reaches the page");
    }

    @Test
    void contentHashedAssetsAreImmutableAndTheIndexAndManifestAreRevalidated() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> script = get("/ui/assets/app-3f2a1b.js");
        HttpResponse<String> index = get("/ui/");
        HttpResponse<String> manifest = get("/ui/asset-manifest.json");

        assertEquals("public, max-age=31536000, immutable", script.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("no-store", index.headers().firstValue("Cache-Control").orElse(""),
                "the index carries a per-request nonce, so it is never stored");
        assertEquals(200, manifest.statusCode());
        assertEquals("application/json", manifest.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-cache", manifest.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void everyResponseIsNosniffAndThePolicyIsRestrictive() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        for (String path : List.of("/ui/", "/ui/assets/app-3f2a1b.js", "/ui/assets/app-3f2a1b.css",
                "/ui/asset-manifest.json", "/ui/assets/missing-000000.js")) {
            HttpResponse<String> response = get(path);
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""), path);
            assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElse(""), path);
            assertTrue(response.headers().firstValue("Content-Security-Policy").isPresent(), path);
        }
        String policy = get("/ui/").headers().firstValue("Content-Security-Policy").orElse("");
        for (String directive : List.of("default-src 'none'", "script-src 'self'", "connect-src 'self'",
                "img-src 'self' data:", "font-src 'self'", "base-uri 'self'", "form-action 'none'",
                "frame-ancestors 'none'")) {
            assertTrue(policy.contains(directive), directive + " in " + policy);
        }
        assertTrue(!policy.contains("unsafe-inline") && !policy.contains("unsafe-eval"), policy);
    }

    @Test
    void anUnknownExtensionlessPathFallsBackToTheIndexForClientSideRouting() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> route = get("/ui/services/web");
        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(uri("/ui/nodes")).timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());

        assertEquals(200, route.statusCode());
        assertEquals("text/html; charset=utf-8", route.headers().firstValue("Content-Type").orElse(""));
        assertEquals("/ui/", bootstrap(route.body()).path("uiPath").asText(), "the fallback is the rendered index");
        assertEquals("no-store", route.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(200, head.statusCode());
        for (String path : List.of("/ui/assets/missing-000000.js", "/ui/missing.png", "/ui/assets",
                "/ui/assets/chunk", "/ui/services/../../secret")) {
            assertEquals(404, get(path).statusCode(), path + " never falls back");
        }
        HttpResponse<String> outside = get("/raftx/status");
        assertEquals(404, outside.statusCode());
        assertTrue(!outside.body().contains("Qraft fixture"), "a path outside the interface never falls back");
    }

    @Test
    void theIndexNamesTheInterfacesPathAsItsBaseSoItsAssetsResolveFromAnyRoute() throws Exception {
        // The build links its assets relative to the page, since the path is configured; from /console/services/web
        // they must still resolve below /console/, so the base comes before anything the page links.
        start(new AdminUiConfig(true, "/console/", Optional.empty()), FIXTURE);

        String html = get("/console/services/web").body();

        int base = html.indexOf("<base href=\"/console/\">");
        assertTrue(base > 0, "no base element in " + html);
        assertTrue(base < html.indexOf("<title>"), "the base precedes the rest of the head: " + html);
    }

    @Test
    void aTemplateWithoutAHeadKeepsItsDoctypeFirst() throws Exception {
        // Anything before <!doctype html> puts the browser into quirks mode, so the base and the bootstrap go after it.
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(root.resolve("index.html"), "<!DOCTYPE html><title>dev</title><div id=\"root\"></div>");
        start(new AdminUiConfig(true, "/ui/", Optional.of(root)), new DirectoryUiAssets(root));

        String html = get("/ui/").body();

        assertTrue(html.startsWith("<!DOCTYPE html><base href=\"/ui/\">"), html);
        assertTrue(html.indexOf("id=\"qraft-bootstrap\"") < html.indexOf("<title>"), html);
        assertEquals("/ui/", bootstrap(html).path("uiPath").asText());
    }

    @Test
    void aTemplateWithNeitherHeadNorDoctypeGetsBothAtItsStart() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(root.resolve("index.html"), "<header>dev</header>");
        start(new AdminUiConfig(true, "/ui/", Optional.of(root)), new DirectoryUiAssets(root));

        String html = get("/ui/").body();

        assertTrue(html.startsWith("<base href=\"/ui/\"><script id=\"qraft-bootstrap\""), html);
        assertTrue(html.endsWith("</script><header>dev</header>"), "a header element is not a head: " + html);
    }

    @Test
    void theIndexRequestedByNameIsRenderedLikeThePrefix() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> index = get("/ui/index.html");

        assertEquals(200, index.statusCode());
        assertEquals("/ui/", bootstrap(index.body()).path("uiPath").asText());
        assertEquals("no-store", index.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void aRefusedMethodNamesTheAllowedOnesAndKeepsTheSecurityHeaders() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> delete = http.send(HttpRequest.newBuilder(uri("/ui/assets/app-3f2a1b.js"))
                .timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(405, delete.statusCode());
        assertEquals("GET, HEAD", delete.headers().firstValue("Allow").orElse(""));
        assertEquals("nosniff", delete.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(delete.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertEquals("", delete.body());
    }

    @Test
    void headGivesTheLengthAndHeadersOfGetWithoutTheBody() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> get = get("/ui/assets/app-3f2a1b.js");
        HttpResponse<String> head = http.send(HttpRequest.newBuilder(uri("/ui/assets/app-3f2a1b.js"))
                .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(String.valueOf(get.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                head.headers().firstValue("Content-Length").orElse(""));
        assertEquals(get.headers().firstValue("Content-Type"), head.headers().firstValue("Content-Type"));
        assertEquals(get.headers().firstValue("Cache-Control"), head.headers().firstValue("Cache-Control"));
    }

    @Test
    void aFileOfAnUnknownTypeIsServedAsBytes() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><head></head>");
        Files.write(root.resolve("data.bin"), new byte[] {1, 2, 3});
        start(new AdminUiConfig(true, "/ui/", Optional.of(root)), new DirectoryUiAssets(root));

        HttpResponse<String> file = get("/ui/data.bin");

        assertEquals(200, file.statusCode());
        assertEquals("application/octet-stream", file.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void withoutAnIndexNothingFallsBack() throws Exception {
        // A development directory without an index is refused at startup (AdminUiConfig); assets on the classpath
        // are not checked, so a jar packaged without the index must answer not found rather than fail.
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist").resolve("assets"));
        Files.writeString(root.resolve("app-000000.js"), "export {};");
        Path dist = root.getParent();
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), new DirectoryUiAssets(dist));

        for (String path : List.of("/ui/", "/ui/index.html", "/ui/services/web")) {
            assertEquals(404, get(path).statusCode(), path);
        }
        assertEquals(200, get("/ui/assets/app-000000.js").statusCode(), "the assets are still served");
    }

    private static JsonNode bootstrap(String html) throws Exception {
        Matcher element = BOOTSTRAP.matcher(html);
        assertTrue(element.find(), "no bootstrap element in " + html);
        return new ObjectMapper().readTree(element.group(1));
    }

    private void start(AdminUiConfig ui, UiAssets assets) throws Exception {
        server = new HttpApiServer(0, null, null, Clock.systemUTC(), ui, assets);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
