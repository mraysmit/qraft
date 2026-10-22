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

package dev.mars.qraft.server.recovery;

import dev.mars.qraft.raft.RaftConfiguration;

import java.util.Objects;
import java.util.Optional;

/**
 * What {@link OfflineRecovery#inspect} found in a stopped data directory, for choosing the seed: its server ID,
 * persistent term and vote, the last entry of its log, its snapshot and compaction boundary, the configuration in
 * force and where it came from, and why ordinary startup would refuse the directory, if it would.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public record DirectoryReport(String serverId, long currentTerm, Optional<String> votedFor,
                              long lastLogIndex, long lastLogTerm, long snapshotIndex, long snapshotTerm,
                              long compactedThrough, Optional<RaftConfiguration> configuration,
                              ConfigurationSource configurationSource, Optional<String> startupRefusal) {
    public DirectoryReport {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(votedFor, "votedFor");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(configurationSource, "configurationSource");
        Objects.requireNonNull(startupRefusal, "startupRefusal");
    }

    /** Where the configuration in force was found: the latest WAL configuration entry, or the snapshot. */
    public enum ConfigurationSource { NONE, WAL, SNAPSHOT }
}
