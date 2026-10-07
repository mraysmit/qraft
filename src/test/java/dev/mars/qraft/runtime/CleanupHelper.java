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

package dev.mars.qraft.runtime;

/**
 * Test teardown helper for runtime tests. Runs every cleanup step even when an earlier one fails,
 * so one resource that will not stop cannot
 * leave the others open for the next test. The first failure is rethrown with the later ones suppressed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
final class CleanupHelper {
    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    private Exception failure;

    CleanupHelper run(Step step) {
        try {
            step.run();
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        return this;
    }

    void rethrow() throws Exception {
        if (failure != null) throw failure;
    }
}
