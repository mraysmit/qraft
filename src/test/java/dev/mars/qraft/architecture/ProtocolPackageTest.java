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

package dev.mars.qraft.architecture;

import dev.mars.qraft.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.raft.grpc.CommandsProto;
import dev.mars.qraft.raft.grpc.RaftProto;
import dev.mars.qraft.server.api.grpc.DistributedStateServiceGrpc;
import dev.mars.qraft.server.api.grpc.DistributedStateProto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that Java ownership changes leave the public protobuf and gRPC wire namespaces intact.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
class ProtocolPackageTest {
    @Test
    void generatedRaftClassesBelongToRaft() {
        assertEquals("dev.mars.qraft.raft.grpc", RaftServiceGrpc.class.getPackageName());
        assertEquals("dev.mars.qraft.raft.grpc", CommandsProto.class.getPackageName());
    }

    @Test
    void generatedExternalApiBelongsToServer() {
        assertEquals("dev.mars.qraft.server.api.grpc", DistributedStateServiceGrpc.class.getPackageName());
    }

    @Test
    void wireNamespacesAndServiceNamesStayCompatible() {
        assertEquals("qraft.raft", RaftProto.getDescriptor().getPackage());
        assertEquals("qraft.raft", CommandsProto.getDescriptor().getPackage());
        assertEquals("qraft.api", DistributedStateProto.getDescriptor().getPackage());
        assertEquals("qraft.raft.RaftService", RaftServiceGrpc.getServiceDescriptor().getName());
        assertEquals("qraft.api.DistributedStateService", DistributedStateServiceGrpc.getServiceDescriptor().getName());
    }
}
