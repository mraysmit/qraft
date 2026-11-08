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

package dev.mars.qraft.common.acl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a policy's rules from the JSON form of Consul's rules (ACL list, proposals 6 to 8):
 *
 * <pre>{@code
 * {"node_prefix": {"": {"policy": "read"}},
 *  "tenant": {"acme": {"namespace": {"prod": {"service_prefix": {"billing-": {"policy": "write"}}}}}},
 *  "operator": "read", "acl": "write"}
 * }</pre>
 *
 * <ul>
 *   <li>{@code node}, {@code service}, {@code client}, {@code key} and {@code session}, each with a {@code _prefix}
 *       form, map names to
 *       {@code {"policy": ...}}; {@code operator} and {@code acl} take the access itself. The access is
 *       {@code read}, {@code write} or {@code deny}, in lower case, as Consul writes it.</li>
 *   <li>{@code tenant} and {@code namespace}, each with a {@code _prefix} form, nest service rules, as Consul
 *       Enterprise's {@code partition} and {@code namespace} do. A tenant is given only at the top, a namespace at the
 *       top or in a tenant, and a service rule outside either is in {@code default}. No other rule is nested, because
 *       nodes have no scope (proposal 7).</li>
 *   <li>Anything else is refused: an unknown resource or field, Consul's {@code intentions}, which need a mesh, a key
 *       written twice, and a rule given twice, also through {@code default}. A refusal names where in the rules it
 *       is.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public final class AclRulesJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

    private enum Level { TOP, TENANT, NAMESPACE }

    private AclRulesJson() {
    }

    /** The rules {@code json} holds, refused with {@link IllegalArgumentException} as the class describes. */
    public static AclRules parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("ACL rules are not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("ACL rules must be a JSON object");
        List<AclRule> rules = new ArrayList<>();
        block(root, "", Level.TOP, null, null, rules);
        return new AclRules(rules);
    }

    /**
     * {@code rules} in the form {@link #parse} reads, so that the two are inverses: a service rule in {@code default}
     * at the top, and any other scope nested under {@code tenant} and {@code namespace}, each with its
     * {@code _prefix} form; the access in lower case.
     */
    public static String write(AclRules rules) {
        ObjectNode root = MAPPER.createObjectNode();
        for (AclRule rule : rules.rules()) {
            String access = rule.access().name().toLowerCase(java.util.Locale.ROOT);
            if (!rule.resource().named()) {
                root.put(rule.resource().ruleName(), access);
                continue;
            }
            ObjectNode block = root;
            if (rule.resource().scoped()) {
                if (!rule.tenant().equals(AclScope.DEFAULT)) block = scope(block, "tenant", rule.tenant());
                if (!rule.namespace().equals(AclScope.DEFAULT)) block = scope(block, "namespace", rule.namespace());
            }
            child(child(block, rule.resource().ruleName() + (rule.prefix() ? "_prefix" : "")), rule.name())
                    .put("policy", access);
        }
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ACL rules could not be written", e);
        }
    }

    private static ObjectNode scope(ObjectNode block, String kind, AclScope scope) {
        return child(child(block, kind + (scope.prefix() ? "_prefix" : "")), scope.name());
    }

    private static ObjectNode child(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        return existing instanceof ObjectNode object ? object : parent.putObject(key);
    }

    private static void block(JsonNode node, String path, Level level, AclScope tenant, AclScope namespace,
                              List<AclRule> rules) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            String at = path.isEmpty() ? key : path + "." + key;
            boolean prefix = key.endsWith("_prefix");
            String base = prefix ? key.substring(0, key.length() - "_prefix".length()) : key;
            switch (base) {
                case "node", "service", "client", "key", "session" -> {
                    AclResource resource = AclResource.valueOf(base.toUpperCase(java.util.Locale.ROOT));
                    if (!resource.scoped() && level != Level.TOP) {
                        throw refused(at, "a " + base + " rule has no tenant or namespace");
                    }
                    named(field.getValue(), at, resource, prefix, tenant, namespace, rules);
                }
                case "operator", "acl" -> {
                    if (prefix) throw refused(at, base + " is a single value and has no prefix form");
                    if (level != Level.TOP) throw refused(at, "an " + base + " rule has no tenant or namespace");
                    rules.add(at(at, () -> AclRule.single(AclResource.valueOf(base.toUpperCase(java.util.Locale.ROOT)),
                            access(field.getValue(), at))));
                }
                case "tenant" -> {
                    if (level != Level.TOP) throw refused(at, "a tenant is given only at the top of the rules");
                    scopes(field.getValue(), at, prefix, (scope, child, childPath) ->
                            block(child, childPath, Level.TENANT, scope, null, rules));
                }
                case "namespace" -> {
                    if (level == Level.NAMESPACE) throw refused(at, "a namespace cannot hold a namespace");
                    scopes(field.getValue(), at, prefix, (scope, child, childPath) ->
                            block(child, childPath, Level.NAMESPACE, tenant, scope, rules));
                }
                default -> throw refused(at, "not an ACL resource or field this server knows");
            }
        }
    }

    private static void named(JsonNode value, String path, AclResource resource, boolean prefix, AclScope tenant,
                              AclScope namespace, List<AclRule> rules) {
        if (!value.isObject()) throw refused(path, "must map names to rules");
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            String at = path + "." + entry.getKey();
            JsonNode rule = entry.getValue();
            if (!rule.isObject()) throw refused(at, "a rule is an object holding its policy");
            for (Map.Entry<String, JsonNode> field : rule.properties()) {
                if (!field.getKey().equals("policy")) throw refused(at + "." + field.getKey(), "not a field of a rule");
            }
            if (!rule.has("policy")) throw refused(at, "a rule needs a policy");
            AclAccess access = access(rule.get("policy"), at + ".policy");
            rules.add(at(at, () -> new AclRule(resource, entry.getKey(), prefix, access,
                    resource.scoped() ? tenant : null, resource.scoped() ? namespace : null)));
        }
    }

    private static void scopes(JsonNode value, String path, boolean prefix, ScopeBlock block) {
        if (!value.isObject()) throw refused(path, "must map names to rules");
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            String at = path + "." + entry.getKey();
            AclScope scope = at(at, () -> new AclScope(entry.getKey(), prefix));
            if (!entry.getValue().isObject()) throw refused(at, "must hold rules");
            block.read(scope, entry.getValue(), at);
        }
    }

    private static AclAccess access(JsonNode value, String path) {
        String text = value.isTextual() ? value.textValue() : null;
        return switch (text == null ? "" : text) {
            case "read" -> AclAccess.READ;
            case "write" -> AclAccess.WRITE;
            case "deny" -> AclAccess.DENY;
            default -> throw refused(path, "the access is read, write or deny, not " + value);
        };
    }

    private static <T> T at(String path, java.util.function.Supplier<T> value) {
        try {
            return value.get();
        } catch (IllegalArgumentException e) {
            throw refused(path, e.getMessage());
        }
    }

    private static IllegalArgumentException refused(String path, String reason) {
        return new IllegalArgumentException("ACL rules, at " + path + ": " + reason);
    }

    @FunctionalInterface
    private interface ScopeBlock {
        void read(AclScope scope, JsonNode rules, String path);
    }
}
