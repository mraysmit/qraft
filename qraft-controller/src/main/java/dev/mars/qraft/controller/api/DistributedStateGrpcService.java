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

package dev.mars.qraft.controller.api;

import dev.mars.qraft.controller.api.grpc.DeleteRequest;
import dev.mars.qraft.controller.api.grpc.DeleteResponse;
import dev.mars.qraft.controller.api.grpc.DistributedStateServiceGrpc;
import dev.mars.qraft.controller.api.grpc.Entry;
import dev.mars.qraft.controller.api.grpc.GetRequest;
import dev.mars.qraft.controller.api.grpc.GetResponse;
import dev.mars.qraft.controller.api.grpc.ListRequest;
import dev.mars.qraft.controller.api.grpc.ListResponse;
import dev.mars.qraft.controller.api.grpc.PutRequest;
import dev.mars.qraft.controller.api.grpc.PutResponse;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import io.grpc.stub.StreamObserver;

import java.util.Map;

/**
 * External client-facing gRPC service for replicated key-value state.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public class DistributedStateGrpcService extends DistributedStateServiceGrpc.DistributedStateServiceImplBase {

    private final RaftNode raftNode;
    private final QraftStateStore stateStore;

    public DistributedStateGrpcService(RaftNode raftNode, QraftStateStore stateStore) {
        this.raftNode = raftNode;
        this.stateStore = stateStore;
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        DistributedStateCommand command = DistributedStateCommand.put(request.getKey(), request.getValue());
        raftNode.submitCommand(new DistributedStateRaftCommand(command))
                .onSuccess(result -> {
                    boolean accepted = result instanceof RaftCommandResult.Success<?>;
                    responseObserver.onNext(PutResponse.newBuilder().setAccepted(accepted).build());
                    responseObserver.onCompleted();
                })
                .onFailure(responseObserver::onError);
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        String value = stateStore.getMetadata().get(request.getKey());
        boolean found = value != null;
        GetResponse.Builder builder = GetResponse.newBuilder().setFound(found);
        if (found) {
            builder.setValue(value);
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responseObserver) {
        DistributedStateCommand command = DistributedStateCommand.delete(request.getKey());
        raftNode.submitCommand(new DistributedStateRaftCommand(command))
                .onSuccess(result -> {
                    boolean deleted = result instanceof RaftCommandResult.Success<?>;
                    responseObserver.onNext(DeleteResponse.newBuilder().setDeleted(deleted).build());
                    responseObserver.onCompleted();
                })
                .onFailure(responseObserver::onError);
    }

    @Override
    public void list(ListRequest request, StreamObserver<ListResponse> responseObserver) {
        ListResponse.Builder builder = ListResponse.newBuilder();
        for (Map.Entry<String, String> entry : stateStore.getMetadata().entrySet()) {
            builder.addEntries(Entry.newBuilder().setKey(entry.getKey()).setValue(entry.getValue()).build());
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
