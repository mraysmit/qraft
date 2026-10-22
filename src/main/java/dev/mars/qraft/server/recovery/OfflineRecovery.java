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

package dev.mars.qraft.server.recovery;

import java.nio.file.Path;

/**
 * Offline lost-quorum recovery (membership Step 7, decision 8). It runs against stopped data directories only and
 * contacts no server.
 * <ul>
 *   <li>{@link #inspect} reports a directory without changing it;</li>
 *   <li>{@link #recover} copies a stopped survivor or backup into a new directory, appends one configuration entry
 *       making it the sole voter under a new server ID, and publishes that directory atomically. The source is
 *       never changed.</li>
 * </ul>
 * Ordinary server startup never reads a recovery input, claim or staging directory.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class OfflineRecovery {
    private OfflineRecovery() { }

    /** Reports the stopped {@code directory} without changing it; refused if its lock is held. */
    public static DirectoryReport inspect(Path directory) throws RecoveryRefusedException {
        throw new UnsupportedOperationException("Offline inspection is not implemented yet");
    }

    /** Performs {@code plan}, or refuses it with the source unchanged. */
    public static RecoveryResult recover(RecoveryPlan plan) throws RecoveryRefusedException {
        throw new UnsupportedOperationException("Offline recovery is not implemented yet");
    }
}
