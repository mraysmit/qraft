package dev.mars.qraft.controller.api;

import dev.mars.qraft.controller.api.grpc.*;
import dev.mars.qraft.controller.raft.InMemoryTransportSimulator;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.state.DistributedStateRaftCommandCodec;
import dev.mars.qraft.controller.state.GenericStateStore;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DistributedStateGrpcServiceTest {

    private JavaRuntime runtime;
    private RaftNode node;
    private GenericStateStore store;
    private DistributedStateGrpcService service;

    @BeforeEach
    void setUp() throws Exception {
        runtime = JavaRuntime.create();
        InMemoryTransportSimulator.clearAllTransports();
        InMemoryTransportSimulator transport = new InMemoryTransportSimulator("single");
        store = new GenericStateStore();
        node = RaftNode.builder()
                .runtime(runtime)
                .nodeId("single")
                .clusterNodes(Set.of("single"))
                .transport(transport)
                .stateMachine(store)
                .commandCodec(new DistributedStateRaftCommandCodec())
                .mode(RaftNodeMode.volatileMode())
                .electionTimeout(50)
                .heartbeatInterval(20)
                .build();
        node.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!node.isLeader() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(node.isLeader());
        service = new DistributedStateGrpcService(node, store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) node.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        InMemoryTransportSimulator.clearAllTransports();
    }

    @Test
    void putGetListAndDeleteRoundTrip() throws Exception {
        RecordingObserver<PutResponse> put = new RecordingObserver<>();
        service.put(PutRequest.newBuilder().setKey("service/api").setValue("healthy").build(), put);
        assertTrue(put.await().getAccepted());

        RecordingObserver<GetResponse> get = new RecordingObserver<>();
        service.get(GetRequest.newBuilder().setKey("service/api").build(), get);
        assertTrue(get.await().getFound());
        assertEquals("healthy", get.value.getValue());

        RecordingObserver<ListResponse> list = new RecordingObserver<>();
        service.list(ListRequest.getDefaultInstance(), list);
        assertEquals(1, list.await().getEntriesCount());

        RecordingObserver<DeleteResponse> delete = new RecordingObserver<>();
        service.delete(DeleteRequest.newBuilder().setKey("service/api").build(), delete);
        assertTrue(delete.await().getDeleted());

        RecordingObserver<GetResponse> missing = new RecordingObserver<>();
        service.get(GetRequest.newBuilder().setKey("service/api").build(), missing);
        assertFalse(missing.await().getFound());
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private T value;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            completion.completeExceptionally(error);
        }

        @Override
        public void onCompleted() {
            completion.complete(value);
        }

        T await() throws Exception {
            return completion.get(5, TimeUnit.SECONDS);
        }
    }
}
