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

import java.io.Serial;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Test exception fixture used to inject a named failure into production code.
 * It names its
 * {@link IntentionalErrorFixture}, so the log can label every event the failure causes as an injected failure.
 *
 * <p>Where production code reacts to a specific exception type, the test keeps that type and attaches the fault as
 * its cause, for example {@code new IOException("disk full", new InjectedFaultFixture(DISK_FULL, "disk full"))}. The
 * production path under test is unchanged, and the event is still recognised.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class InjectedFaultFixture extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IntentionalErrorFixture error;

    /**
     * @param error   an entry of kind {@link IntentionalErrorFixture.Kind#INJECTED_FAILURE}
     * @param message the failure's message, as production code will see and log it
     */
    public InjectedFaultFixture(IntentionalErrorFixture error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "error");
        if (error.kind() != IntentionalErrorFixture.Kind.INJECTED_FAILURE) {
            throw new IllegalArgumentException(error + " is not an injected failure");
        }
    }

    public IntentionalErrorFixture error() {
        return error;
    }

    /** The injected fault in {@code failure}, its causes, or their suppressed exceptions, if there is one. */
    public static Optional<InjectedFaultFixture> in(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) pending.push(failure);
        while (!pending.isEmpty()) {
            Throwable next = pending.pop();
            if (!visited.add(next)) continue;
            if (next instanceof InjectedFaultFixture fault) return Optional.of(fault);
            if (next.getCause() != null) pending.push(next.getCause());
            for (Throwable suppressed : next.getSuppressed()) pending.push(suppressed);
        }
        return Optional.empty();
    }
}
