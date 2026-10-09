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

import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.common.ClientCapabilities;

import java.time.Instant;
import java.util.Objects;

/**
 * Sealed interface for client lifecycle commands.
 *
 * <p>Each permitted subtype carries only the fields relevant to its operation,
 * eliminating nullable "bag-of-fields" patterns. Pattern matching in
 * {@code switch} expressions provides compile-time exhaustiveness.
 *
 * <h3>Permitted subtypes</h3>
 * <ul>
 *   <li>{@link Register} — register a new client</li>
 *   <li>{@link Deregister} — deregister a client</li>
 *   <li>{@link UpdateStatus} — change client status</li>
 *   <li>{@link UpdateCapabilities} — update client capabilities</li>
 *   <li>{@link Heartbeat} — record a client heartbeat</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.0
 * @since 2025-08-26
 */
public sealed interface ClientCommand extends RaftCommand
        permits ClientCommand.Register,
                ClientCommand.Deregister,
                ClientCommand.UpdateStatus,
                ClientCommand.UpdateCapabilities,
                ClientCommand.Heartbeat,
                ClientCommand.Expire {

    /** Common accessor: every subtype carries a client ID. */
    String clientId();

    /** Common accessor: every subtype carries a timestamp. */
    Instant timestamp();

    /**
     * Register a new client.
     *
     * @param clientId   the client identifier
     * @param clientInfo the full client information
     * @param timestamp the command timestamp
     */
    record Register(String clientId, ClientInfo clientInfo, Instant timestamp) implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public Register {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(clientInfo, "clientInfo");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Deregister a client.
     *
     * @param clientId   the client identifier
     * @param timestamp the command timestamp
     */
    record Deregister(String clientId, Instant timestamp) implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public Deregister {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Update the status of an existing client.
     *
     * @param clientId        the client identifier
     * @param expectedStatus the expected current status for CAS validation (null to skip check)
     * @param newStatus      the new status
     * @param timestamp      the command timestamp
     */
    record UpdateStatus(String clientId, ClientStatus expectedStatus, ClientStatus newStatus, Instant timestamp) implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public UpdateStatus {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(expectedStatus, "expectedStatus");
            Objects.requireNonNull(newStatus, "newStatus");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Update the capabilities of an existing client.
     *
     * @param clientId         the client identifier
     * @param newCapabilities the new capabilities
     * @param timestamp       the command timestamp
     */
    record UpdateCapabilities(String clientId, ClientCapabilities newCapabilities, Instant timestamp) implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public UpdateCapabilities {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(newCapabilities, "newCapabilities");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
        }
    }

    /**
     * Record a client heartbeat.
     *
     * @param clientId   the client identifier
     * @param status    optional status update with heartbeat (may be null)
     * @param timestamp the command timestamp
     * @param sequenceNumber sender-local ordering value; zero means unsequenced
     */
    record Heartbeat(String clientId, ClientStatus status, Instant timestamp,
                     long sequenceNumber, String registrationId) implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public Heartbeat {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(timestamp, "timestamp");
            timestamp = replicated(timestamp);
            if (sequenceNumber < 0) throw new IllegalArgumentException("sequenceNumber must not be negative");
            // A blank registration identifier means "no registration check" on every replica.
            if (registrationId != null && registrationId.isBlank()) registrationId = null;
            // status may be null — heartbeat doesn't always carry a status update
        }

        public Heartbeat(String clientId, ClientStatus status, Instant timestamp) {
            this(clientId, status, timestamp, 0, null);
        }

        public Heartbeat(String clientId, ClientStatus status, Instant timestamp, long sequenceNumber) {
            this(clientId, status, timestamp, sequenceNumber, null);
        }
    }

    /**
     * Leader-proposed membership expiry. Without {@code reap}, it marks the node unreachable; with
     * {@code reap}, it removes an already unreachable node together with every service instance it
     * registered. Either applies only while the node's last contact, its last heartbeat or else its
     * registration time, still equals {@code expectedLastContact}, so a heartbeat or re-registration
     * committed first turns a stale command into a no-op.
     */
    record Expire(String clientId, Instant expectedLastContact, boolean reap, Instant timestamp)
            implements ClientCommand {
        private static final long serialVersionUID = 1L;

        public Expire {
            Objects.requireNonNull(clientId, "clientId");
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

    // ── Factory methods (preserve existing API) ─────────────────

    /**
     * Create a command to register a new client.
     */
    static ClientCommand register(ClientInfo clientInfo) {
        return register(clientInfo, Instant.now());
    }

    /** Registration stamped with the proposing server's clock, which membership expiry relies on. */
    static ClientCommand register(ClientInfo clientInfo, Instant timestamp) {
        return new Register(clientInfo.getClientId(), clientInfo, timestamp);
    }

    /**
     * Create a command to deregister a client.
     */
    static ClientCommand expire(String clientId, Instant expectedLastContact, boolean reap, Instant timestamp) {
        return new Expire(clientId, expectedLastContact, reap, timestamp);
    }

    static ClientCommand deregister(String clientId) {
        return new Deregister(clientId, Instant.now());
    }

    /**
     * Create a command to update a client's status with CAS protection.
     *
     * @param clientId        the client identifier
     * @param expectedStatus the expected current status (must match for command to apply)
     * @param newStatus      the new status
     */
    static ClientCommand updateStatus(String clientId, ClientStatus expectedStatus, ClientStatus newStatus) {
        return new UpdateStatus(clientId, expectedStatus, newStatus, Instant.now());
    }

    /**
     * Create a command to update a client's capabilities.
     */
    static ClientCommand updateCapabilities(String clientId, ClientCapabilities newCapabilities) {
        return new UpdateCapabilities(clientId, newCapabilities, Instant.now());
    }

    /**
     * Create a command to record a client heartbeat.
     */
    static ClientCommand heartbeat(String clientId) {
        return new Heartbeat(clientId, null, Instant.now(), 0, null);
    }

    /**
     * Create a command to record a client heartbeat with status.
     */
    static ClientCommand heartbeat(String clientId, ClientStatus status, Instant timestamp) {
        return heartbeat(clientId, status, timestamp, 0);
    }

    /**
     * Create a sequenced heartbeat command. Sequence zero means the sender does not
     * participate in ordering; positive values must increase for each client.
     */
    static ClientCommand heartbeat(String clientId, ClientStatus status, Instant timestamp, long sequenceNumber) {
        return heartbeat(clientId, status, timestamp, sequenceNumber, null);
    }

    static ClientCommand heartbeat(String clientId, ClientStatus status, Instant timestamp, long sequenceNumber,
                                  String registrationId) {
        return new Heartbeat(clientId, status, timestamp != null ? timestamp : Instant.now(), sequenceNumber,
                registrationId);
    }
}
