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
 * Test helper executable launched in a separate JVM by {@link OfflineRecoveryProcessTest} to crash a recovery.
 * It runs {@link OfflineRecovery#recover} on the plan in its arguments and halts the JVM when the selected
 * {@link RecoveryCheckpoint} is reached, skipping all cleanup, as a power loss would. The calling test audits this
 * JVM's output and checks what the next run does.
 *
 * <p>This class belongs in the test sources because it supplies a subprocess for the test. Its {@code main} method
 * prepares the crash; the calling test performs the assertions.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-23
 * @version 1.0
 */
public final class RecoveryCrashWriterFixture {
    public static final int HALT_EXIT_CODE = 92;

    private RecoveryCrashWriterFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "expected: <source> <source-server-id> <output> <name> <address> <checkpoint>");
        }
        RecoveryPlan plan = new RecoveryPlan(Path.of(args[0]), args[1], Path.of(args[2]), args[3], args[4]);
        RecoveryCheckpoint selected = RecoveryCheckpoint.valueOf(args[5]);
        OfflineRecovery.recover(plan, reached -> {
            if (reached == selected) Runtime.getRuntime().halt(HALT_EXIT_CODE);
        });
        throw new AssertionError("checkpoint was not reached: " + selected);
    }
}
