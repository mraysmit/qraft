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

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorageConfig;
import dev.mars.qraft.testing.fault.IntentionalErrorsHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.WAL_DIRECTORY_ALREADY_LOCKED;

/**
 * Test helper executable launched in separate JVMs by {@link RaftStorageProcessLockTest}
 * to verify that two processes cannot open the same Raft storage directory.
 *
 * <p>Opens the directory supplied in the first argument and prints {@code LOCKED} when
 * it owns the lock. It holds the lock until stdin receives a line or closes, then closes
 * the storage. The second argument is {@code owner} or {@code contender}; the third names
 * the calling test. Only the contender declares the intentional lock error. A failed open
 * reports {@code LOCK_FAILED} and exits with code {@code 73} after its log audit passes.
 * The calling test launches an owner and a contender and performs the assertions.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the
 * test. It has a {@code main} entry point rather than JUnit test methods.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-22
 * @version 1.0
 */
public final class DirectoryLockProcessFixture {
    private DirectoryLockProcessFixture() { }

    public static void main(String[] args) {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        String role = args[1];
        if (!role.equals("owner") && !role.equals("contender")) {
            throw new IllegalArgumentException("expected owner or contender role, got " + role);
        }
        int exitCode = IntentionalErrorsHelper.inSubprocess(args[2] + "/" + role, () -> {
            if (role.equals("contender")) IntentionalErrorsHelper.expect(WAL_DIRECTORY_ALREADY_LOCKED, 1);
            return runProcess(directory);
        });
        System.exit(exitCode);
    }

    private static int runProcess(Path directory) {
        try (FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory).syncEnabled(true).build())) {
            storage.open(directory).join();
            System.out.println("LOCKED " + directory);
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            return 0;
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("LOCK_FAILED directory=" + directory
                    + "; another process may hold the storage lock: " + cause.getMessage());
            return 73;
        }
    }
}
