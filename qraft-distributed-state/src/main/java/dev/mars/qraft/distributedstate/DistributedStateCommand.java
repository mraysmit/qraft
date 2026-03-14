package dev.mars.qraft.distributedstate;

import dev.mars.qraft.raft.api.ReplicatedCommand;

import java.util.Objects;

/**
 * Generic replicated key-value command model.
 */
public sealed interface DistributedStateCommand extends ReplicatedCommand
        permits DistributedStateCommand.Put, DistributedStateCommand.Delete {

    String key();

    record Put(String key, String value) implements DistributedStateCommand {
        private static final long serialVersionUID = 1L;

        public Put {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record Delete(String key) implements DistributedStateCommand {
        private static final long serialVersionUID = 1L;

        public Delete {
            Objects.requireNonNull(key, "key");
        }
    }

    static DistributedStateCommand put(String key, String value) {
        return new Put(key, value);
    }

    static DistributedStateCommand delete(String key) {
        return new Delete(key);
    }
}
