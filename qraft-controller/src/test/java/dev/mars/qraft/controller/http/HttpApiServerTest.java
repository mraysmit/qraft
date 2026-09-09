package dev.mars.qraft.controller.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpApiServerTest {
    private HttpApiServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void exposesHealthStatusAndInfoEndpoints() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> live = request(client, "/health/live", "GET");
        HttpResponse<String> ready = request(client, "/health/ready", "GET");
        HttpResponse<String> status = request(client, "/status", "GET");
        HttpResponse<String> info = request(client, "/api/v1/info", "GET");

        assertEquals(200, live.statusCode());
        assertEquals(200, ready.statusCode());
        assertEquals(200, status.statusCode());
        assertEquals(200, info.statusCode());
        assertTrue(live.body().contains("alive"));
        assertTrue(info.body().contains("version"));
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        server = new HttpApiServer(0);
        server.start().join();
        HttpResponse<String> response = request(HttpClient.newHttpClient(), "/health/live", "POST");
        assertEquals(405, response.statusCode());
    }

    private HttpResponse<String> request(HttpClient client, String path, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
