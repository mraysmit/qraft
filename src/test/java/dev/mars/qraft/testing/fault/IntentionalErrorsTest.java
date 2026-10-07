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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.RAFT_PEER_UNREACHABLE;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.SELF_TEST_INJECTED_FAILURE;
import static dev.mars.qraft.testing.fault.IntentionalErrorFixture.SELF_TEST_INTENTIONAL_ERROR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the labelling and checks of intentional errors themselves: an unlabelled error or logged exception is a
 * problem; an injected failure is labelled wherever it appears in the exception chain; an intentional error is
 * labelled only when declared and matched exactly; a declared count that is not met is a problem; and a root
 * logger without a started check is refused. Each case runs in its own window, opened inside this test's window,
 * and records events directly, so none of them is written to the log.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
class IntentionalErrorsTest {

    private static final Logger SELF_TEST = (Logger) LoggerFactory.getLogger("dev.mars.qraft.testing.fault.selftest");
    private static final Logger OTHER = (Logger) LoggerFactory.getLogger("dev.mars.qraft.testing.fault.other");

    @Test
    void anUnlabelledErrorIsAProblem() {
        List<String> problems = inWindow("SelfTest#a", () ->
                IntentionalErrorsHelper.record(event(SELF_TEST, Level.ERROR, "a real failure", null)));

        assertEquals(List.of("unlabelled ERROR [main] dev.mars.qraft.testing.fault.selftest - a real failure"),
                problems.stream().map(problem -> problem.replaceFirst("\\[[^\\]]*\\]", "[main]")).toList());
    }

