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

package dev.mars.qraft.state.kv;

import com.google.protobuf.ByteString;
import dev.mars.qraft.raft.grpc.KvCommandProto;
import dev.mars.qraft.raft.grpc.KvDeleteProto;
import dev.mars.qraft.raft.grpc.KvSetProto;

/**
 * The protobuf form of {@link KvCommand} (key/value list, Phase 1).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KvCodec {
    private KvCodec() { }

    public static KvCommandProto toProto(KvCommand command) {
        KvCommandProto.Builder proto = KvCommandProto.newBuilder()
                .setTenant(command.scope().tenant()).setNamespace(command.scope().namespace());
        switch (command) {
            case KvCommand.Set set -> {
                KvSetProto.Builder body = KvSetProto.newBuilder().setKey(set.key())
                        .setValue(ByteString.copyFrom(set.value())).setFlags(set.flags());
                if (set.cas() != null) body.setCas(set.cas());
                proto.setSet(body);
            }
            case KvCommand.Delete delete -> {
                KvDeleteProto.Builder body = KvDeleteProto.newBuilder().setKey(delete.key());
                if (delete.cas() != null) body.setCas(delete.cas());
                proto.setDelete(body);
            }
            case KvCommand.DeleteTree tree -> proto.setDeleteTreePrefix(tree.prefix());
            case KvCommand.Lock lock -> proto.setLock(dev.mars.qraft.raft.grpc.KvLockProto.newBuilder().setKey(lock.key())
                    .setValue(ByteString.copyFrom(lock.value())).setFlags(lock.flags()).setSession(lock.session())
                    .setAtMs(lock.at().toEpochMilli()));
            case KvCommand.Unlock unlock -> proto.setUnlock(dev.mars.qraft.raft.grpc.KvUnlockProto.newBuilder()
                    .setKey(unlock.key()).setValue(ByteString.copyFrom(unlock.value())).setFlags(unlock.flags())
                    .setSession(unlock.session()));
        }
        return proto.build();
    }

    public static KvCommand fromProto(KvCommandProto proto) {
        KvScope scope = new KvScope(proto.getTenant(), proto.getNamespace());
        return switch (proto.getCommandCase()) {
            case SET -> new KvCommand.Set(scope, proto.getSet().getKey(), proto.getSet().getValue().toByteArray(),
                    proto.getSet().getFlags(), proto.getSet().hasCas() ? proto.getSet().getCas() : null);
            case DELETE -> new KvCommand.Delete(scope, proto.getDelete().getKey(),
                    proto.getDelete().hasCas() ? proto.getDelete().getCas() : null);
            case DELETE_TREE_PREFIX -> new KvCommand.DeleteTree(scope, proto.getDeleteTreePrefix());
            case LOCK -> new KvCommand.Lock(scope, proto.getLock().getKey(), proto.getLock().getValue().toByteArray(),
                    proto.getLock().getFlags(), proto.getLock().getSession(),
                    java.time.Instant.ofEpochMilli(proto.getLock().getAtMs()));
            case UNLOCK -> new KvCommand.Unlock(scope, proto.getUnlock().getKey(),
                    proto.getUnlock().getValue().toByteArray(), proto.getUnlock().getFlags(), proto.getUnlock().getSession());
            case COMMAND_NOT_SET -> throw new IllegalArgumentException("a key/value command holds no write");
        };
    }
}
