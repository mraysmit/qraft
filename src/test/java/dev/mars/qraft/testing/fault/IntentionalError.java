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
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The complete list of errors the test suite causes on purpose. Every error a test causes deliberately has an
 * entry here, and nowhere else.
 *
 * <ul>
 * <li>An {@link Kind#INJECTED_FAILURE} is a failure a test injects into production code by throwing an
 * {@link InjectedFault} that names the entry. A logged event is that failure when its exception, or any exception
 * in its cause or suppressed chain, is that {@link InjectedFault}.</li>
 * <li>An {@link Kind#INTENTIONAL_ERROR} is an error that production code logs because a test arranged the
 * situation, with no injected exception: a partitioned peer, a corrupted file, a refused configuration. It names
 * the exact logger, level, and complete message pattern, and labels an event only during a test that declares it
 * with {@link IntentionalErrors#expect(IntentionalError)}.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public enum IntentionalError {

    /** Used only by the tests of this package, which test the labelling and checks themselves. */
    SELF_TEST_INJECTED_FAILURE,

    /** Used only by the tests of this package, which test the labelling and checks themselves. */
    SELF_TEST_INTENTIONAL_ERROR("dev.mars.qraft.testing.fault.selftest", Level.ERROR,
            "Self-test intentional error \\d+");

    /** Whether a test injects the failure or arranges the situation that produces the error. */
    public enum Kind {
        INJECTED_FAILURE("INJECTED FAILURE", "injected by"),
        INTENTIONAL_ERROR("INTENTIONAL ERROR", "caused by");

        private final String title;
        private final String attribution;

        Kind(String title, String attribution) {
            this.title = title;
            this.attribution = attribution;
        }

        /** The label's opening words, for example {@code INJECTED FAILURE}. */
        public String title() {
            return title;
        }

        /** How the label names the test, for example {@code injected by}. */
        public String attribution() {
            return attribution;
        }
    }

    private final Kind kind;
    private final String loggerName;
    private final Level level;
    private final Pattern message;

    IntentionalError() {
        this.kind = Kind.INJECTED_FAILURE;
        this.loggerName = null;
        this.level = null;
        this.message = null;
    }

    IntentionalError(String loggerName, Level level, String messagePattern) {
        this.kind = Kind.INTENTIONAL_ERROR;
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.message = Pattern.compile(Objects.requireNonNull(messagePattern, "messagePattern"));
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Whether {@code event} is this intentional error: the same logger and level, and a formatted message that the
     * pattern matches completely. Always false for an injected failure, which is recognised by its exception.
     */
    boolean matches(ILoggingEvent event) {
        return kind == Kind.INTENTIONAL_ERROR
                && loggerName.equals(event.getLoggerName())
                && level.equals(event.getLevel())
                && message.matcher(event.getFormattedMessage()).matches();
    }
}
