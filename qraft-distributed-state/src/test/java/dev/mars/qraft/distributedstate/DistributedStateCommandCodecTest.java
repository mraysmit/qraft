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

package dev.mars.qraft.distributedstate;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link DistributedStateCommandCodec} round trips of put and delete commands and rejection
 * of unknown types and malformed payloads.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
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
