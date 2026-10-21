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
 * Immutable local policy for promoting non-voters and automatically removing failed voters.
 * Durations are milliseconds; a node converts them to applied heartbeat rounds using ceiling division.
 * The policy does not itself change the replicated voting configuration.
 *
 * @param promotionStabilizationMs continuous health required before promotion
 * @param promotionMaxTrailingEntries maximum permitted log lag for promotion
 * @param cleanupEnabled whether automatic failed-voter removal is enabled
 * @param cleanupFailureTimeoutMs continuous absence required for removal eligibility
 * @param cleanupIntervalMs interval between periodic cleanup passes
 * @param cleanupMinVoters minimum number of voters automatic cleanup must preserve
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-21
 * @version 1.0
 */
public record RaftMembershipPolicy(long promotionStabilizationMs, long promotionMaxTrailingEntries,
                                   boolean cleanupEnabled, long cleanupFailureTimeoutMs,
                                   long cleanupIntervalMs, int cleanupMinVoters) {
    public RaftMembershipPolicy {
        if (promotionStabilizationMs < 0 || promotionMaxTrailingEntries < 0) {
            throw new IllegalArgumentException("Promotion stabilization and trailing entries must not be negative");
        }
        if (cleanupFailureTimeoutMs < 1 || cleanupIntervalMs < 1 || cleanupMinVoters < 1) {
            throw new IllegalArgumentException("Cleanup failure timeout, interval and minimum voters must be positive");
        }
    }

    /** Defaults preserve existing promotion limits and allow a full election timeout before cleanup. */
    public static RaftMembershipPolicy defaults(long electionTimeoutMs, long heartbeatIntervalMs) {
        return new RaftMembershipPolicy(10_000, 250, true, Math.max(60_000, electionTimeoutMs),
                Math.max(10_000, heartbeatIntervalMs), 3);
    }

    /** Validates against the node's timing before it starts any resource or registers metrics. */
    void validateTiming(long electionTimeoutMs, long heartbeatIntervalMs) {
        if (electionTimeoutMs < 1 || heartbeatIntervalMs < 1) {
            throw new IllegalArgumentException("Election timeout and heartbeat interval must be positive");
        }
        if (cleanupFailureTimeoutMs < electionTimeoutMs) {
            throw new IllegalArgumentException("Cleanup failure timeout must be at least the election timeout");
        }
        if (cleanupIntervalMs < heartbeatIntervalMs) {
            throw new IllegalArgumentException("Cleanup interval must be at least the heartbeat interval");
        }
    }
}
