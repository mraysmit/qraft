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

package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code qraft operator raft} commands, after Consul's {@code consul operator raft}. They call a server's
 * HTTP API, and any server will do: it forwards a removal to the leader.
 * <ul>
 *   <li>{@code list-peers} prints each server in the Raft configuration: name, server ID, address, whether it
 *       leads, and whether it votes;</li>
 *   <li>{@code remove-peer --id <server-id> | --name <name>} removes a server. It needs the operator token.</li>
 * </ul>
 * The server's HTTP address is {@code --http-addr}, else {@code 127.0.0.1:8080}. The token is {@code --token},
 * or the contents of {@code --token-file}, which keeps it out of the shell's history and the process list. As
 * everywhere in Qraft, nothing is read from environment variables. Exit codes: 0 done, 1 refused or unreachable,
 * 2 misuse.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
final class RaftOperatorCommand {
    static final String USAGE = """
            Usage: qraft operator raft list-peers [--http-addr <host:port>]
                   qraft operator raft remove-peer (--id <server-id> | --name <name>) [--http-addr <host:port>] \
            (--token <token> | --token-file <path>)
            The HTTP address defaults to 127.0.0.1:8080.""";
    private static final String DEFAULT_ADDRESS = "127.0.0.1:8080";
    /** Longer than a server waits for a removal to commit, so the server's own answer arrives first. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    private RaftOperatorCommand() { }

    /** Runs the command after {@code qraft operator}, and returns its exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> options = new HashMap<>();
        String subcommand;
        try {
            if (args.length < 2 || !args[0].equals("raft")) throw new IllegalArgumentException("Unknown command");
            subcommand = args[1];
            Set<String> allowed = switch (subcommand) {
                case "list-peers" -> Set.of("--http-addr");
                case "remove-peer" -> Set.of("--http-addr", "--token", "--token-file", "--id", "--name");
                default -> throw new IllegalArgumentException("Unknown command: " + subcommand);
            };
            for (int i = 2; i < args.length; i += 2) {
                if (!allowed.contains(args[i])) throw new IllegalArgumentException("Unknown option: " + args[i]);
                if (i + 1 >= args.length) throw new IllegalArgumentException(args[i] + " needs a value");
                options.put(args[i], args[i + 1]);
            }
            if (subcommand.equals("remove-peer") && options.containsKey("--id") == options.containsKey("--name")) {
                throw new IllegalArgumentException("Name the server to remove with --id or --name");
            }
            if (options.containsKey("--token") && options.containsKey("--token-file")) {
                throw new IllegalArgumentException("Give the token with --token or --token-file, not both");
            }
        } catch (IllegalArgumentException misuse) {
            err.println(misuse.getMessage());
            err.println(USAGE);
            return 2;
        }
        String base = baseUri(options.getOrDefault("--http-addr", DEFAULT_ADDRESS));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            return subcommand.equals("list-peers")
                    ? listPeers(client, base, out, err)
                    : removePeer(client, base, options, out, err);
        } catch (IOException unreachable) {
            err.println("Could not reach " + base + ": " + unreachable);
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        }
    }

    private static int listPeers(HttpClient client, String base, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                        base + "/v1/operator/raft/configuration")).timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(response.body());
        if (response.statusCode() != 200) return refused(body, response.statusCode(), err);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Name", "ID", "Address", "State", "Voter"});
        for (JsonNode server : body.path("servers")) {
            rows.add(new String[] {server.path("name").asText(), server.path("serverId").asText(),
                    server.path("address").asText(), server.path("leader").asBoolean() ? "leader" : "follower",
                    String.valueOf(server.path("voter").asBoolean())});
        }
        int[] widths = new int[5];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) widths[i] = Math.max(widths[i], row[i].length());
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(i < row.length - 1 ? String.format("%-" + (widths[i] + 2) + "s", row[i]) : row[i]);
            }
            out.println(line);
        }
        return 0;
    }

    private static int removePeer(HttpClient client, String base, Map<String, String> options,
                                  PrintStream out, PrintStream err) throws IOException, InterruptedException {
        String token;
        if (options.containsKey("--token-file")) {
            try {
                token = Files.readString(Path.of(options.get("--token-file"))).strip();
            } catch (IOException unreadable) {
                err.println("Could not read the token file " + options.get("--token-file") + ": " + unreadable);
                return 1;
            }
        } else {
            token = options.getOrDefault("--token", "");
        }
        String parameter = options.containsKey("--id")
                ? "id=" + URLEncoder.encode(options.get("--id"), StandardCharsets.UTF_8)
                : "name=" + URLEncoder.encode(options.get("--name"), StandardCharsets.UTF_8);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                        base + "/v1/operator/raft/peer?" + parameter)).timeout(REQUEST_TIMEOUT)
                        .header("X-Qraft-Token", token).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode body = JSON.readTree(response.body());
        if (response.statusCode() != 200) return refused(body, response.statusCode(), err);
        out.println(body.path("message").asText());
        return 0;
    }

    private static int refused(JsonNode body, int status, PrintStream err) {
        err.println("Error " + status + ": " + body.path("code").asText() + ": " + body.path("message").asText());
        return 1;
    }

    private static String baseUri(String address) {
        String trimmed = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
        return trimmed.startsWith("http://") || trimmed.startsWith("https://") ? trimmed : "http://" + trimmed;
    }
}
