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

package dev.mars.qraft.client.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HttpCheck;
import dev.mars.qraft.client.health.TcpCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link RegistrationStore}: what the local API registers is kept atomically in the data directory, reads back
 * as it was, and is never replaced when it cannot be read.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
class RegistrationStoreTest {
    private static final ServiceDefinition API = new ServiceDefinition("api-1", "api", "10.0.0.5", 8080,
            List.of("v1"), Map.of("team", "platform"), true);
    private static final List<HealthCheckDefinition> CHECKS = List.of(
            new HttpCheck("api-1", "http", URI.create("http://127.0.0.1:8080/health"), Duration.ofSeconds(10),
                    Duration.ofSeconds(2), Duration.ofSeconds(30), false, Duration.ofMinutes(5)),
            new TcpCheck("api-1", "tcp", "10.0.0.7", 9090, Duration.ofSeconds(5), Duration.ofSeconds(1),
                    Duration.ofSeconds(15), true, Duration.ZERO, Duration.ofMillis(500)),
            // A check kept for a service the configuration file defines: the store does not hold that service.
            new TtlCheck("from-the-file", "app", Duration.ofSeconds(30), true, Duration.ofMinutes(1)));

    @TempDir
    Path directory;

    @Test
    void nothingKeptReadsAsNothing() throws Exception {
        assertEquals(new RegistrationStore.Kept(List.of(), List.of()), new RegistrationStore(directory).read());
    }

    @Test
    void whatIsWrittenReadsBackAsItWasIncludingACheckForAServiceItDoesNotHold() throws Exception {
        RegistrationStore store = new RegistrationStore(directory);
        RegistrationStore.Kept kept = new RegistrationStore.Kept(List.of(API), CHECKS);

        store.write(kept);

        assertEquals(kept, new RegistrationStore(directory).read());
    }

    @Test
    void theFileIsVersionedAndHoldsTheWrittenFormsSoThatItOutlivesAnUpgrade() throws Exception {
        RegistrationStore store = new RegistrationStore(directory);

        store.write(new RegistrationStore.Kept(List.of(API), List.of(CHECKS.get(2))));

        JsonNode written = new ObjectMapper().readTree(directory.resolve(RegistrationStore.FILE_NAME).toFile());
        assertEquals(new ObjectMapper().readTree("""
                {"version":1,
                 "services":[{"id":"api-1","name":"api","address":"10.0.0.5","port":8080,"tags":["v1"],
                              "metadata":{"team":"platform"}}],
                 "checks":[{"id":"app","serviceId":"from-the-file","type":"ttl","required":true,"ttlMs":30000,
                            "deregisterAfterMs":60000}]}
                """), written);
    }

    @Test
    void maintenanceIsKeptOnlyWhenThereIsSome() throws Exception {
        RegistrationStore store = new RegistrationStore(directory);
        RegistrationStore.Kept kept = new RegistrationStore.Kept(List.of(API), List.of(), "rack move",
                Map.of("api-1", "upgrade"));

        store.write(kept);

        assertEquals(kept, new RegistrationStore(directory).read());
        JsonNode written = new ObjectMapper().readTree(directory.resolve(RegistrationStore.FILE_NAME).toFile());
        assertEquals(new ObjectMapper().readTree("{\"node\":\"rack move\",\"services\":{\"api-1\":\"upgrade\"}}"),
                written.path("maintenance"));

        store.write(new RegistrationStore.Kept(List.of(API), List.of()));
        assertFalse(new ObjectMapper().readTree(directory.resolve(RegistrationStore.FILE_NAME).toFile())
                .has("maintenance"), "a file without maintenance is the form that was kept before it existed");
        assertEquals(new RegistrationStore.Kept(List.of(API), List.of()), store.read());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":1,\"services\":[],\"checks\":[],\"maintenance\":[]}",
            "{\"version\":1,\"services\":[],\"checks\":[],\"maintenance\":{\"node\":1}}",
            "{\"version\":1,\"services\":[],\"checks\":[],\"maintenance\":{\"services\":{\"a\":1}}}",
            "{\"version\":1,\"services\":[],\"checks\":[],\"maintenance\":{\"unknown\":\"x\"}}",
            "{ not json",
            "[]",
            "{\"version\":2,\"services\":[],\"checks\":[]}",
            "{\"services\":[],\"checks\":[]}",
            "{\"version\":1,\"services\":{},\"checks\":[]}",
            "{\"version\":1,\"services\":[{\"name\":\"no-port\"}],\"checks\":[]}",
            "{\"version\":1,\"services\":[{\"name\":\"no-address\",\"port\":80}],\"checks\":[]}",
            "{\"version\":1,\"services\":[],\"checks\":[{\"id\":\"no-service\",\"type\":\"ttl\",\"ttlMs\":1000}]}",
            "{\"version\":1,\"services\":[],\"checks\":[{\"id\":\"tcp\",\"serviceId\":\"s\",\"type\":\"tcp\"}]}"})
    void aFileThatCannotBeReadStopsTheReadNamesItselfAndIsNotReplaced(String content) throws Exception {
        Path file = directory.resolve(RegistrationStore.FILE_NAME);
        Files.writeString(file, content);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new RegistrationStore(directory).read());

        assertTrue(refused.getMessage().contains(file.toString()), refused.getMessage());
        assertEquals(content, Files.readString(file));
    }

    @Test
    void aWriteLeavesOnlyTheFile() throws Exception {
        new RegistrationStore(directory).write(new RegistrationStore.Kept(List.of(API), List.of()));

        try (var entries = Files.list(directory)) {
            assertEquals(List.of(RegistrationStore.FILE_NAME),
                    entries.map(entry -> entry.getFileName().toString()).toList());
        }
    }

    @Test
    void aWriteThatFailsLeavesWhatWasKept() throws Exception {
        RegistrationStore store = new RegistrationStore(directory);
        RegistrationStore.Kept kept = new RegistrationStore.Kept(List.of(API), List.of());
        store.write(kept);
        // The write goes through a temporary file, which cannot be opened while a directory has its name.
        Path temporary = Files.createDirectory(directory.resolve(RegistrationStore.FILE_NAME + ".tmp"));

        assertThrows(IOException.class, () -> store.write(new RegistrationStore.Kept(List.of(), List.of())));

        Files.delete(temporary);
        assertEquals(kept, store.read());
        assertFalse(Files.exists(temporary));
    }
}
