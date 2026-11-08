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
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code qraft kv} (key/value list, Phase 5), on Consul's {@code consul kv}: {@code get}, {@code put} and
 * {@code delete} against a server's {@code /v1/kv}, with Consul's messages. A value given as {@code @path} is read
 * from that file, as in Consul. Exit codes: 0 done, 1 refused, not found, a failed compare-and-set, or unreachable,
 * 2 misuse.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
final class KvCommandLine {
    static final String USAGE = """
            Usage: qraft kv get <key> [--recurse | --keys [--separator <separator>]]
                   qraft kv put <key> <value | @file> [--flags <number>] [--cas <index>]
                   qraft kv delete <key> [--recurse | --cas <index>]
            Every command takes --http-addr <host:port>, 127.0.0.1:8080 by default, or https://<host:port> with \
            --ca-file <path> [--client-cert <path> --client-key <path>]; --tenant <tenant> and --namespace <namespace>; \
            and the caller's token as --token <token> or --token-file <path>.""";
    private static final String DEFAULT_ADDRESS = "127.0.0.1:8080";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FLAGS = Set.of("--recurse", "--keys");
    private static final Set<String> COMMON = Set.of("--http-addr", "--token", "--token-file", "--tenant", "--namespace");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "get", Set.of("--recurse", "--keys", "--separator"),
            "put", Set.of("--flags", "--cas"),
            "delete", Set.of("--recurse", "--cas"));

    private KvCommandLine() { }

    /** Runs the command after {@code qraft kv}, and returns its exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String command;
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new HashMap<>();
        HttpClient client;
        String token;
        byte[] value = null;
        try {
            if (args.length == 0 || !ALLOWED.containsKey(args[0])) {
                throw new IllegalArgumentException(args.length == 0 ? "Name a kv command" : "Unknown command: " + args[0]);
            }
            command = args[0];
            Set<String> allowed = new HashSet<>(ALLOWED.get(command));
            allowed.addAll(COMMON);
            allowed.addAll(CommandLineTls.OPTIONS);
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    positional.add(arg);
                } else if (!allowed.contains(arg)) {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                } else if (FLAGS.contains(arg)) {
                    options.put(arg, "");
                } else if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(arg + " needs a value");
                } else {
                    options.put(arg, args[++i]);
                }
            }
            boolean listing = options.containsKey("--recurse") || options.containsKey("--keys");
            if (options.containsKey("--recurse") && options.containsKey("--keys")) {
                throw new IllegalArgumentException("Give --recurse or --keys, not both");
            }
            if (options.containsKey("--separator") && !options.containsKey("--keys")) {
                throw new IllegalArgumentException("--separator is given with --keys");
            }
            if (options.containsKey("--recurse") && options.containsKey("--cas")) {
                throw new IllegalArgumentException("Give --recurse or --cas, not both");
            }
            int expected = command.equals("put") ? 2 : 1;
            if (positional.size() > expected || positional.size() < expected && !(listing && positional.isEmpty())) {
                throw new IllegalArgumentException(command.equals("put") ? "put takes a key and a value"
                        : command + " takes one key");
            }
            if (options.containsKey("--token") && options.containsKey("--token-file")) {
                throw new IllegalArgumentException("Give the token with --token or --token-file, not both");
            }
            token = options.containsKey("--token-file")
                    ? Files.readString(Path.of(options.get("--token-file"))).strip() : options.get("--token");
            if (command.equals("put")) value = value(positional.get(1));
            client = CommandLineTls.client(options::get);
        } catch (IllegalArgumentException misuse) {
            err.println(misuse.getMessage());
            err.println(USAGE);
            return 2;
        } catch (IOException unreadable) {
            err.println(unreadable.getMessage());
            return 1;
        }
        String key = positional.isEmpty() ? "" : positional.getFirst();
        String base = RaftOperatorCommand.baseUri(options.getOrDefault("--http-addr", DEFAULT_ADDRESS));
        try {
            return switch (command) {
                case "get" -> get(client, base, key, options, token, out, err);
                case "put" -> put(client, base, key, value, options, token, out, err);
                default -> delete(client, base, key, options, token, out, err);
            };
        } catch (IOException unreachable) {
            err.println("Could not reach " + base + ": " + unreachable);
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        }
    }

    private static int get(HttpClient client, String base, String key, Map<String, String> options, String token,
                           PrintStream out, PrintStream err) throws IOException, InterruptedException {
        boolean keys = options.containsKey("--keys");
        boolean recurse = options.containsKey("--recurse");
        String query = keys ? "keys" + (options.containsKey("--separator")
                ? "&separator=" + URLEncoder.encode(options.get("--separator"), StandardCharsets.UTF_8) : "")
                : recurse ? "recurse" : "raw";
        HttpResponse<byte[]> answer = send(client, "GET", base, key, query, null, options, token);
        if (answer.statusCode() == 404) {
            if (keys || recurse) return 0;
            err.println("Error! No key exists at: " + key);
            return 1;
        }
        if (answer.statusCode() / 100 != 2) return refused(answer, err);
        if (keys) {
            for (JsonNode name : JSON.readTree(answer.body())) out.println(name.asText());
        } else if (recurse) {
            for (JsonNode entry : JSON.readTree(answer.body())) {
                byte[] value = Base64.getDecoder().decode(entry.path("Value").asText());
                out.println(entry.path("Key").asText() + ":" + new String(value, StandardCharsets.UTF_8));
            }
        } else {
            out.write(answer.body());
            out.println();
        }
        return 0;
    }

    private static int put(HttpClient client, String base, String key, byte[] value, Map<String, String> options,
                           String token, PrintStream out, PrintStream err) throws IOException, InterruptedException {
        List<String> query = new ArrayList<>();
        if (options.containsKey("--flags")) query.add("flags=" + encode(options.get("--flags")));
        if (options.containsKey("--cas")) query.add("cas=" + encode(options.get("--cas")));
        HttpResponse<byte[]> answer = send(client, "PUT", base, key, String.join("&", query), value, options, token);
        if (answer.statusCode() / 100 != 2) return refused(answer, err);
        if (applied(answer)) {
            out.println("Success! Data written to: " + key);
            return 0;
        }
        err.println("Error! Did not write to " + key + ": CAS failed");
        return 1;
    }

    private static int delete(HttpClient client, String base, String key, Map<String, String> options, String token,
                              PrintStream out, PrintStream err) throws IOException, InterruptedException {
        boolean recurse = options.containsKey("--recurse");
        String query = recurse ? "recurse" : options.containsKey("--cas") ? "cas=" + encode(options.get("--cas")) : "";
        HttpResponse<byte[]> answer = send(client, "DELETE", base, key, query, null, options, token);
        if (answer.statusCode() / 100 != 2) return refused(answer, err);
        if (!applied(answer)) {
            err.println("Error! Did not delete key " + key + ": CAS failed");
            return 1;
        }
        out.println(recurse ? "Success! Deleted keys with prefix: " + key : "Success! Deleted key: " + key);
        return 0;
    }

    private static HttpResponse<byte[]> send(HttpClient client, String method, String base, String key, String query,
                                             byte[] body, Map<String, String> options, String token)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("/v1/kv/");
        for (String segment : key.split("/", -1)) {
            if (path.charAt(path.length() - 1) != '/') path.append('/');
            path.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path
                        + (query.isEmpty() ? "" : "?" + query)))
                .timeout(REQUEST_TIMEOUT)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) request.header("X-Qraft-Token", token);
        if (options.containsKey("--tenant")) request.header("X-Qraft-Tenant", options.get("--tenant"));
        if (options.containsKey("--namespace")) request.header("X-Qraft-Namespace", options.get("--namespace"));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** {@code @path} names a file to read, as in Consul; anything else is the value itself. */
    private static byte[] value(String given) throws IOException {
        if (given.startsWith("@")) return Files.readAllBytes(Path.of(given.substring(1)));
        return given.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean applied(HttpResponse<byte[]> answer) throws IOException {
        return JSON.readTree(answer.body()).asBoolean();
    }

    private static int refused(HttpResponse<byte[]> answer, PrintStream err) throws IOException {
        JsonNode body;
        try {
            body = JSON.readTree(answer.body());
        } catch (IOException notJson) {
            err.println("Error " + answer.statusCode() + ": " + new String(answer.body(), StandardCharsets.UTF_8));
            return 1;
        }
        return RaftOperatorCommand.refused(body, answer.statusCode(), err);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
