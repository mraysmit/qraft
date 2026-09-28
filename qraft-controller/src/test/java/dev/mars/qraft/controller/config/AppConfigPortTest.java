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

package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the rules for a server's listening ports. Port 0 asks the system for any free port. The HTTP and
 * API gRPC ports may always be 0. The Raft port may be 0 only on a server that is its cluster's sole
 * member, because peers dial the configured Raft address. Two zeros never clash, but two equal nonzero
 * ports do, and ports outside 0 to 65535 are invalid.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class AppConfigPortTest {

    @Test
    void everyPortMayBeZeroOnASoleMember() {
        assertValid("""
                {"version":1,"server":{"id":"solo","http":{"port":0},"apiGrpcPort":0,"raft":{"port":0}}}
                """);
        assertValid("""
                {"version":1,"server":{"id":"solo","http":{"port":0},"apiGrpcPort":0,
                 "raft":{"port":0,"nodes":{"solo":"127.0.0.1:0"}}}}
                """);
    }

    @Test
    void theRaftPortCannotBeZeroWhenPeersMustDialIt() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"a","raft":{"port":0,"nodes":{"a":"a:0","b":"b:9080"}}}}
                """));
        assertTrue(failure.getMessage().contains("server.raft.port"), failure.getMessage());
        assertValid("""
                {"version":1,"server":{"id":"a","http":{"port":0},"apiGrpcPort":0,
                 "raft":{"port":9080,"nodes":{"a":"a:9080","b":"b:9080"}}}}
                """);
    }

    @Test
    void equalNonzeroPortsClashButZerosDoNot() {
        assertValid("""
                {"version":1,"server":{"id":"solo","http":{"port":0},"apiGrpcPort":0,"raft":{"port":9080}}}
                """);
        assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"solo","http":{"port":9080},"apiGrpcPort":10080,"raft":{"port":9080}}}
                """));
        assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"solo","http":{"port":8080},"apiGrpcPort":8080,"raft":{"port":0}}}
                """));
    }

    @Test
    void portsOutsideTheRangeAreRejected() {
        assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"solo","http":{"port":-1}}}
                """));
        assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"solo","apiGrpcPort":65536}}
                """));
        assertThrows(IllegalStateException.class, () -> validate("""
                {"version":1,"server":{"id":"solo","raft":{"port":-1}}}
                """));
        assertValid("""
                {"version":1,"server":{"id":"solo","http":{"port":65535},"apiGrpcPort":1,"raft":{"port":2}}}
                """);
    }

    private static void assertValid(String document) {
        assertDoesNotThrow(() -> validate(document), document);
    }

    private static void validate(String document) {
        AppConfig.fromJson(document).validate();
    }
}
