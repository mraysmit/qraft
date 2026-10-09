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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the audit a parent test runs over the console output of a helper JVM that has halted or been killed:
 * ordinary output passes; an ERROR line, a Logback status error, or an uncaught exception fails the calling test,
 * and the failure names the helper JVM, the running test, and each error with its stack trace. The cases pass
 * text to the audit and log nothing themselves.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
class SubprocessOutputAuditHelperTest {

    private static final String INFO =
            "12:00:00.001 [main] INFO  dev.mars.raftlog.storage.FileRaftStorage - WAL opened";
    private static final String ERROR =
            "12:00:00.004 [wal-executor] ERROR dev.mars.raftlog.storage.FileRaftStorage - WAL write failed";

    @Test
    void outputWithoutAnErrorPasses() {
        String output = lines(INFO,
                "12:00:00.002 [main] DEBUG dev.mars.qraft.client.catalog.HttpCatalogClient"
                        + " - Retrying after an error response: ERROR_COUNT=1",
                "12:00:00,003 |-INFO in ch.qos.logback.core.FileAppender[FILE] - File property is set",
                "12:00:00.004 [main] WARN  dev.mars.qraft.client.QraftAgent - Heartbeat was late");

        assertDoesNotThrow(() -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", output));
    }

    @Test
    void anErrorLineFailsTheCallingTestAndNamesTheHelperJvmTheTestAndTheLine() {
        String runningTest = IntentionalErrorsHelper.openWindow().orElseThrow();

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", lines(INFO, ERROR)));

        assertTrue(failure.getMessage().contains("crash-writer"), failure.getMessage());
        assertTrue(failure.getMessage().contains(runningTest), failure.getMessage());
        assertTrue(failure.getMessage().contains(ERROR), failure.getMessage());
        assertFalse(failure.getMessage().contains(INFO), failure.getMessage());
    }

    @Test
    void everyErrorLineIsReported() {
        String second = "12:00:00.009 [main] ERROR dev.mars.qraft.raft.RaftNode - Recovery failed";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", lines(ERROR, INFO, second)));

        assertTrue(failure.getMessage().contains("2 error"), failure.getMessage());
        assertTrue(failure.getMessage().contains(ERROR), failure.getMessage());
        assertTrue(failure.getMessage().contains(second), failure.getMessage());
    }

    @Test
    void anErrorKeepsItsStackTraceAndNothingElse() {
        String exception = "java.io.IOException: disk full";
        String frame = "\tat dev.mars.raftlog.storage.FileRaftStorage.sync(FileRaftStorage.java:412)";
        String cause = "Caused by: java.nio.channels.ClosedChannelException: null";
        String later = "12:00:00.008 [main] INFO  dev.mars.raftlog.storage.FileRaftStorage - WAL closed";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer",
                        lines(INFO, ERROR, exception, frame, cause, later)));

        assertTrue(failure.getMessage().contains("1 error"), failure.getMessage());
        assertTrue(failure.getMessage().contains(exception), failure.getMessage());
        assertTrue(failure.getMessage().contains(frame), failure.getMessage());
        assertTrue(failure.getMessage().contains(cause), failure.getMessage());
        assertFalse(failure.getMessage().contains(later), failure.getMessage());
    }

    @Test
    void anUncaughtExceptionFailsTheCallingTest() {
        String uncaught = "Exception in thread \"main\" java.lang.IllegalStateException: boom";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("client", lines(INFO, uncaught,
                        "\tat dev.mars.qraft.runtime.QraftRuntimeApplication.main(QraftRuntimeApplication.java:60)")));

        assertTrue(failure.getMessage().contains(uncaught), failure.getMessage());
    }

    @Test
    void aLogbackStatusErrorFailsTheCallingTest() {
        String status = "12:00:00,005 |-ERROR in ch.qos.logback.core.FileAppender[FILE]"
                + " - openFile(logs/qraft-maven-tests.log,true) call failed.";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", lines(status, INFO)));

        assertTrue(failure.getMessage().contains(status), failure.getMessage());
    }

    @Test
    void anErrorThatCarriesAFlagStillFailsBecauseTheseHelperJvmsDeclareNothing() {
        String flagged = "12:00:00.006 [main] ERROR *** INJECTED FAILURE: SELF_TEST_INJECTED_FAILURE,"
                + " injected by no running test *** dev.mars.qraft.raft.RaftNode - Failed to persist command to WAL";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", lines(INFO, flagged)));

        assertTrue(failure.getMessage().contains(flagged), failure.getMessage());
    }

    @Test
    void colourCodesDoNotHideAnError() {
        String coloured = "12:00:00.007 [main] \u001B[31mERROR\u001B[0m dev.mars.qraft.raft.RaftNode - boom";

        AssertionError failure = assertThrows(AssertionError.class,
                () -> SubprocessOutputAuditHelper.requireNoErrors("crash-writer", lines(INFO, coloured)));

        assertTrue(failure.getMessage().contains("12:00:00.007 [main] ERROR dev.mars.qraft.raft.RaftNode - boom"),
                failure.getMessage());
    }

    private static String lines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }
}
