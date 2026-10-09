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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

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

    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
    /** The rule of {@link DockerLogCaptureHelper}: ERROR standing alone, as a log level or a Logback status. */
    private static final Pattern ERROR_TOKEN = Pattern.compile("(?:^|[|\\s-])ERROR(?:[|:\\s-]|$)");
    private static final String UNCAUGHT_EXCEPTION = "Exception in thread";
    /** A log event or a Logback status line; any other line after an error belongs to its stack trace. */
    private static final Pattern RECORD_START =
            Pattern.compile("^(?:\\d{4}-\\d{2}-\\d{2} )?\\d{2}:\\d{2}:\\d{2}[.,]\\d{3} ");

    private SubprocessOutputAuditHelper() {
    }

    /**
     * Fails the calling test when {@code output}, the complete console output of the finished helper JVM
     * {@code source}, holds an ERROR line or an uncaught exception. The failure names the running test and
     * quotes each error with its stack trace; a flag on an error does not excuse it.
     */
    public static void requireNoErrors(String source, String output) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(output, "output");
        List<String> report = new ArrayList<>();
        int errors = 0;
        boolean inError = false;
        for (String rawLine : output.split("\\R")) {
            String line = ANSI.matcher(rawLine).replaceAll("").stripTrailing();
            if (ERROR_TOKEN.matcher(line).find() || line.contains(UNCAUGHT_EXCEPTION)) {
                errors++;
                inError = true;
                report.add(line);
            } else if (inError && !line.isEmpty() && !RECORD_START.matcher(line).find()) {
                report.add(line);
            } else {
                inError = false;
            }
        }
        if (errors == 0) return;
        throw new AssertionError(IntentionalErrorsHelper.openWindow().orElse("no running test")
                + ": helper JVM " + source + " logged " + errors + " error(s) that no test declared:\n  "
                + String.join("\n  ", report));
    }
}
