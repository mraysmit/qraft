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

package dev.mars.qraft.raft;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests {@link TransitionFailureLog}: a transition refused because the sequencer is draining is logged at debug,
 * and every other failure at error with its stack trace. The tests log to a logger of their own that does not
 * pass events on, so the error they provoke on purpose is read here and not by the suite's error audit.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
class TransitionFailureLogTest {
    private static final String FORMAT = "Failed to process vote response for election term {}: {}";

    private Logger logger;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void captureOnALoggerOfOurOwn() {
        logger = (Logger) LoggerFactory.getLogger("transition-failure-log-test");
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void release() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test
    void aDrainingRefusalIsLoggedAtDebugWithItsMessageAndNoStackTrace() {
        TransitionFailureLog.log(logger, FORMAT, new RaftTransitionSequencer.DrainingException(), 7L);

        assertEquals(1, events.list.size());
        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.DEBUG, event.getLevel());
        assertEquals("Failed to process vote response for election term 7: Raft transition sequencer is draining",
                event.getFormattedMessage());
        assertNull(event.getThrowableProxy(), "an expected refusal carries no stack trace");
    }

    @Test
    void aDrainingRefusalWrappedInACompletionExceptionIsRecognisedToo() {
        TransitionFailureLog.log(logger, FORMAT,
                new CompletionException(new RaftTransitionSequencer.DrainingException()), 7L);

        assertEquals(Level.DEBUG, events.list.get(0).getLevel());
    }

    @Test
    void anyOtherFailureIsLoggedAtErrorWithItsStackTrace() {
        IOException failure = new IOException("disk full");

        TransitionFailureLog.log(logger, FORMAT, failure, 7L);

        ILoggingEvent event = events.list.get(0);
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals("Failed to process vote response for election term 7: disk full", event.getFormattedMessage());
        assertNotNull(event.getThrowableProxy(), "a real failure keeps its stack trace");
        assertEquals(IOException.class.getName(), event.getThrowableProxy().getClassName());
    }

    @Test
    void aFencedSequencerIsNotTheExpectedRefusal() {
        IllegalStateException fenced = new RaftTransitionSequencer.FencedException(new IOException("sync failed"));

        TransitionFailureLog.log(logger, FORMAT, fenced, 7L);

        assertEquals(Level.ERROR, events.list.get(0).getLevel(), "a fence is a fault and stays an error");
        assertEquals(fenced.getClass().getName(), events.list.get(0).getThrowableProxy().getClassName());
    }

    @Test
    void aFormatWithOnlyTheMessageNeedsNoLeadingArguments() {
        TransitionFailureLog.log(logger, "Snapshot failed: {}", new RaftTransitionSequencer.DrainingException());

        assertEquals("Snapshot failed: Raft transition sequencer is draining",
                events.list.get(0).getFormattedMessage());
    }
}
