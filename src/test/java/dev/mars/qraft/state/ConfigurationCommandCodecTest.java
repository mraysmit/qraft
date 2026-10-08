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

package dev.mars.qraft.state;

import dev.mars.qraft.raft.ConfigurationCommand;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftConfiguration;
import dev.mars.qraft.raft.RaftConfiguration.Server;
import dev.mars.qraft.raft.grpc.RaftCommandMessage;
import dev.mars.qraft.raft.grpc.RaftConfigurationProto;
import dev.mars.qraft.raft.grpc.RaftServerProto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link ConfigurationCommand}, the log entry that records a cluster's servers, survives the command
 * codec intact, voter flags included, and that a stored configuration which breaks the configuration's rules is
 * refused when read rather than trusted.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class ConfigurationCommandCodecTest {
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void aConfigurationEntrySurvivesTheCodecWithEveryServerAndVoterFlag() {
        ConfigurationCommand command = new ConfigurationCommand(new RaftConfiguration(List.of(
                new Server("id-a", "a", "a:9080", true),
                new Server("id-b", "b", "b:9080", true),
                new Server("id-c", "c", "c:9080", false))));

        RaftCommand decoded = codec.deserialize(codec.serialize(command));

        assertEquals(command, decoded);
    }

    @Test
    void aStoredConfigurationThatBreaksTheRulesIsRefusedWhenRead() {
        byte[] noVoter = RaftCommandMessage.newBuilder().setConfiguration(RaftConfigurationProto.newBuilder()
                .addServers(RaftServerProto.newBuilder()
                        .setServerId("id-a").setName("a").setAddress("a:9080").setVoter(false)))
                .build().toByteArray();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> codec.deserialize(noVoter));

        assertEquals("a configuration needs at least one voter", refused.getMessage());
    }

    @Test
    void aConfigurationEntryIsNotAStateMachineNoOp() {
        RaftCommand decoded = codec.deserialize(codec.serialize(new ConfigurationCommand(
                new RaftConfiguration(List.of(new Server("id-a", "a", "a:9080", true))))));

        assertInstanceOf(ConfigurationCommand.class, decoded, "an empty command decodes as null, the no-op");
    }
}
