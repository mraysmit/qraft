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

package dev.mars.qraft.state.session;

import dev.mars.qraft.raft.grpc.SessionCommandProto;
import dev.mars.qraft.raft.grpc.SessionDestroyProto;
import dev.mars.qraft.raft.grpc.SessionProto;

import java.time.Duration;
import java.time.Instant;

/**
 * The protobuf form of {@link SessionCommand} (sessions list, Phase 1); durations and times in milliseconds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class SessionCodec {
    private SessionCodec() { }

    public static SessionCommandProto toProto(SessionCommand command) {
        return switch (command) {
            case SessionCommand.Create create -> {
                Session session = create.session();
                yield SessionCommandProto.newBuilder().setCreate(SessionProto.newBuilder().setId(session.id())
                        .setNode(session.node()).setName(session.name()).setBehavior(session.behavior().apiName())
                        .setTtlMs(session.ttl().toMillis()).setLockDelayMs(session.lockDelay().toMillis())
                        .setCreateTimeMs(session.createTime().toEpochMilli())).build();
            }
            case SessionCommand.Destroy destroy -> {
                SessionDestroyProto.Builder proto = SessionDestroyProto.newBuilder().setId(destroy.id())
                        .setAtMs(destroy.at().toEpochMilli());
                // An API destroy is written as before, so a server without causes reads it the same way.
                if (destroy.cause() != SessionCommand.Cause.DESTROYED) proto.setCause(destroy.cause().reason());
                yield SessionCommandProto.newBuilder().setDestroy(proto).build();
            }
        };
    }

    public static SessionCommand fromProto(SessionCommandProto proto) {
        return switch (proto.getCommandCase()) {
            case CREATE -> {
                SessionProto session = proto.getCreate();
                yield new SessionCommand.Create(new Session(session.getId(), session.getNode(), session.getName(),
                        SessionBehavior.ofApiName(session.getBehavior()), Duration.ofMillis(session.getTtlMs()),
                        Duration.ofMillis(session.getLockDelayMs()), 0, Instant.ofEpochMilli(session.getCreateTimeMs())));
            }
            case DESTROY -> new SessionCommand.Destroy(proto.getDestroy().getId(),
                    Instant.ofEpochMilli(proto.getDestroy().getAtMs()), cause(proto.getDestroy().getCause()));
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("a session command holds no change");
        };
    }

    /** A destroy's cause as written; none is a destroy through the API, as every entry written before causes is. */
    private static SessionCommand.Cause cause(String written) {
        if (written.isEmpty()) return SessionCommand.Cause.DESTROYED;
        for (SessionCommand.Cause cause : SessionCommand.Cause.values()) {
            if (cause.reason().equals(written)) return cause;
        }
        throw new IllegalArgumentException("a session destroy names an unknown cause: " + written);
    }
}
