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
 * Lifecycle status of a node in the replicated node registry. These are the three statuses Qraft sets: a node
 * registers, heartbeats, and is marked unreachable when its heartbeats stop.
 *
 * <p>Earlier versions had more: {@code degraded}, {@code maintenance}, {@code failed}, and
 * {@code deregistered}, and before them the job system's {@code active}, {@code idle}, {@code overloaded}, and
 * {@code draining}. Nothing in Qraft ever set them. Replicated state that holds one reads through
 * {@link #fromStoredValue}, as the status that says whether the node was in contact; API input through the
 * strict {@link #fromValue} rejects them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
public enum NodeStatus {

    /** The node has registered and has not yet sent a heartbeat. */
    REGISTERING("registering"),

    /** The node is registered and heartbeating. */
    HEALTHY("healthy"),

    /** The node has stopped heartbeating for longer than the node TTL. */
    UNREACHABLE("unreachable");

    private final String value;

    NodeStatus(String value) {
        this.value = value;
    }

    /** The status's wire and JSON value. */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Parses a status from API input, case-insensitively.
     *
     * @throws IllegalArgumentException if the value is {@code null} or not a current status
     */
    public static NodeStatus fromValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Node status value must not be null");
        }
        for (NodeStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown node status: " + value);
    }

    /**
     * Reads a status from replicated JSON written by any version. A removed status that a node in contact could
     * hold reads as {@link #HEALTHY}; {@code failed} and {@code deregistered} read as {@link #UNREACHABLE}.
     * Every replica therefore restores the same state.
     */
    @JsonCreator
    public static NodeStatus fromStoredValue(String value) {
        if (value != null) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "active", "idle", "degraded", "overloaded", "maintenance", "draining" -> {
                    return HEALTHY;
                }
                case "failed", "deregistered" -> {
                    return UNREACHABLE;
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
