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

package dev.mars.qraft.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Lifecycle status of a client-mode node in the replicated node registry.
 *
 * <ul>
 * <li><strong>Healthy</strong> ({@code HEALTHY}): the node is registered and heartbeating.</li>
 * <li><strong>Problematic</strong> ({@code DEGRADED}, {@code UNREACHABLE}, {@code FAILED}): the node
 * reports degradation, has stopped heartbeating, or needs intervention.</li>
 * <li><strong>Transitional</strong> ({@code REGISTERING}, {@code MAINTENANCE}): the node is joining or has
 * been taken out of service on purpose.</li>
 * <li><strong>Terminal</strong> ({@code DEREGISTERED}): the node has been removed.</li>
 * </ul>
 *
 * <p>{@code FAILED} is not terminal: a failed node can still be deregistered. The statuses of the job system
 * Qraft was derived from ({@code active}, {@code idle}, {@code overloaded}, {@code draining}) no longer exist.
 * Replicated state written before their removal reads through {@link #fromStoredValue}; API input through the
 * strict {@link #fromValue} rejects them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-26
 * @version 2.0
 */
public enum AgentStatus {

    /** The node has registered and has not yet sent a heartbeat. */
    REGISTERING("registering", "Agent is registering with the controller", false),

    /** The node is registered and heartbeating. */
    HEALTHY("healthy", "Agent is healthy", true),

    /** The node reports degradation but is still operating. */
    DEGRADED("degraded", "Agent is experiencing performance issues", true),

    /** The node has been taken out of service on purpose. */
    MAINTENANCE("maintenance", "Agent is in maintenance mode", false),

    /** The node has stopped heartbeating for longer than the node TTL. */
    UNREACHABLE("unreachable", "Agent is unreachable", false),

    /**
     * The node has encountered a critical error and requires intervention. This is not terminal: the node
     * can still be {@linkplain #DEREGISTERED deregistered}.
     */
    FAILED("failed", "Agent has failed and requires intervention", false),

    /** The node has been removed. This is the only terminal status. */
    DEREGISTERED("deregistered", "Agent has been deregistered", false);

    private final String value;
    private final String description;
    private final boolean operational;

    /**
     * @param value       the status's wire and JSON value
     * @param description a human-readable description
     * @param operational whether a node in this status is operating
     */
    AgentStatus(String value, String description, boolean operational) {
        this.value = value;
        this.description = description;
        this.operational = operational;
    }

    /** The status's wire and JSON value. */
    @JsonValue
    public String getValue() {
        return value;
    }

    /** A human-readable description of the status. */
    public String getDescription() {
        return description;
    }

    /** True for {@code HEALTHY} and {@code DEGRADED}: the node is operating. */
    public boolean isOperational() {
        return operational;
    }

    /** True only for {@code HEALTHY}; stricter than {@link #isOperational()}, which includes {@code DEGRADED}. */
    public boolean isHealthy() {
        return this == HEALTHY;
    }

    /** True for {@code DEGRADED}, {@code UNREACHABLE}, and {@code FAILED}. */
    public boolean isProblematic() {
        return this == DEGRADED || this == UNREACHABLE || this == FAILED;
    }

    /** True for {@code REGISTERING} and {@code MAINTENANCE}. */
    public boolean isTransitional() {
        return this == REGISTERING || this == MAINTENANCE;
    }

    /** True only for {@link #DEREGISTERED}, which has no outgoing transition. */
    public boolean isTerminal() {
        return this == DEREGISTERED;
    }

    // ── Parsing ────────────────────────────────────────────────────────

    /**
     * Parses a status from API input, case-insensitively.
     *
     * @throws IllegalArgumentException if the value is {@code null} or not a current status
     */
    public static AgentStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Agent status value must not be null");
        }
        for (AgentStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown agent status: " + value);
    }

    /**
     * Reads a status from replicated JSON written by any version. A snapshot written before the job system's
     * statuses were removed may hold {@code active} or {@code idle}, which read as {@link #HEALTHY};
     * {@code overloaded}, which reads as {@link #DEGRADED}; or {@code draining}, which reads as
     * {@link #MAINTENANCE}. Every replica therefore restores the same state.
     */
    @JsonCreator
    public static AgentStatus fromStoredValue(String value) {
        if (value != null) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "active", "idle" -> {
                    return HEALTHY;
                }
                case "overloaded" -> {
                    return DEGRADED;
                }
                case "draining" -> {
                    return MAINTENANCE;
                }
                default -> {
                    // A current status.
                }
            }
        }
        return fromValue(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
