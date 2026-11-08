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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the replicated {@code kubernetes} auth method (Kubernetes auth method list, Phase 1, decisions 1 and 2): it is
 * created with its host, CA and reviewer token, carried by the snapshot beside a {@code jwt} method, whose snapshot form
 * is unchanged, and round-trips through the codec; a method whose type and configuration disagree, or whose
 * configuration lacks a part, is refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class QraftStateStoreKubernetesAuthTest {
    private static final String CA = "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\n-----END CERTIFICATE-----";
    static final KubernetesConfig CONFIG = new KubernetesConfig("https://kubernetes.default.svc:443", CA,
            "reviewer-token");
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private RaftCommandResult<?> apply(RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    static AuthMethod kube(String name, KubernetesConfig config) {
        return new AuthMethod(name, "kubernetes", "Pods", Duration.ofMinutes(10), config, 0, 0);
    }

    private static void refused(RaftCommandResult<?> result) {
        assertEquals("invalid_auth_method", assertInstanceOf(RaftCommandResult.Refused.class, result).code(),
                result.toString());
    }

    @Test
    void aKubernetesMethodIsCreatedSnapshottedBesideAJwtMethodAndRoundTrips() throws Exception {
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.createMethod(kube("pods", CONFIG))));
        assertInstanceOf(RaftCommandResult.Success.class,
                apply(AuthCommand.createMethod(QraftStateStoreAuthTest.method("workloads"))));
        assertEquals(CONFIG, store.getAuth().method("pods").orElseThrow().config());
        assertInstanceOf(RaftCommandResult.Success.class, apply(AuthCommand.createBindingRule(
                QraftStateStoreAuthTest.rule("r1", "pods", "value.name == \"web\""))));

        byte[] snapshot = store.takeSnapshot();
        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(snapshot);
        assertEquals(store.getAuth().methods(), restored.getAuth().methods());
        ObjectNode jwt = null;
        for (var method : new ObjectMapper().readTree(snapshot).path("auth").path("methods")) {
            if (method.path("name").asText().equals("workloads")) jwt = (ObjectNode) method.path("config");
        }
        assertTrue(jwt != null && jwt.has("publicKeysPem") && jwt.size() == 8,
                "a jwt method's configuration is written as before, with no type of its own: " + jwt);

        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (RaftCommand command : List.of(AuthCommand.createMethod(kube("pods", CONFIG)),
                AuthCommand.updateMethod(kube("pods", new KubernetesConfig("https://10.0.0.1:6443", CA, "next"))))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }

    @Test
    void aMethodWhoseTypeAndConfigurationDisagreeOrWhoseConfigurationIsIncompleteIsRefused() {
        refused(apply(AuthCommand.createMethod(kube("nohost", new KubernetesConfig("", CA, "reviewer-token")))));
        refused(apply(AuthCommand.createMethod(kube("noca", new KubernetesConfig("https://k8s", "", "reviewer-token")))));
        refused(apply(AuthCommand.createMethod(kube("nojwt", new KubernetesConfig("https://k8s", CA, "")))));
        refused(apply(AuthCommand.createMethod(new AuthMethod("mixed", "jwt", "", Duration.ofHours(1), CONFIG, 0, 0))));
        refused(apply(AuthCommand.createMethod(new AuthMethod("other", "oidc", "", Duration.ofHours(1), CONFIG, 0, 0))));
        assertTrue(store.getAuth().methods().isEmpty());
    }
}
