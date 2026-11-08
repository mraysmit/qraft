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

package dev.mars.qraft.state.tenancy;

import dev.mars.qraft.raft.RaftCommand;

import java.util.Objects;

/**
 * A write that applies only where its tenant and namespace exist (tenancy list, Phase 2, decision 3). The servers
 * propose registrations, key sets and acquisitions wrapped in it, so the state machine checks the scope at the entry's
 * place in the log, and a write racing a deletion cannot land in a deleted namespace; an entry written before tenancy
 * holds the command alone, and applies as it always did.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record ScopedWrite(String tenant, String namespace, RaftCommand command) implements RaftCommand {
    private static final long serialVersionUID = 1L;

    public ScopedWrite {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(command, "command");
        if (command instanceof ScopedWrite) throw new IllegalArgumentException("a scoped write holds another");
    }

    public static ScopedWrite of(String tenant, String namespace, RaftCommand command) {
        return new ScopedWrite(tenant, namespace, command);
    }
}