    @Test
    void anUnlabelledExceptionIsAProblemAtAnyLevel() {
        List<String> problems = inWindow("SelfTest#b", () -> {
            IntentionalErrorsHelper.record(event(SELF_TEST, Level.DEBUG, "a debug line", new IllegalStateException("boom")));
            IntentionalErrorsHelper.record(event(SELF_TEST, Level.WARN, "a warning", new IllegalStateException("boom")));
        });

        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void aWarningWithoutAnExceptionIsNotAProblem() {
        assertEquals(List.of(), inWindow("SelfTest#c", () ->
                IntentionalErrorsHelper.record(event(SELF_TEST, Level.WARN, "a warning", null))));
    }

    @Test
    void anInjectedFailureIsLabelledWithItsEntryAndTest() {
        InjectedFaultFixture fault = new InjectedFaultFixture(SELF_TEST_INJECTED_FAILURE, "Simulated sync failure");
        ILoggingEvent direct = event(OTHER, Level.ERROR, "Failed to persist command: Simulated sync failure", fault);
        ILoggingEvent wrapped = event(OTHER, Level.WARN, "Sync failed",
                new IllegalStateException("outer", new IOException("disk", fault)));
        RuntimeException suppressing = new RuntimeException("outer");
        suppressing.addSuppressed(fault);
        ILoggingEvent suppressed = event(OTHER, Level.ERROR, "Shutdown failed", suppressing);

        List<String> problems = inWindow("SelfTest#d", () -> {
            for (ILoggingEvent event : List.of(direct, wrapped, suppressed)) {
                assertEquals("*** INJECTED FAILURE: SELF_TEST_INJECTED_FAILURE, injected by SelfTest#d *** ",
                        new IntentionalErrorLabelHelper().convert(event));
                IntentionalErrorsHelper.record(event);
            }
        });

        assertEquals(List.of(), problems);
    }

    @Test
    void anInjectedFailureCountsAgainstADeclaredNumber() {
        InjectedFaultFixture fault = new InjectedFaultFixture(SELF_TEST_INJECTED_FAILURE, "Simulated");
        List<String> problems = inWindow("SelfTest#e", () -> {
            IntentionalErrorsHelper.expect(SELF_TEST_INJECTED_FAILURE, 1);
            IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "first", fault));
            IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "second", fault));
        });

        assertEquals(List.of("INJECTED FAILURE SELF_TEST_INJECTED_FAILURE was declared exactly 1 time(s)"
                + " but occurred 2 time(s)"), problems);
    }

    @Test
    void anIntentionalErrorIsLabelledOnlyOnceDeclared() {
        ILoggingEvent error = event(SELF_TEST, Level.ERROR, "Self-test intentional error 7", null);

        List<String> problems = inWindow("SelfTest#f", () -> {
            assertEquals("", new IntentionalErrorLabelHelper().convert(error));
            IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR);
            assertEquals("*** INTENTIONAL ERROR: SELF_TEST_INTENTIONAL_ERROR, caused by SelfTest#f *** ",
                    new IntentionalErrorLabelHelper().convert(error));
            IntentionalErrorsHelper.record(error);
        });

        assertEquals(List.of(), problems);
    }

    @Test
    void anIntentionalErrorMatchesOnlyItsLoggerLevelAndWholeMessage() {
        List<String> problems = inWindow("SelfTest#g", () -> {
            IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR, 1);
            IntentionalErrorsHelper.record(event(SELF_TEST, Level.ERROR, "Self-test intentional error 7", null));
            IntentionalErrorsHelper.record(event(SELF_TEST, Level.ERROR, "Self-test intentional error 7 and more", null));
            IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "Self-test intentional error 7", null));
            IntentionalErrorsHelper.record(event(SELF_TEST, Level.WARN, "Self-test intentional error 7",
                    new IllegalStateException("boom")));
        });

        assertEquals(3, problems.size(), problems.toString());
        problems.forEach(problem -> assertTrue(problem.startsWith("unlabelled "), problem));
    }

    @Test
    void aDeclaredErrorThatNeverOccursIsAProblem() {
        List<String> problems = inWindow("SelfTest#h", () -> IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR));

        assertEquals(List.of("INTENTIONAL ERROR SELF_TEST_INTENTIONAL_ERROR was declared at least 1 time(s)"
                + " but occurred 0 time(s)"), problems);
    }

    @Test
    void aStoppedPeerDeclarationLabelsBothRpcFailuresOnlyForThatPeer() {
        Logger raft = (Logger) LoggerFactory.getLogger("dev.mars.qraft.controller.raft.RaftNode");
        List<String> problems = inWindow("SelfTest#stoppedPeer", () -> {
            IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE, "node.b");
            for (String message : List.of("Failed to retrieve vote from node.b",
                    "Raft peer node.b became unreachable during AppendEntries")) {
                ILoggingEvent failure = event(raft, Level.ERROR, message, null);
                assertEquals("*** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, caused by SelfTest#stoppedPeer *** ",
                        IntentionalErrorsHelper.label(failure));
                IntentionalErrorsHelper.record(failure);
            }
            for (String message : List.of("Failed to retrieve vote from node-b",
                    "Raft peer node-c became unreachable during AppendEntries",
                    "Failed to retrieve vote from node.b and more")) {
                ILoggingEvent failure = event(raft, Level.ERROR, message, null);
                assertEquals("", IntentionalErrorsHelper.label(failure));
                IntentionalErrorsHelper.record(failure);
            }
            IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "Failed to retrieve vote from node.b", null));
            IntentionalErrorsHelper.record(event(raft, Level.WARN, "Failed to retrieve vote from node.b",
                    new IOException("unexpected warning")));
        });

        assertEquals(5, problems.size(), problems.toString());
        problems.forEach(problem -> assertTrue(problem.startsWith("unlabelled "), problem));
    }

    @Test
    void aStoppedPeerDeclarationMustOccurAndRejectsInvalidArguments() {
        assertEquals(List.of("INTENTIONAL ERROR RAFT_PEER_UNREACHABLE was declared at least 1 time(s)"
                + " but occurred 0 time(s)"), inWindow("SelfTest#missingPeer", () ->
                IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE, "node-b")));
        assertThrows(IllegalArgumentException.class,
                () -> IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR, "node-b"));
        assertThrows(IllegalArgumentException.class,
                () -> IntentionalErrorsHelper.expect(RAFT_PEER_UNREACHABLE, " "));
    }

    @Test
    void aSubprocessScopeLabelsAndChecksItsDeclaredErrorsBeforeReturning() {
        int exitCode = IntentionalErrorsHelper.inSubprocess("SelfTest#contender", () -> {
            IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR, 1);
            ILoggingEvent failure = event(SELF_TEST, Level.ERROR, "Self-test intentional error 7", null);
            assertEquals("*** INTENTIONAL ERROR: SELF_TEST_INTENTIONAL_ERROR, caused by SelfTest#contender *** ",
                    IntentionalErrorsHelper.label(failure));
            IntentionalErrorsHelper.record(failure);
            return 73;
        });
        assertEquals(73, exitCode);
    }

    @Test
    void aSubprocessScopeRejectsUnlabelledErrorsAndMissingExpectedErrors() {
        String enclosing = IntentionalErrorsHelper.openWindow().orElseThrow();
        AssertionError unlabelled = assertThrows(AssertionError.class, () ->
                IntentionalErrorsHelper.inSubprocess("SelfTest#unexpectedChild", () -> {
                    IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "unexpected child failure", null));
                    return 73;
                }));
        assertTrue(unlabelled.getMessage().contains("unlabelled ERROR"), unlabelled.getMessage());
        AssertionError missing = assertThrows(AssertionError.class, () ->
                IntentionalErrorsHelper.inSubprocess("SelfTest#missingChildError", () -> {
                    IntentionalErrorsHelper.expect(SELF_TEST_INTENTIONAL_ERROR, 1);
                    return 73;
                }));
        assertTrue(missing.getMessage().contains("occurred 0 time(s)"), missing.getMessage());
        assertEquals(enclosing, IntentionalErrorsHelper.openWindow().orElseThrow());
    }

    @Test
    void aSubprocessScopePreservesTheBodyFailureAndItsAuditFailure() {
        String enclosing = IntentionalErrorsHelper.openWindow().orElseThrow();
        IllegalStateException original = new IllegalStateException("child body failed");
        assertEquals(original, assertThrows(IllegalStateException.class, () ->
                IntentionalErrorsHelper.inSubprocess("SelfTest#failedChild", () -> {
                    IntentionalErrorsHelper.record(event(OTHER, Level.ERROR, "unexpected child failure", null));
                    throw original;
                })));
        assertEquals(1, original.getSuppressed().length);
        assertTrue(original.getSuppressed()[0].getMessage().contains("unlabelled ERROR"));
        assertEquals(enclosing, IntentionalErrorsHelper.openWindow().orElseThrow());
    }

    @Test
    void aWindowClosedOutOfOrderIsAProblem() {
        IntentionalErrorsHelper.begin("SelfTest#outer");
        IntentionalErrorsHelper.begin("SelfTest#inner");

        assertEquals(List.of("intentional-error window mismatch: closing SelfTest#outer but the open window is"
                + " SelfTest#inner"), IntentionalErrorsHelper.end("SelfTest#outer"));
        assertEquals(List.of(), IntentionalErrorsHelper.end("SelfTest#inner"));
        assertEquals(List.of(), IntentionalErrorsHelper.end("SelfTest#outer"));
    }

    @Test
    void aRootWithoutAStartedCheckIsRefused() {
        LoggerContext context = new LoggerContext();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertThrows(AssertionError.class, () -> IntentionalErrorCheckHelper.requireAttachedTo(root));

        IntentionalErrorCheckHelper check = new IntentionalErrorCheckHelper();
        check.setContext(context);
        root.addAppender(check);
        assertThrows(AssertionError.class, () -> IntentionalErrorCheckHelper.requireAttachedTo(root));

        check.start();
        IntentionalErrorCheckHelper.requireAttachedTo(root);
    }

    @Test
    void onlyAnInjectedFailureEntryCanBeInjected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InjectedFaultFixture(SELF_TEST_INTENTIONAL_ERROR, "not injectable"));
    }

    private static List<String> inWindow(String owner, Runnable body) {
        IntentionalErrorsHelper.begin(owner);
        try {
            body.run();
        } catch (RuntimeException | Error failure) {
            IntentionalErrorsHelper.end(owner);
            throw failure;
        }
        return IntentionalErrorsHelper.end(owner);
    }

    private static ILoggingEvent event(Logger logger, Level level, String message, Throwable failure) {
        return new LoggingEvent(Logger.class.getName(), logger, level, message, failure, null);
    }
}
