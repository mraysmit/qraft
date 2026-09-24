package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogCommandCodecTest {
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void roundTripsRegistrationAndDeregistration() {
        ServiceInstance instance = new ServiceInstance("search-1", "search", "node-1", "10.0.0.4",
                9090, List.of("primary"), Map.of("zone", "a"), ServiceHealth.WARNING,
                "tenant-a", "production", "dc-1", "eu-west", false);

        assertEquals(CatalogCommand.register(instance), codec.deserialize(codec.serialize(CatalogCommand.register(instance))));
        var identity = instance.identity();
        assertEquals(CatalogCommand.deregister(identity),
                codec.deserialize(codec.serialize(CatalogCommand.deregister(identity))));
    }

    @Test
    void readsLegacyJsonDistributedStateEntries() {
        byte[] legacy = new DistributedStateCommandCodec().serialize(DistributedStateCommand.put("key", "value"));

        assertEquals(new DistributedStateRaftCommand(DistributedStateCommand.put("key", "value")),
                codec.deserialize(legacy));
    }

    @Test
    void rejectsCorruptAndIncompleteCatalogPayloads() {
        assertThrows(RuntimeException.class, () -> codec.deserialize(new byte[]{99, 111, 114, 114, 117, 112, 116}));

        var incomplete = dev.mars.qraft.controller.raft.grpc.RaftCommandMessage.newBuilder()
                .setCatalogCommand(dev.mars.qraft.controller.raft.grpc.CatalogCommandProto.newBuilder()
                        .setType(dev.mars.qraft.controller.raft.grpc.CatalogCommandType.CATALOG_CMD_REGISTER))
                .build()
                .toByteArray();
        assertThrows(IllegalArgumentException.class, () -> codec.deserialize(incomplete));
    }
}
