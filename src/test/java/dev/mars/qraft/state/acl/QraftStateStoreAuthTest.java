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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the replicated auth methods and binding rules (authentication list, Phase 1, decisions 1 and 2): a {@code jwt}
 * method is created, updated and deleted, taking its binding rules and its login tokens with it; a rule needs its
 * method and a selector this server reads; refusals are those Consul makes; the snapshot carries both, and one written
 * before them restores none; and the commands round-trip through the codec.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreAuthTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final String KEY = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n-----END PUBLIC KEY-----";
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private RaftCommandResult<?> apply(RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    private static void refused(String code, RaftCommandResult<?> result) {
        assertEquals(code, assertInstanceOf(RaftCommandResult.Refused.class, result).code(), result.toString());
    }

    static AuthMethod method(String name) {
        return new AuthMethod(name, "jwt", "Workloads", Duration.ofMinutes(30), new JwtConfig(List.of(KEY),
                "https://issuer.example", List.of("qraft"), Map.of("sub", "name"), Map.of("groups", "groups"),
                Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(5)), 0, 0);
    }

    static BindingRule rule(String id, String method, String selector) {
        return new BindingRule(id, "web workloads", method, selector, BindType.SERVICE, "${value.name}", 0, 0);
    }

    @Test
    void aMethodAndItsRulesAreCreatedUpdatedAndDeletedWithItsLoginTokens() {
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.createMethod(method("workloads"))));
        assertEquals(30, store.getAuth().method("workloads").orElseThrow().maxTokenTtl().toMinutes());
        assertTrue(store.getAuth().method("workloads").orElseThrow().createIndex() > 0);
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.createBindingRule(rule("r1", "workloads",
                "value.name == \"web\""))));
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.createBindingRule(rule("r2", "workloads",
                "\"admins\" in list.groups and value.name == \"ops\""))));
        assertEquals(List.of("r1", "r2"), store.getAuth().rulesOf("workloads").stream().map(BindingRule::id).toList());

        AclToken login = new AclToken("a1d1b0c4-5e6f-4a7b-8c9d-0e1f2a3b4c5d", AclSecrets.hash("s"), "login: workloads",
                List.of(), List.of(), List.of(), List.of(), T0, T0.plusSeconds(1800), false);
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.login(login, "workloads")));
        assertTrue(store.getAcl().token(login.accessorId()).isPresent());
        assertEquals(java.util.Optional.of("workloads"), store.getAuth().loginMethodOf(login.accessorId()));

        AuthMethod changed = new AuthMethod("workloads", "jwt", "Changed", Duration.ofHours(1),
                method("workloads").config(), 0, 0);
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.updateMethod(changed)));
        assertEquals("Changed", store.getAuth().method("workloads").orElseThrow().description());

        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.deleteMethod("workloads")));
        assertTrue(store.getAuth().method("workloads").isEmpty());
        assertEquals(List.of(), store.getAuth().rulesOf("workloads"), "its rules go with it");
        assertTrue(store.getAcl().token(login.accessorId()).isEmpty(), "and so do its login tokens, as in Consul");
    }

    @Test
    void whatCannotBeWrittenIsRefused() {
        apply(AuthCommand.createMethod(method("workloads")));

        refused("auth_method_exists", apply(AuthCommand.createMethod(method("workloads"))));
        refused("invalid_auth_method", apply(AuthCommand.createMethod(new AuthMethod("kube", "kubernetes", "", Duration
                .ofHours(1), method("x").config(), 0, 0))));
        refused("invalid_auth_method", apply(AuthCommand.createMethod(new AuthMethod("long", "jwt", "", Duration
                .ofHours(25), method("x").config(), 0, 0))));
        refused("invalid_auth_method", apply(AuthCommand.createMethod(new AuthMethod("nokeys", "jwt", "", Duration
                .ofHours(1), new JwtConfig(List.of(), "", List.of(), Map.of(), Map.of(), Duration.ZERO, Duration.ZERO,
                Duration.ZERO), 0, 0))));
        refused("auth_method_not_found", apply(AuthCommand.createBindingRule(rule("r1", "absent", ""))));
        refused("invalid_binding_rule", apply(AuthCommand.createBindingRule(rule("r1", "workloads",
                "value.name matches \".*\""))));
        refused("auth_method_not_found", apply(AuthCommand.deleteMethod("absent")));
        refused("binding_rule_not_found", apply(AuthCommand.deleteBindingRule("absent")));
        refused("auth_method_not_found", apply(AuthCommand.login(new AclToken("b2b2b2b2-0000-4000-8000-000000000002", AclSecrets.hash("s"), "", List.of(),
                List.of(), List.of(), List.of(), T0, T0.plusSeconds(60), false), "absent")));
    }

    @Test
    void selectorsAreTheSubsetThisServerReads() {
        assertTrue(Selector.parse("").matches(Map.of(), Map.of()), "an empty selector takes every login");
        Selector both = Selector.parse("value.name == \"web\" and \"admins\" in list.groups");
        assertTrue(both.matches(Map.of("name", "web"), Map.of("groups", List.of("admins", "dev"))));
        assertTrue(!both.matches(Map.of("name", "web"), Map.of("groups", List.of("dev"))));
        assertTrue(!both.matches(Map.of("name", "db"), Map.of("groups", List.of("admins"))));
        for (String unread : List.of("value.name != \"web\"", "value.name == web", "value.name == \"a\" or value.b == \"c\"",
                "list.groups contains \"x\"", "value.name ==")) {
            assertThrows(IllegalArgumentException.class, () -> Selector.parse(unread), unread);
        }
    }

    @Test
    void theSnapshotCarriesMethodsRulesAndLoginsAndTheCommandsRoundTrip() throws Exception {
        apply(AuthCommand.createMethod(method("workloads")));
        apply(AuthCommand.createBindingRule(rule("r1", "workloads", "value.name == \"web\"")));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(store.getAuth().methods(), restored.getAuth().methods());
        assertEquals(store.getAuth().rulesOf("workloads"), restored.getAuth().rulesOf("workloads"));
        ObjectNode older = (ObjectNode) new ObjectMapper().readTree(store.takeSnapshot());
        older.remove("auth");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(new ObjectMapper().writeValueAsBytes(older));
        assertEquals(List.of(), fromOlder.getAuth().methods());

        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        AclToken login = new AclToken("c3c3c3c3-0000-4000-8000-000000000003", AclSecrets.hash("s"), "login", List.of("d4d4d4d4-0000-4000-8000-000000000004"), List.of("e5e5e5e5-0000-4000-8000-000000000005"), List.of(),
                List.of(new dev.mars.qraft.common.acl.ServiceIdentity("web", "default", "default")), T0, T0.plusSeconds(60), false);
        for (RaftCommand command : List.of(AuthCommand.createMethod(method("workloads")),
                AuthCommand.updateMethod(method("workloads")), AuthCommand.deleteMethod("workloads"),
                AuthCommand.createBindingRule(rule("r1", "workloads", "value.name == \"web\"")),
                AuthCommand.updateBindingRule(rule("r1", "workloads", "")), AuthCommand.deleteBindingRule("r1"),
                AuthCommand.login(login, "workloads"), AuthCommand.logout("c3c3c3c3-0000-4000-8000-000000000003"))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }
}
