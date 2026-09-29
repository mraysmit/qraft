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

/**
 * What a leader did for a server asking to join its cluster.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public enum JoinResult {
    /** The server was added as a non-voter; the leader promotes it once it has caught up. */
    JOINED,
    /** The server is already in the configuration, under the same server ID. */
    ALREADY_MEMBER,
    /**
     * Another server ID held the joining server's name or address, as after a server loses its storage, and
     * that entry was removed. The server is added when it asks again.
     */
    REPLACING
}
