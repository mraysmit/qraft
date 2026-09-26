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

package dev.mars.qraft.raft.api;

/**
 * Generic state machine contract for commands committed through Raft.
 *
 * @param <C> command type
 * @param <R> apply result type
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public interface ReplicatedStateMachine<C extends ReplicatedCommand, R> {

    R apply(C command);

    byte[] takeSnapshot();

    void restoreSnapshot(byte[] snapshot);

    long getLastAppliedIndex();

    void setLastAppliedIndex(long index);

    void reset();
}
