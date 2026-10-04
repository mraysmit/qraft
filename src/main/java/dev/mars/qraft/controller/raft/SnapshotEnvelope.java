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

package dev.mars.qraft.controller.raft;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.mars.qraft.controller.raft.grpc.RaftConfigurationProto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A snapshot's bytes as a Raft node stores and sends them: the configuration in force at the snapshot's last
 * included index, followed by the state machine's own snapshot. A node restoring a snapshot, from storage or
 * from its leader, restores the configuration with it, because the configuration entries the snapshot covers
 * are compacted away.
 *
 * <p>The layout is the tag {@code QRSE}, a version byte, the configuration's length as a four-byte integer, the
 * configuration in its protobuf form, and then the state machine snapshot.
 *
 * @param configuration the configuration the snapshot covers
 * @param stateMachineSnapshot the state machine's snapshot
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public record SnapshotEnvelope(RaftConfiguration configuration, byte[] stateMachineSnapshot) {
    private static final byte[] TAG = "QRSE".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int HEADER = TAG.length + 1 + Integer.BYTES;

    public static byte[] wrap(RaftConfiguration configuration, byte[] stateMachineSnapshot) {
        byte[] encoded = RaftConfigurationCodec.toProto(configuration).toByteArray();
        return ByteBuffer.allocate(HEADER + encoded.length + stateMachineSnapshot.length)
                .put(TAG).put(VERSION).putInt(encoded.length).put(encoded).put(stateMachineSnapshot)
                .array();
    }

    /**
     * @throws IllegalStateException if the bytes record no configuration, or the envelope is damaged
     */
    public static SnapshotEnvelope unwrap(byte[] data) {
        if (data.length < TAG.length || !Arrays.equals(data, 0, TAG.length, TAG, 0, TAG.length)) {
            throw new IllegalStateException("The snapshot records no cluster configuration: it was taken before "
                    + "Qraft recorded configurations in snapshots, and data from before then is not upgraded");
        }
        if (data.length < HEADER) {
            throw damaged("the header is incomplete");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, TAG.length, data.length - TAG.length);
        byte version = buffer.get();
        if (version != VERSION) {
            throw damaged("unknown version " + version);
        }
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            throw damaged("the configuration is cut short");
        }
        try {
            RaftConfiguration configuration = RaftConfigurationCodec.fromProto(
                    RaftConfigurationProto.parseFrom(ByteBuffer.wrap(data, HEADER, length)));
            return new SnapshotEnvelope(configuration, Arrays.copyOfRange(data, HEADER + length, data.length));
        } catch (InvalidProtocolBufferException | IllegalArgumentException unreadable) {
            throw damaged("the configuration cannot be read: " + unreadable.getMessage());
        }
    }

    private static IllegalStateException damaged(String reason) {
        return new IllegalStateException("Damaged snapshot envelope: " + reason);
    }
}
