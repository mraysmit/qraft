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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.List;
import java.util.Set;

/**
 * The {@code qraft acl} commands (ACL list, Phase 5), after Consul's {@code consul acl}: {@code bootstrap};
 * {@code token}, {@code policy} and {@code role}, each with {@code create}, {@code list}, {@code read} and
 * {@code delete}; and {@code explain}. Each calls a server's ACL API. A change goes to the leader, so its address is
 * the one to give; a read is answered by any server.
 *
 * <p>The caller's token is {@code --token}, or the contents of {@code --token-file}, which keeps it out of the shell's
 * history and the process list, as {@code qraft operator raft} takes it; {@code bootstrap} sends none. The server's
 * address is {@code --http-addr}, else {@code 127.0.0.1:8080}. A link names a policy or role by ID or by name, in the
 * order given; a service identity is {@code service} or {@code service:tenant:namespace}. Exit codes: 0 done, 1
 * refused or unreachable, 2 misuse.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
final class AclCommandLine {
    static final String USAGE = """
            Usage: qraft acl bootstrap
                   qraft acl token create [--description <text>] [--policy-id <id> | --policy-name <name>]... \
            [--role-id <id> | --role-name <name>]... [--node-identity <node>]... \
            [--service-identity <service>[:<tenant>:<namespace>]]... [--expiration-ttl <duration>]
                   qraft acl token list
                   qraft acl token read (--id <accessor-id> | --self)
                   qraft acl token delete --id <accessor-id>
                   qraft acl policy create --name <name> [--description <text>] (--rules <json> | --rules-file <path>)
                   qraft acl policy list | read (--id <id> | --name <name>) | delete --id <id>
                   qraft acl role create --name <name> [--description <text>] \
            [--policy-id <id> | --policy-name <name>]... [--node-identity <node>]... \
            [--service-identity <service>[:<tenant>:<namespace>]]...
                   qraft acl role list | read (--id <id> | --name <name>) | delete --id <id>
                   qraft acl explain --accessor-id <id> --resource <resource> [--name <name>] --access (read|write) \
            [--tenant <tenant> --namespace <namespace>]
            Every command takes --http-addr <host:port>, 127.0.0.1:8080 by default, or https://<host:port> for a \
            server that serves HTTPS, with --ca-file <path> [--client-cert <path> --client-key <path>]; and every \
            command but bootstrap the caller's token as --token <token> or --token-file <path>.""";
    private static final String DEFAULT_ADDRESS = "127.0.0.1:8080";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TOKEN_OPTIONS = Set.of("--token", "--token-file");
    private static final Set<String> REPEATABLE = Set.of("--policy-id", "--policy-name", "--role-id", "--role-name",
            "--node-identity", "--service-identity");
    private static final Set<String> FLAGS = Set.of("--self");

    private AclCommandLine() { }

    /** One option as given, in order, so that links keep the order of the command line. */
    private record Option(String name, String value) {
    }

    /** What a command line asks for. */
    private record Request(String method, String path, ObjectNode body, Kind answer, String subject) {
    }

    private enum Kind { TOKEN, TOKENS, POLICY, POLICIES, ROLE, ROLES, EXPLANATION, DELETED }

    /** Runs the command after {@code qraft acl}, and returns its exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        List<Option> options;
        Request request;
        String token;
        try {
            if (args.length == 0) throw new IllegalArgumentException("Name an ACL command");
            boolean grouped = Set.of("token", "policy", "role").contains(args[0]);
            if (!grouped && !Set.of("bootstrap", "explain").contains(args[0])) {
                throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
            if (grouped && args.length < 2) throw new IllegalArgumentException("Name what to do with the " + args[0]);
            String command = grouped ? args[0] + " " + args[1] : args[0];
            options = options(args, grouped ? 2 : 1, allowed(command));
            request = request(command, options);
            token = command.equals("bootstrap") ? null : token(options);
        } catch (IllegalArgumentException misuse) {
            err.println(misuse.getMessage());
            err.println(USAGE);
            return 2;
        } catch (IOException unreadable) {
            err.println(unreadable.getMessage());
            return 1;
        }
        String base = RaftOperatorCommand.baseUri(single(options, "--http-addr", DEFAULT_ADDRESS));
        HttpClient client;
        try {
            List<Option> given = options;
            client = CommandLineTls.client(name -> single(given, name, null));
        } catch (IllegalArgumentException misuse) {
            err.println(misuse.getMessage());
            err.println(USAGE);
            return 2;
        }
        try {
            HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(base + request.path()))
                    .timeout(REQUEST_TIMEOUT)
                    .method(request.method(), request.body() == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request.body())));
            if (token != null) http.header("X-Qraft-Token", token);
            HttpResponse<String> response = client.send(http.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return refused(response, err);
            print(request, JSON.readTree(response.body()), out);
            return 0;
        } catch (IOException unreachable) {
            err.println("Could not reach " + base + ": " + unreachable);
            return 1;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            err.println("Interrupted");
            return 1;
        }
    }

    private static Set<String> allowed(String command) {
        Set<String> specific = switch (command) {
            case "bootstrap" -> Set.of();
            case "token create" -> Set.of("--description", "--policy-id", "--policy-name", "--role-id", "--role-name",
                    "--node-identity", "--service-identity", "--expiration-ttl");
            case "token list", "policy list", "role list" -> Set.of();
            case "token read" -> Set.of("--id", "--self");
            case "token delete", "policy delete", "role delete" -> Set.of("--id");
            case "policy read", "role read" -> Set.of("--id", "--name");
            case "policy create" -> Set.of("--name", "--description", "--rules", "--rules-file");
            case "role create" -> Set.of("--name", "--description", "--policy-id", "--policy-name", "--node-identity",
                    "--service-identity");
            case "explain" -> Set.of("--accessor-id", "--resource", "--name", "--access", "--tenant", "--namespace");
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        };
        Set<String> allowed = new java.util.HashSet<>(specific);
        allowed.add("--http-addr");
        allowed.addAll(CommandLineTls.OPTIONS);
        if (!command.equals("bootstrap")) allowed.addAll(TOKEN_OPTIONS);
        return allowed;
    }

    private static List<Option> options(String[] args, int from, Set<String> allowed) {
        List<Option> options = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            String name = args[i];
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown option: " + name);
            if (FLAGS.contains(name)) {
                options.add(new Option(name, null));
                continue;
            }
            if (i + 1 >= args.length) throw new IllegalArgumentException(name + " needs a value");
            if (!REPEATABLE.contains(name) && options.stream().anyMatch(option -> option.name().equals(name))) {
                throw new IllegalArgumentException(name + " is given once");
            }
            options.add(new Option(name, args[++i]));
        }
        if (has(options, "--token") && has(options, "--token-file")) {
            throw new IllegalArgumentException("Give the token with --token or --token-file, not both");
        }
        return options;
    }

    private static Request request(String command, List<Option> options) throws IOException {
        return switch (command) {
            case "bootstrap" -> new Request("PUT", "/v1/acl/bootstrap", null, Kind.TOKEN, null);
            case "token create" -> new Request("PUT", "/v1/acl/token", tokenBody(options), Kind.TOKEN, null);
            case "token list" -> new Request("GET", "/v1/acl/tokens", null, Kind.TOKENS, null);
            case "token read" -> {
                if (has(options, "--self") == has(options, "--id")) {
                    throw new IllegalArgumentException("Name the token with --id, or read your own with --self");
                }
                yield new Request("GET", "/v1/acl/token/" + (has(options, "--self") ? "self"
                        : segment(single(options, "--id", null))), null, Kind.TOKEN, null);
            }
            case "token delete" -> deletion("token", options);
            case "policy create" -> new Request("PUT", "/v1/acl/policy", policyBody(options), Kind.POLICY, null);
            case "policy list" -> new Request("GET", "/v1/acl/policies", null, Kind.POLICIES, null);
            case "policy read" -> reading("policy", options, Kind.POLICY);
            case "policy delete" -> deletion("policy", options);
            case "role create" -> new Request("PUT", "/v1/acl/role", roleBody(options), Kind.ROLE, null);
            case "role list" -> new Request("GET", "/v1/acl/roles", null, Kind.ROLES, null);
            case "role read" -> reading("role", options, Kind.ROLE);
            case "role delete" -> deletion("role", options);
            case "explain" -> new Request("POST", "/v1/acl/explain", explainBody(options), Kind.EXPLANATION, null);
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        };
    }

    private static Request reading(String kind, List<Option> options, Kind answer) {
        if (has(options, "--id") == has(options, "--name")) {
            throw new IllegalArgumentException("Name the " + kind + " with --id or --name");
        }
        String path = has(options, "--id") ? "/v1/acl/" + kind + "/" + segment(single(options, "--id", null))
                : "/v1/acl/" + kind + "/name/" + segment(single(options, "--name", null));
        return new Request("GET", path, null, answer, null);
    }

    private static Request deletion(String kind, List<Option> options) {
        String id = single(options, "--id", null);
        if (id == null) throw new IllegalArgumentException("Name the " + kind + " to delete with --id");
        return new Request("DELETE", "/v1/acl/" + kind + "/" + segment(id), null, Kind.DELETED, kind + " " + id);
    }

    private static ObjectNode tokenBody(List<Option> options) {
        ObjectNode body = JSON.createObjectNode();
        putIfGiven(body, "description", options, "--description");
        links(body, "policies", options, "--policy-id", "--policy-name");
        links(body, "roles", options, "--role-id", "--role-name");
        identities(body, options);
        putIfGiven(body, "expirationTtl", options, "--expiration-ttl");
        return body;
    }

    private static ObjectNode policyBody(List<Option> options) throws IOException {
        String name = single(options, "--name", null);
        if (name == null) throw new IllegalArgumentException("Name the policy with --name");
        if (has(options, "--rules") == has(options, "--rules-file")) {
            throw new IllegalArgumentException("Give the rules with --rules or --rules-file");
        }
        String rules;
        if (has(options, "--rules")) {
            rules = single(options, "--rules", null);
        } else {
            String file = single(options, "--rules-file", null);
            try {
                rules = Files.readString(Path.of(file));
            } catch (IOException unreadable) {
                throw new IOException("Could not read the rules file " + file + ": " + unreadable, unreadable);
            }
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        putIfGiven(body, "description", options, "--description");
        try {
            body.set("rules", JSON.readTree(rules));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("The rules are not valid JSON: " + invalid.getOriginalMessage());
        }
        return body;
    }

    private static ObjectNode roleBody(List<Option> options) {
        String name = single(options, "--name", null);
        if (name == null) throw new IllegalArgumentException("Name the role with --name");
        ObjectNode body = JSON.createObjectNode();
        body.put("name", name);
        putIfGiven(body, "description", options, "--description");
        links(body, "policies", options, "--policy-id", "--policy-name");
        identities(body, options);
        return body;
    }

    private static ObjectNode explainBody(List<Option> options) {
        for (String required : List.of("--accessor-id", "--resource", "--access")) {
            if (!has(options, required)) throw new IllegalArgumentException("explain needs " + required);
        }
        ObjectNode body = JSON.createObjectNode();
        putIfGiven(body, "accessorId", options, "--accessor-id");
        putIfGiven(body, "resource", options, "--resource");
        putIfGiven(body, "name", options, "--name");
        putIfGiven(body, "access", options, "--access");
        putIfGiven(body, "tenant", options, "--tenant");
        putIfGiven(body, "namespace", options, "--namespace");
        return body;
    }

    private static void links(ObjectNode body, String field, List<Option> options, String byId, String byName) {
        ArrayNode links = JSON.createArrayNode();
        for (Option option : options) {
            if (option.name().equals(byId)) links.addObject().put("id", option.value());
            if (option.name().equals(byName)) links.addObject().put("name", option.value());
        }
        if (!links.isEmpty()) body.set(field, links);
    }

    private static void identities(ObjectNode body, List<Option> options) {
        ArrayNode nodes = JSON.createArrayNode();
        ArrayNode services = JSON.createArrayNode();
        for (Option option : options) {
            if (option.name().equals("--node-identity")) nodes.addObject().put("nodeName", option.value());
            if (option.name().equals("--service-identity")) {
                String[] parts = option.value().split(":", -1);
                if (parts.length != 1 && parts.length != 3) {
                    throw new IllegalArgumentException("A service identity is service or service:tenant:namespace, not "
                            + option.value());
                }
                ObjectNode identity = services.addObject().put("serviceName", parts[0]);
                if (parts.length == 3) identity.put("tenant", parts[1]).put("namespace", parts[2]);
            }
        }
        if (!nodes.isEmpty()) body.set("nodeIdentities", nodes);
        if (!services.isEmpty()) body.set("serviceIdentities", services);
    }

    private static String token(List<Option> options) throws IOException {
        if (has(options, "--token-file")) {
            String file = single(options, "--token-file", null);
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (IOException unreadable) {
                throw new IOException("Could not read the token file " + file + ": " + unreadable, unreadable);
            }
        }
        return single(options, "--token", "");
    }

    // ---- Printing ----

    private static void print(Request request, JsonNode answer, PrintStream out) {
        switch (request.answer()) {
            case TOKEN -> printToken(answer, out);
            case POLICY -> printPolicy(answer, out);
            case ROLE -> printRole(answer, out);
            case EXPLANATION -> printExplanation(answer, out);
            case DELETED -> out.println("Deleted " + request.subject());
            case TOKENS -> table(answer, out, List.of("AccessorID", "Description", "Policies"),
                    token -> new String[] {token.path("accessorId").asText(), token.path("description").asText(),
                            names(token.path("policies"))});
            case POLICIES, ROLES -> table(answer, out, List.of("ID", "Name", "Description"),
                    entry -> new String[] {entry.path("id").asText(), entry.path("name").asText(),
                            entry.path("description").asText()});
        }
    }

    private static void printToken(JsonNode token, PrintStream out) {
        RaftOperatorCommand.printField(out, "AccessorID:", token.path("accessorId").asText());
        RaftOperatorCommand.printField(out, "SecretID:", token.path("secretId").asText());
        RaftOperatorCommand.printField(out, "Description:", token.path("description").asText());
        RaftOperatorCommand.printField(out, "Policies:", links(token.path("policies")));
        RaftOperatorCommand.printField(out, "Roles:", links(token.path("roles")));
        RaftOperatorCommand.printField(out, "Node identities:", nodeIdentities(token.path("nodeIdentities")));
        RaftOperatorCommand.printField(out, "Service identities:", serviceIdentities(token.path("serviceIdentities")));
        RaftOperatorCommand.printField(out, "Create time:", token.path("createTime").asText());
        RaftOperatorCommand.printField(out, "Expiration time:", token.path("expirationTime").isNull()
                || token.path("expirationTime").isMissingNode() ? "(none)" : token.path("expirationTime").asText());
        if (token.path("installedFromFile").asBoolean()) {
            RaftOperatorCommand.printField(out, "Installed from:", "server.acl.initialManagementTokenFile; it returns "
                    + "at the next leadership change while any server's file holds its secret");
        }
    }

    private static void printPolicy(JsonNode policy, PrintStream out) {
        RaftOperatorCommand.printField(out, "ID:", policy.path("id").asText());
        RaftOperatorCommand.printField(out, "Name:", policy.path("name").asText());
        RaftOperatorCommand.printField(out, "Description:", policy.path("description").asText());
        RaftOperatorCommand.printField(out, "Rules:", policy.path("rules").toString());
    }

    private static void printRole(JsonNode role, PrintStream out) {
        RaftOperatorCommand.printField(out, "ID:", role.path("id").asText());
        RaftOperatorCommand.printField(out, "Name:", role.path("name").asText());
        RaftOperatorCommand.printField(out, "Description:", role.path("description").asText());
        RaftOperatorCommand.printField(out, "Policies:", links(role.path("policies")));
        RaftOperatorCommand.printField(out, "Node identities:", nodeIdentities(role.path("nodeIdentities")));
        RaftOperatorCommand.printField(out, "Service identities:", serviceIdentities(role.path("serviceIdentities")));
    }

    private static void printExplanation(JsonNode explanation, PrintStream out) {
        RaftOperatorCommand.printField(out, "Allowed:", explanation.path("allowed").asBoolean());
        boolean byRule = explanation.path("decidedBy").asText().equals("rule");
        RaftOperatorCommand.printField(out, "Decided by:", byRule ? "a rule" : "the default policy, "
                + explanation.path("defaultPolicy").asText() + ", as no rule matched");
        if (explanation.has("policy")) {
            RaftOperatorCommand.printField(out, "Policy:", link(explanation.path("policy")));
        }
        if (explanation.has("nodeIdentity")) {
            RaftOperatorCommand.printField(out, "Node identity:", explanation.path("nodeIdentity").path("nodeName")
                    .asText());
        }
        if (explanation.has("serviceIdentity")) {
            RaftOperatorCommand.printField(out, "Service identity:",
                    serviceIdentities(JSON.createArrayNode().add(explanation.path("serviceIdentity"))));
        }
        if (explanation.has("role")) RaftOperatorCommand.printField(out, "Role:", link(explanation.path("role")));
        if (byRule) RaftOperatorCommand.printField(out, "Rule:", explanation.path("rule").toString());
    }

    private static void table(JsonNode entries, PrintStream out, List<String> header,
                              java.util.function.Function<JsonNode, String[]> row) {
        List<String[]> rows = new ArrayList<>();
        rows.add(header.toArray(String[]::new));
        entries.forEach(entry -> rows.add(row.apply(entry)));
        RaftOperatorCommand.printTable(rows, out);
    }

    private static String link(JsonNode link) {
        return link.path("name").asText() + " (" + link.path("id").asText() + ")";
    }

    private static String links(JsonNode links) {
        List<String> each = new ArrayList<>();
        links.forEach(link -> each.add(link(link)));
        return each.isEmpty() ? "(none)" : String.join(", ", each);
    }

    private static String names(JsonNode links) {
        List<String> each = new ArrayList<>();
        links.forEach(link -> each.add(link.path("name").asText()));
        return String.join(",", each);
    }

    private static String nodeIdentities(JsonNode identities) {
        List<String> each = new ArrayList<>();
        identities.forEach(identity -> each.add(identity.path("nodeName").asText()));
        return each.isEmpty() ? "(none)" : String.join(", ", each);
    }

    private static String serviceIdentities(JsonNode identities) {
        List<String> each = new ArrayList<>();
        identities.forEach(identity -> each.add(identity.path("serviceName").asText() + " ("
                + identity.path("tenant").asText("default") + "/" + identity.path("namespace").asText("default") + ")"));
        return each.isEmpty() ? "(none)" : String.join(", ", each);
    }

    private static int refused(HttpResponse<String> response, PrintStream err) {
        try {
            return RaftOperatorCommand.refused(JSON.readTree(response.body()), response.statusCode(), err);
        } catch (JsonProcessingException notJson) {
            err.println("Error " + response.statusCode() + ": " + response.body());
            return 1;
        }
    }

    // ---- Options ----

    private static boolean has(List<Option> options, String name) {
        return options.stream().anyMatch(option -> option.name().equals(name));
    }

    private static String single(List<Option> options, String name, String fallback) {
        return options.stream().filter(option -> option.name().equals(name)).map(Option::value).findFirst()
                .orElse(fallback);
    }

    private static void putIfGiven(ObjectNode body, String field, List<Option> options, String name) {
        String value = single(options, name, null);
        if (value != null) body.put(field, value);
    }

    /** {@code value} as one path segment. */
    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
