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

/**
 * One server as the leader sees it (observability list, Phase 3, decision 5), Consul's autopilot server health: its
 * last contact with the leader in milliseconds, 0 for the leader and -1 before it has answered in this leadership; the
 * term and index it holds as far as the leader knows; and whether it is healthy, having answered within an election
 * timeout and holding the leader's log to within {@value ClusterHealth#MAX_TRAILING_LOGS} entries.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record ServerHealth(String serverId, String name, String address, boolean leader, boolean voter,
                           long lastContactMillis, long lastTerm, long lastIndex, boolean healthy) {
}
