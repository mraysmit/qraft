/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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
 * Immutable local membership policy, distinct from the replicated cluster configuration.
 * The policy does not itself change the replicated voting configuration.
 * Durations count complete heartbeat rounds and round up without overflowing.
 *
 * @param promotionStabilizationMs continuous health required before promotion
 * @param promotionMaxTrailingEntries maximum permitted log lag for promotion
 * @param cleanupEnabled whether automatic failed-voter removal is enabled
 * @param cleanupFailureTimeoutMs continuous absence required for removal eligibility
 * @param cleanupIntervalMs interval between periodic cleanup passes
 * @param cleanupMinVoters minimum number of voters automatic cleanup must preserve
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-19
 * @version 1.0
 */
public record RaftMembershipPolicy(long promotionStabilizationMs, long promotionMaxTrailingEntries,
                                   boolean cleanupEnabled, long cleanupFailureTimeoutMs,
                                   long cleanupIntervalMs, int cleanupMinVoters) {
    public RaftMembershipPolicy {
        if (promotionStabilizationMs < 0) throw new IllegalArgumentException("promotionStabilizationMs must not be negative");
        if (promotionMaxTrailingEntries < 0) throw new IllegalArgumentException("promotionMaxTrailingEntries must not be negative");
        positive(cleanupFailureTimeoutMs, "cleanupFailureTimeoutMs");
        positive(cleanupIntervalMs, "cleanupIntervalMs");
        positive(cleanupMinVoters, "cleanupMinVoters");
    }

    /** Defaults retain the configured node's election and heartbeat lower bounds. */
    public static RaftMembershipPolicy defaults(long electionTimeoutMs, long heartbeatIntervalMs) {
        positive(electionTimeoutMs, "electionTimeoutMs");
        positive(heartbeatIntervalMs, "heartbeatIntervalMs");
        return new RaftMembershipPolicy(10_000, 250, true,
                Math.max(60_000, electionTimeoutMs), Math.max(10_000, heartbeatIntervalMs), 3);
    }

    /** Validate against the node's final timings, including when cleanup is disabled. */
    public void validateFor(long electionTimeoutMs, long heartbeatIntervalMs) {
        positive(electionTimeoutMs, "electionTimeoutMs");
        positive(heartbeatIntervalMs, "heartbeatIntervalMs");
        if (cleanupFailureTimeoutMs < electionTimeoutMs) {
            throw new IllegalArgumentException("cleanupFailureTimeoutMs must be at least electionTimeoutMs");
        }
        if (cleanupIntervalMs < heartbeatIntervalMs) {
            throw new IllegalArgumentException("cleanupIntervalMs must be at least heartbeatIntervalMs");
        }
    }

    public long promotionStabilizationRounds(long heartbeatIntervalMs) {
        return rounds(promotionStabilizationMs, heartbeatIntervalMs);
    }

    public long cleanupFailureTimeoutRounds(long heartbeatIntervalMs) {
        return rounds(cleanupFailureTimeoutMs, heartbeatIntervalMs);
    }

    public long cleanupIntervalRounds(long heartbeatIntervalMs) {
        return rounds(cleanupIntervalMs, heartbeatIntervalMs);
    }

    private static long rounds(long durationMs, long heartbeatIntervalMs) {
        positive(heartbeatIntervalMs, "heartbeatIntervalMs");
        return Math.ceilDiv(durationMs, heartbeatIntervalMs);
    }

    private static void positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
