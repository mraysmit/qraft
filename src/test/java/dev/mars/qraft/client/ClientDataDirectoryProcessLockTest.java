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

package dev.mars.qraft.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a client data directory held by a client in another JVM, a {@link ClientDataDirectoryProcessFixture},
 * cannot be opened here, that the refusal leaves the owner holding it, and that it opens once the owner has gone.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-27
 * @version 1.0
 */
class ClientDataDirectoryProcessLockTest {
    @TempDir
    Path directory;

    @Test
    void aDirectoryHeldByAnotherProcessIsRefusedUntilThatProcessLetsItGo(TestInfo testInfo) throws Exception {
        String testOwner = getClass().getSimpleName() + "#" + testInfo.getTestMethod().orElseThrow().getName();
        Process owner = ownerProcess(testOwner).redirectErrorStream(true).start();
        try {
            BufferedReader ownerOutput = new BufferedReader(new InputStreamReader(
                    owner.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder startup = new StringBuilder();
            String ready = null;
            for (String line; (line = ownerOutput.readLine()) != null; ) {
                startup.append(line).append(System.lineSeparator());
                if (line.startsWith("LOCKED ")) {
                    ready = line;
                    break;
                }
            }
            assertNotNull(ready, startup.toString());
            String ownersNodeId = ready.substring("LOCKED ".length());

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> ClientDataDirectory.open(directory));

            assertEquals(directory + " is in use by another client process; a data directory belongs to one"
                    + " client at a time", refused.getMessage());
            assertTrue(owner.isAlive(), "the refusal must not disturb the owning process");

            owner.getOutputStream().write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            owner.getOutputStream().flush();
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS), "owner did not close promptly");
            assertEquals(0, owner.exitValue(), startup.toString());

            try (ClientDataDirectory reopened = ClientDataDirectory.open(directory)) {
                assertEquals(ownersNodeId, reopened.nodeId(), "the directory keeps the owner's node ID");
            }
        } finally {
            if (owner.isAlive()) owner.destroyForcibly();
        }
    }

    private ProcessBuilder ownerProcess(String testOwner) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classPath, ClientDataDirectoryProcessFixture.class.getName(),
                directory.toString(), testOwner);
    }
}
