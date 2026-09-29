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

package dev.mars.qraft.controller.raft;

import java.io.Serializable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The servers a Raft cluster replicates to, as recorded in the replicated log. Each server has a durable server ID,
 * a name, an address, and whether it votes. Elections and commits need a majority of the voters, counted by
 * server ID, so a server that lost its storage, and with it its ID, is not counted until it is added again.
 *
 * <p>Servers are kept in name order, so every server that builds the same set holds an equal configuration.
 *
 * @param servers the servers, in name order
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
public record RaftConfiguration(List<Server> servers) implements Serializable {

    /** One server in a configuration; a non-voter receives the log but is not counted towards any majority. */
    public record Server(String serverId, String name, String address, boolean voter) implements Serializable {
    }

    public RaftConfiguration {
        for (Server server : servers) {
            if (isBlank(server.serverId()) || isBlank(server.name()) || isBlank(server.address())) {
                throw new IllegalArgumentException("a server needs an ID, a name, and an address");
            }
        }
        if (servers.stream().noneMatch(Server::voter)) {
            throw new IllegalArgumentException("a configuration needs at least one voter");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Server server : servers) {
            if (!ids.add(server.serverId())) {
                throw new IllegalArgumentException("server ID " + server.serverId() + " appears twice");
            }
            if (!names.add(server.name())) {
                throw new IllegalArgumentException("server name " + server.name() + " appears twice");
            }
        }
        servers = servers.stream().sorted(Comparator.comparing(Server::name)).toList();
    }

    /** The server IDs of the voters. */
    public Set<String> voterIds() {
        return servers.stream().filter(Server::voter).map(Server::serverId).collect(Collectors.toUnmodifiableSet());
    }

    /** The number of voters that makes a majority. */
    public int quorum() {
        return voterIds().size() / 2 + 1;
    }

    /** Whether {@code serverIds} include a majority of the voters; other IDs are ignored. */
    public boolean hasQuorum(Set<String> serverIds) {
        return voterIds().stream().filter(serverIds::contains).count() >= quorum();
    }

    public boolean isVoter(String serverId) {
        return server(serverId).map(Server::voter).orElse(false);
    }

    public Optional<Server> server(String serverId) {
        return servers.stream().filter(server -> server.serverId().equals(serverId)).findFirst();
    }

    public Optional<Server> serverNamed(String name) {
        return servers.stream().filter(server -> server.name().equals(name)).findFirst();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
