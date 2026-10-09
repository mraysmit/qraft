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

package dev.mars.qraft.server.http;

/**
 * JSON body of {@code PUT /v1/catalog/node/heartbeat}, for the node named by the request's identity.
 *
 * <p>A heartbeat carries no status and no time: it makes the node healthy, and the receiving server's clock
 * gives the time of the contact.
 *
 * @param sequenceNumber the heartbeat's place in its registration's sequence; zero or absent means unsequenced
 * @param registrationId the registration the heartbeat belongs to; absent means any
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
record NodeHeartbeatRequest(long sequenceNumber, String registrationId) {

    NodeHeartbeatRequest {
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must not be negative");
        }
    }
}
