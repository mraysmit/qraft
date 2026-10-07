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

package dev.mars.qraft.testing.fault;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Proves the two auto-detected extensions compose correctly without needing a Docker daemon. */
@Tag("docker")
@ExpectedDockerErrorsHelper(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE)
class DockerLogExtensionIntegrationTest {

    @Test
    void declaredExternalErrorIsCheckedInTheClassWindow() {
        DockerLogCaptureHelper.capture("extension-fixture", "fixture", """
                2026-10-06 14:00:00.000 [main] ERROR dev.mars.qraft.controller.raft.RaftNode - Raft peer n2 became unreachable during AppendEntries
                """);
    }
}
