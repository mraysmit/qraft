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

package dev.mars.qraft.raft;

import dev.mars.qraft.raft.grpc.RaftConfigurationProto;
import dev.mars.qraft.raft.grpc.RaftServerProto;

/**
 * Converts a {@link RaftConfiguration} to and from its protobuf form, used by configuration log entries and
 * snapshot envelopes alike. Reading goes through the configuration's constructor, so a stored configuration
 * that breaks its rules is refused rather than trusted.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public final class RaftConfigurationCodec {
    private RaftConfigurationCodec() {
    }

    public static RaftConfigurationProto toProto(RaftConfiguration configuration) {
        RaftConfigurationProto.Builder builder = RaftConfigurationProto.newBuilder();
        for (RaftConfiguration.Server server : configuration.servers()) {
            builder.addServers(RaftServerProto.newBuilder()
                    .setServerId(server.serverId()).setName(server.name())
                    .setAddress(server.address()).setVoter(server.voter()));
        }
        return builder.build();
    }

    /** @throws IllegalArgumentException if the stored configuration breaks the configuration's rules */
    public static RaftConfiguration fromProto(RaftConfigurationProto proto) {
        return new RaftConfiguration(proto.getServersList().stream()
                .map(server -> new RaftConfiguration.Server(
                        server.getServerId(), server.getName(), server.getAddress(), server.getVoter()))
                .toList());
    }
}
