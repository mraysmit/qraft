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
import dev.mars.qraft.controller.state.CommandResult;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.GenericStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import io.grpc.stub.StreamObserver;

import java.util.Map;

/**
 * External client-facing gRPC service for replicated key-value state.
 */
public class DistributedStateGrpcService extends DistributedStateServiceGrpc.DistributedStateServiceImplBase {

    private final RaftNode raftNode;
    private final GenericStateStore stateStore;

    public DistributedStateGrpcService(RaftNode raftNode, GenericStateStore stateStore) {
        this.raftNode = raftNode;
        this.stateStore = stateStore;
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        DistributedStateCommand command = DistributedStateCommand.put(request.getKey(), request.getValue());
        raftNode.submitCommand(new DistributedStateRaftCommand(command))
                .onSuccess(result -> {
                    boolean accepted = result instanceof CommandResult.Success<?>;
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
                    boolean deleted = result instanceof CommandResult.Success<?>;
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
