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

package dev.mars.qraft.state;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.raft.RaftCommand;

import java.time.Instant;
import java.util.Objects;

/**
 * Sealed interface for the commands that change the node registry.
 *
 * <p>Each permitted subtype carries only the fields relevant to its operation,
 * eliminating nullable "bag-of-fields" patterns. Pattern matching in
 * {@code switch} expressions provides compile-time exhaustiveness.
 *
 * <h3>Permitted subtypes</h3>
 * <ul>
 *   <li>{@link Register} — register a node</li>
 *   <li>{@link Deregister} — deregister a node</li>
 *   <li>{@link UpdateStatus} — change a node's status</li>
 *   <li>{@link Heartbeat} — record a node's heartbeat</li>
 *   <li>{@link Expire} — mark a silent node unreachable, or remove it</li>
 * </ul>
 *
 * <p>An earlier version also had a command that replaced a node's capabilities. Qraft never issued it, and
 * the codec skips a log entry that holds one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 3.0
 * @since 2025-08-26
 */
public sealed interface NodeCommand extends RaftCommand
        permits NodeCommand.Register,
                NodeCommand.Deregister,
                NodeCommand.UpdateStatus,
                NodeCommand.Heartbeat,
                NodeCommand.Expire {

    /** Common accessor: every subtype names its node. */
    String name();

    /** Common accessor: every subtype carries a timestamp. */
    Instant timestamp();

    /**
     * Register a node.
     *
     * @param name      the node's name
     * @param node      the node as its client describes it
     * @param timestamp the command timestamp
     */
    record Register(String name, Node node, Instant timestamp) implements NodeCommand {
        private static final long serialVersionUID = 1L;

        public Register {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Deregister a node.
     *
     * @param name      the node's name
     * @param timestamp the command timestamp
     */
    record Deregister(String name, Instant timestamp) implements NodeCommand {
        private static final long serialVersionUID = 1L;

        public Deregister {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Update the status of an existing node.
     *
     * @param name           the node's name
     * @param expectedStatus the status the node must have for the command to apply
     * @param newStatus      the new status
     * @param timestamp      the command timestamp
     */
    record UpdateStatus(String name, NodeStatus expectedStatus, NodeStatus newStatus, Instant timestamp)
            implements NodeCommand {
        private static final long serialVersionUID = 1L;

        public UpdateStatus {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(newStatus, "newStatus");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Record a node's heartbeat.
     *
     * @param name           the node's name
     * @param status         optional status update with the heartbeat (may be null)
     * @param timestamp      the command timestamp
     * @param sequenceNumber sender-local ordering value; zero means unsequenced
     * @param registrationId the registration attempt the heartbeat belongs to, or null for no check
     */
    record Heartbeat(String name, NodeStatus status, Instant timestamp,
                     long sequenceNumber, String registrationId) implements NodeCommand {
        private static final long serialVersionUID = 1L;

        public Heartbeat {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
            if (sequenceNumber < 0) throw new IllegalArgumentException("sequenceNumber must not be negative");
            // A blank registration identifier means "no registration check" on every replica.
            if (registrationId != null && registrationId.isBlank()) registrationId = null;
            // status may be null — heartbeat doesn't always carry a status update
        }

        public Heartbeat(String name, NodeStatus status, Instant timestamp) {
            this(name, status, timestamp, 0, null);
        }

        public Heartbeat(String name, NodeStatus status, Instant timestamp, long sequenceNumber) {
            this(name, status, timestamp, sequenceNumber, null);
        }
    }

    /**
     * Leader-proposed membership expiry. Without {@code reap}, it marks the node unreachable; with
     * {@code reap}, it removes an already unreachable node together with every service instance it
     * registered. Either applies only while the node's last contact, its last heartbeat or else its
     * registration time, still equals {@code expectedLastContact}, so a heartbeat or re-registration
     * committed first turns a stale command into a no-op.
     */
    record Expire(String name, Instant expectedLastContact, boolean reap, Instant timestamp)
            implements NodeCommand {
        private static final long serialVersionUID = 1L;

        public Expire {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(expectedLastContact, "expectedLastContact");
            Objects.requireNonNull(timestamp, "timestamp");
            expectedLastContact = replicated(expectedLastContact);
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Replicated times are stored in milliseconds, so the leader and its followers, which decode the
     * command from the log, hold identical values that expiry commands can match exactly.
     */
    private static Instant replicated(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    // ── Factory methods ─────────────────────────────────────────

    /** Create a command to register a node. */
    static NodeCommand register(Node node) {
        return register(node, Instant.now());
    }

    /** Registration stamped with the proposing server's clock, which membership expiry relies on. */
    static NodeCommand register(Node node, Instant timestamp) {
        return new Register(node.name(), node, timestamp);
    }

    /** Create a command to mark a silent node unreachable, or to remove an unreachable one. */
    static NodeCommand expire(String name, Instant expectedLastContact, boolean reap, Instant timestamp) {
        return new Expire(name, expectedLastContact, reap, timestamp);
    }

    /** Create a command to deregister a node. */
    static NodeCommand deregister(String name) {
        return new Deregister(name, Instant.now());
    }

    /**
     * Create a command to update a node's status with CAS protection.
     *
     * @param name           the node's name
     * @param expectedStatus the expected current status (must match for the command to apply)
     * @param newStatus      the new status
     */
    static NodeCommand updateStatus(String name, NodeStatus expectedStatus, NodeStatus newStatus) {
        return new UpdateStatus(name, expectedStatus, newStatus, Instant.now());
    }

    /** Create a command to record a node's heartbeat. */
    static NodeCommand heartbeat(String name) {
        return new Heartbeat(name, null, Instant.now(), 0, null);
    }

    /** Create a command to record a node's heartbeat with a status. */
    static NodeCommand heartbeat(String name, NodeStatus status, Instant timestamp) {
        return heartbeat(name, status, timestamp, 0);
    }

    /**
     * Create a sequenced heartbeat command. Sequence zero means the sender does not
     * participate in ordering; positive values must increase for each node.
     */
    static NodeCommand heartbeat(String name, NodeStatus status, Instant timestamp, long sequenceNumber) {
        return heartbeat(name, status, timestamp, sequenceNumber, null);
    }

    static NodeCommand heartbeat(String name, NodeStatus status, Instant timestamp, long sequenceNumber,
                                 String registrationId) {
        return new Heartbeat(name, status, timestamp != null ? timestamp : Instant.now(), sequenceNumber,
                registrationId);
    }
}
