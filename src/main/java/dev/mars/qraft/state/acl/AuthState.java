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

package dev.mars.qraft.state.acl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The replicated auth methods, binding rules and the login tokens' methods (authentication list, Phases 1 and 2). The
 * state machine changes it on its thread, refusing what Consul refuses; a change answers {@code null} when made, or its
 * refusal's code. Readers on other threads see each entry whole.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class AuthState {
    /** The shortest life a method gives a login token. */
    public static final java.time.Duration MIN_TOKEN_TTL = java.time.Duration.ofMinutes(1);

    private final ConcurrentSkipListMap<String, AuthMethod> methods = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<String, BindingRule> rules = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<String, String> logins = new ConcurrentHashMap<>();

    public Optional<AuthMethod> method(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(methods.get(name));
    }

    public List<AuthMethod> methods() {
        return List.copyOf(methods.values());
    }

    public Optional<BindingRule> rule(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(rules.get(id));
    }

    public List<BindingRule> rules() {
        return List.copyOf(rules.values());
    }

    /** {@code method}'s binding rules, in ID order. */
    public List<BindingRule> rulesOf(String method) {
        return rules.values().stream().filter(rule -> rule.authMethod().equals(method)).toList();
    }

    /** The method a login token was created through, or empty for any other token. */
    public Optional<String> loginMethodOf(String accessorId) {
        return accessorId == null ? Optional.empty() : Optional.ofNullable(logins.get(accessorId));
    }

    /** Why {@code method} cannot be written, or {@code null}. */
    public static String refusalOf(AuthMethod method) {
        if (method.name().isBlank()) return "invalid_auth_method";
        boolean complete = switch (method.config()) {
            case JwtConfig jwt -> "jwt".equals(method.type()) && !jwt.publicKeysPem().isEmpty();
            case KubernetesConfig kubernetes -> "kubernetes".equals(method.type()) && !kubernetes.host().isBlank()
                    && !kubernetes.caCertPem().isBlank() && !kubernetes.serviceAccountJwt().isBlank();
        };
        if (!complete) return "invalid_auth_method";
        if (method.maxTokenTtl().compareTo(MIN_TOKEN_TTL) < 0
                || method.maxTokenTtl().compareTo(AuthMethod.MAX_TOKEN_TTL) > 0) return "invalid_auth_method";
        return null;
    }

    public String createMethod(AuthMethod method, long index) {
        String refusal = refusalOf(method);
        if (refusal != null) return refusal;
        if (methods.containsKey(method.name())) return "auth_method_exists";
        methods.put(method.name(), method.at(index, index));
        return null;
    }

    public String updateMethod(AuthMethod method, long index) {
        AuthMethod current = methods.get(method.name());
        if (current == null) return "auth_method_not_found";
        String refusal = refusalOf(method);
        if (refusal != null) return refusal;
        methods.put(method.name(), method.at(current.createIndex(), index));
        return null;
    }

    /** Deletes {@code name} and its rules; answers the login tokens it created, which the store deletes, or null. */
    public List<String> deleteMethod(String name) {
        if (methods.remove(name) == null) return null;
        rules.values().removeIf(rule -> rule.authMethod().equals(name));
        List<String> tokens = new ArrayList<>();
        logins.forEach((accessor, method) -> {
            if (method.equals(name)) tokens.add(accessor);
        });
        tokens.forEach(logins::remove);
        return tokens;
    }

    /** Why {@code rule} cannot be written, or {@code null}. */
    String refusalOf(BindingRule rule) {
        if (!methods.containsKey(rule.authMethod())) return "auth_method_not_found";
        if (rule.bindName().isBlank()) return "invalid_binding_rule";
        try {
            Selector.parse(rule.selector());
        } catch (IllegalArgumentException unread) {
            return "invalid_binding_rule";
        }
        return null;
    }

    public String createRule(BindingRule rule, long index) {
        String refusal = refusalOf(rule);
        if (refusal != null) return refusal;
        if (rules.containsKey(rule.id())) return "binding_rule_exists";
        rules.put(rule.id(), rule.at(index, index));
        return null;
    }

    public String updateRule(BindingRule rule, long index) {
        BindingRule current = rules.get(rule.id());
        if (current == null) return "binding_rule_not_found";
        String refusal = refusalOf(rule);
        if (refusal != null) return refusal;
        rules.put(rule.id(), rule.at(current.createIndex(), index));
        return null;
    }

    public String deleteRule(String id) {
        return rules.remove(id) == null ? "binding_rule_not_found" : null;
    }

    public void recordLogin(String accessorId, String method) {
        logins.put(accessorId, method);
    }

    public void forgetLogin(String accessorId) {
        logins.remove(accessorId);
    }

    public Snapshot snapshot() {
        return new Snapshot(methods(), rules(), new TreeMap<>(logins));
    }

    /** Replaces the state with {@code snapshot}'s; none, from a snapshot written before auth methods. */
    public void restore(Snapshot snapshot) {
        methods.clear();
        rules.clear();
        logins.clear();
        if (snapshot == null) return;
        if (snapshot.methods() != null) snapshot.methods().forEach(method -> methods.put(method.name(), method));
        if (snapshot.rules() != null) snapshot.rules().forEach(rule -> rules.put(rule.id(), rule));
        if (snapshot.logins() != null) logins.putAll(snapshot.logins());
    }

    /** The auth state in a snapshot. */
    public record Snapshot(List<AuthMethod> methods, List<BindingRule> rules, Map<String, String> logins) {
    }
}
