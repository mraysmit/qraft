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

package dev.mars.qraft.client.api;

import java.time.Duration;
import java.util.Objects;

/**
 * How a read is made (client library list, decision 3): the index to wait past and how long, as Consul's
 * {@code WaitIndex} and {@code WaitTime}, and the consistency.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record QueryOptions(long waitIndex, Duration waitTime, Consistency consistency) {
    /** The server's default consistency, with no wait. */
    public static final QueryOptions DEFAULT = new QueryOptions(0, Duration.ZERO, Consistency.DEFAULT);

    public enum Consistency { DEFAULT, CONSISTENT, STALE }

    public QueryOptions {
        if (waitIndex < 0) throw new IllegalArgumentException("waitIndex must not be negative: " + waitIndex);
        Objects.requireNonNull(waitTime, "waitTime");
        Objects.requireNonNull(consistency, "consistency");
    }

    /** A blocking read: answered once the index passes {@code index}, or after {@code wait}. */
    public static QueryOptions waitPast(long index, Duration wait) {
        return new QueryOptions(index, wait, Consistency.DEFAULT);
    }

    /** A read the leader answers once it has confirmed it still leads. */
    public static QueryOptions consistent() {
        return new QueryOptions(0, Duration.ZERO, Consistency.CONSISTENT);
    }

    /** A read any server answers from its own state. */
    public static QueryOptions stale() {
        return new QueryOptions(0, Duration.ZERO, Consistency.STALE);
    }
}
