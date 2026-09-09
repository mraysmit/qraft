package dev.mars.qraft.distributedstate;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedStateCommandCodecTest {

    private final DistributedStateCommandCodec codec = new DistributedStateCommandCodec();

    @Test
    void roundTripsPutCommand() {
        DistributedStateCommand command = new DistributedStateCommand.Put("service/name", "value");

        assertEquals(command, codec.deserialize(codec.serialize(command)));
    }

    @Test
    void roundTripsDeleteCommand() {
        DistributedStateCommand command = new DistributedStateCommand.Delete("service/name");

        assertEquals(command, codec.deserialize(codec.serialize(command)));
    }

    @Test
    void rejectsUnknownCommandType() {
        byte[] payload = "{\"type\":\"PATCH\",\"key\":\"key\",\"value\":\"value\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> codec.deserialize(payload));
    }

    @Test
    void rejectsMalformedPayload() {
        assertThrows(RuntimeException.class,
                () -> codec.deserialize("not-json".getBytes(StandardCharsets.UTF_8)));
    }
}
