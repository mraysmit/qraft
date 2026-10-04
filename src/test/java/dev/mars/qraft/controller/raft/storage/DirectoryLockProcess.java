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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Separate-JVM fixture that owns a real RaftLog directory lock until stdin closes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-22
 * @version 1.0
 */
public final class DirectoryLockProcess {
    private DirectoryLockProcess() { }

    public static void main(String[] args) {
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        try (FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder()
                .dataDir(directory).syncEnabled(true).build())) {
            storage.open(directory).join();
            System.out.println("LOCKED " + directory);
            System.out.flush();
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("LOCK_FAILED directory=" + directory
                    + "; another process may hold the storage lock: " + cause.getMessage());
            System.exit(73);
        }
    }
}
