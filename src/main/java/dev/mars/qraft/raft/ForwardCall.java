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

import dev.mars.qraft.raft.grpc.ForwardResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A forwarded write in flight, and its answer: it is either sent or withdrawn before it is sent, never both. A follower
 * that stops following its leader while the write still waits for its connection withdraws it, and so knows it was
 * not applied; one that was sent may have been (Jenkins build 80: such a write was answered as perhaps applied).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
public final class ForwardCall extends CompletableFuture<ForwardResponse> {
    private static final int PENDING = 0;
    private static final int SENT = 1;
    private static final int WITHDRAWN = 2;

    private final AtomicInteger state = new AtomicInteger(PENDING);

    /** Claims the call for sending; false when it was withdrawn first, and must not be sent. */
    public boolean markSent() {
        return state.compareAndSet(PENDING, SENT) || state.get() == SENT;
    }

    /**
     * Withdraws the call unless it was sent: true when it never will be, and it then fails with
     * {@link ForwardNotSentException}; false when it was sent, and its answer is still to come.
     */
    public boolean withdraw() {
        if (state.compareAndSet(PENDING, WITHDRAWN)) {
            completeExceptionally(new ForwardNotSentException("The write was withdrawn before it was sent"));
            return true;
        }
        return state.get() == WITHDRAWN;
    }
}
