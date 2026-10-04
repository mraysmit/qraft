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

package dev.mars.qraft.controller.raft.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a second JVM cannot open a Raft storage directory locked by a live {@link
 * DirectoryLockProcess}, and that its failure leaves the owner unaffected.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-22
 * @version 1.0
 */
class RaftStorageProcessLockTest {
    @TempDir
    Path directory;

    @Test
    void secondJvmCannotOpenDirectoryWhileOwnerRemainsHealthy() throws Exception {
        Process owner = startProcess();
        try {
            BufferedReader ownerOutput = new BufferedReader(new InputStreamReader(
                    owner.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder startup = new StringBuilder();
            String ownerReady = null;
            String line;
            while ((line = ownerOutput.readLine()) != null) {
                startup.append(line).append(System.lineSeparator());
                if (line.startsWith("LOCKED ")) {
                    ownerReady = line;
                    break;
                }
            }
            assertTrue(ownerReady != null, startup.toString());
            assertTrue(ownerReady.contains("LOCKED " + directory.toAbsolutePath().normalize()), ownerReady);

            Path contenderLog = directory.resolveSibling(directory.getFileName() + "-contender.log");
            Process contender = startProcess(contenderLog);
            assertTrue(contender.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                    "second JVM did not fail promptly");
            String failure = Files.readString(contenderLog, StandardCharsets.UTF_8);
            assertFalse(contender.exitValue() == 0, failure);
            assertTrue(failure.contains(directory.toAbsolutePath().normalize().toString()), failure);
            assertTrue(failure.contains("another process may hold the storage lock"), failure);
            assertTrue(owner.isAlive(), "lock contender must not disturb the owning process");

            owner.getOutputStream().write(System.lineSeparator().getBytes(StandardCharsets.UTF_8));
            owner.getOutputStream().flush();
            assertTrue(owner.waitFor(10, TimeUnit.SECONDS), "owner did not close promptly");
            assertEquals(0, owner.exitValue());
        } finally {
            if (owner.isAlive()) owner.destroyForcibly();
        }
    }

    private Process startProcess() throws Exception {
        return processBuilder().redirectErrorStream(true).start();
    }

    private Process startProcess(Path output) throws Exception {
        return processBuilder().redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private ProcessBuilder processBuilder() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classPath,
                DirectoryLockProcess.class.getName(), directory.toString());
    }
}
