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

import dev.mars.qraft.common.tls.TlsMaterial;

import java.util.Objects;

/**
 * Mutual TLS on the Raft port (transport security list, decision 4): each server presents its certificate and
 * requires one from its peers, both signed by the cluster's CA, and checks that a peer's certificate names the host
 * of the address it dialled unless {@code verifyServerHostname} is off.
 *
 * @param material             the server's CA, certificate and key
 * @param verifyServerHostname whether a dialled peer's certificate must name the host dialled
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public record RaftTls(TlsMaterial material, boolean verifyServerHostname) {
    public RaftTls {
        Objects.requireNonNull(material, "material");
        material.serverContext();
    }
}
