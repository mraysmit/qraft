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

package dev.mars.qraft.common.concurrent;

import java.util.concurrent.CompletionException;

/**
 * Reads the failure of a {@link java.util.concurrent.CompletionStage}.
 * <p>
 * A stage that fails because an earlier stage failed hands its callbacks a {@link CompletionException}
 * around the failure, while a stage that was failed directly hands them the failure itself. Code that
 * logs a failure, tests its type, or passes it on reads it through {@link #unwrap}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
public final class Completions {

    private Completions() {
    }

    /** The failure that {@code error} carries: its cause if it is a {@link CompletionException} with one. */
    public static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
