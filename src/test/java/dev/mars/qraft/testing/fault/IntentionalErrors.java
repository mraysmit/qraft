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
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Labels and checks the errors that tests cause on purpose. Tests call {@link #expect}; the rest is used by
 * {@link IntentionalErrorExtension}, {@link IntentionalErrorLabel}, and {@link IntentionalErrorCheck}.
 *
 * <p>Each test runs in a window that the extension opens before it and closes after it, and each test class in an
 * enclosing window. Tests run one at a time, so an event belongs to the innermost open window whatever thread
 * logs it. When a window closes, it reports:
 * <ul>
 * <li>every ERROR event, and every event carrying an exception, that is neither an injected failure nor an
 * intentional error the test declared;</li>
 * <li>every declared intentional error whose count is outside what the test declared.</li>
 * </ul>
 * Nothing is filtered: every event is still written to the log, with its label or without one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class IntentionalErrors {

    private static final Object LOCK = new Object();
    private static final Deque<Window> WINDOWS = new ArrayDeque<>();
    private static final List<String> OUTSIDE_ANY_TEST = new ArrayList<>();

    private IntentionalErrors() {
    }

    /** Declares that the running test causes {@code error} at least once. */
    public static void expect(IntentionalError error) {
        declare(error, 1, Integer.MAX_VALUE);
    }

    /** Declares that the running test causes {@code error} exactly {@code times} times. */
    public static void expect(IntentionalError error, int times) {
        if (times < 1) throw new IllegalArgumentException("times must be at least 1");
        declare(error, times, times);
    }

    private static void declare(IntentionalError error, int minimum, int maximum) {
        Objects.requireNonNull(error, "error");
        synchronized (LOCK) {
            Window window = WINDOWS.peek();
            if (window == null) throw new IllegalStateException("expect(" + error + ") was called outside a test");
            window.expected.put(error, new int[] {minimum, maximum});
        }
    }

    /** Opens the window of a test or test class, named in labels as {@code owner}. */
    static void begin(String owner) {
        synchronized (LOCK) {
            WINDOWS.push(new Window(Objects.requireNonNull(owner, "owner")));
        }
    }

    /** Closes the innermost window, which must be {@code owner}'s, and returns its problems; empty when it passes. */
    static List<String> end(String owner) {
        synchronized (LOCK) {
            Window window = WINDOWS.peek();
            if (window == null || !window.owner.equals(owner)) {
                return List.of("intentional-error window mismatch: closing " + owner + " but the open window is "
                        + (window == null ? "none" : window.owner));
            }
            WINDOWS.pop();
            List<String> problems = new ArrayList<>(window.problems);
            window.expected.forEach((error, bounds) -> {
                int seen = window.seen.getOrDefault(error, 0);
                if (seen < bounds[0] || seen > bounds[1]) {
                    problems.add(error.kind().title() + " " + error + " was declared "
                            + (bounds[0] == bounds[1] ? "exactly " + bounds[0] : "at least " + bounds[0])
                            + " time(s) but occurred " + seen + " time(s)");
                }
            });
            return problems;
        }
    }

    /** The owner of the innermost open window, if one is open. */
    static Optional<String> openWindow() {
        synchronized (LOCK) {
            return Optional.ofNullable(WINDOWS.peek()).map(window -> window.owner);
        }
    }

    /** Returns, and forgets, the problems logged while no window was open. */
    static List<String> drainOutsideAnyTest() {
        synchronized (LOCK) {
            List<String> problems = List.copyOf(OUTSIDE_ANY_TEST);
            OUTSIDE_ANY_TEST.clear();
            return problems;
        }
    }

    /**
     * The label for {@code event}: {@code "*** INJECTED FAILURE: <entry>, injected by <test> *** "},
     * {@code "*** INTENTIONAL ERROR: <entry>, caused by <test> *** "}, or the empty string.
     */
    static String label(ILoggingEvent event) {
        synchronized (LOCK) {
            Window window = WINDOWS.peek();
            return classify(event, window)
                    .map(error -> "*** " + error.kind().title() + ": " + error + ", " + error.kind().attribution()
                            + " " + (window == null ? "no running test" : window.owner) + " *** ")
                    .orElse("");
        }
    }

    /** Counts {@code event} against the innermost window, or records it as a problem when it is not intentional. */
    static void record(ILoggingEvent event) {
        synchronized (LOCK) {
            Window window = WINDOWS.peek();
            Optional<IntentionalError> intentional = classify(event, window);
            if (intentional.isPresent()) {
                if (window != null) window.seen.merge(intentional.get(), 1, Integer::sum);
                return;
            }
            if (event.getLevel().isGreaterOrEqual(Level.ERROR) || event.getThrowableProxy() != null) {
                String problem = "unlabelled " + describe(event);
                if (window == null) OUTSIDE_ANY_TEST.add(problem);
                else window.problems.add(problem);
            }
        }
    }

    private static Optional<IntentionalError> classify(ILoggingEvent event, Window window) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy instanceof ThrowableProxy throwableProxy) {
            Optional<InjectedFault> fault = InjectedFault.in(throwableProxy.getThrowable());
            if (fault.isPresent()) return Optional.of(fault.get().error());
        }
        if (window != null) {
            for (IntentionalError declared : window.expected.keySet()) {
                if (declared.matches(event)) return Optional.of(declared);
            }
        }
        return Optional.empty();
    }

    private static String describe(ILoggingEvent event) {
        StringBuilder text = new StringBuilder()
                .append(event.getLevel()).append(" [").append(event.getThreadName()).append("] ")
                .append(event.getLoggerName()).append(" - ").append(event.getFormattedMessage());
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy != null) text.append(" | ").append(proxy.getClassName()).append(": ").append(proxy.getMessage());
        return text.toString();
    }

    private static final class Window {
        private final String owner;
        private final Map<IntentionalError, int[]> expected = new EnumMap<>(IntentionalError.class);
        private final Map<IntentionalError, Integer> seen = new EnumMap<>(IntentionalError.class);
        private final List<String> problems = new ArrayList<>();

        private Window(String owner) {
            this.owner = owner;
        }
    }
}
