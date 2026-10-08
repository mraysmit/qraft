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

/**
 * Test logging helper for a helper JVM that cannot audit itself: a crash writer that halts at a checkpoint, or a
 * runtime that its test kills. {@link IntentionalErrorsHelper#inSubprocess} never returns in such a JVM, so its
 * parent test passes the JVM's complete console output to {@link #requireNoErrors} once it has ended. These
 * helper JVMs declare no intentional error, so every error in their output fails the calling test.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
public final class SubprocessOutputAuditHelper {

    private SubprocessOutputAuditHelper() {
    }

    /**
     * Fails the calling test when {@code output}, the complete console output of the finished helper JVM
     * {@code source}, holds an ERROR line or an uncaught exception.
     */
    public static void requireNoErrors(String source, String output) {
        // Not implemented yet: SubprocessOutputAuditHelperTest is first shown to fail against this empty body.
    }
}
