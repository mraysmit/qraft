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

package dev.mars.qraft.agent.health;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * One asynchronous check attempt. Implementations must not block the calling thread, must report
 * protocol failures as a critical outcome, and must abandon their work when the future is cancelled.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
@FunctionalInterface
interface Probe {
    CompletableFuture<Outcome> run();

    record Outcome(CheckStatus status, String output) {
        public Outcome {
            Objects.requireNonNull(status, "status");
        }
    }

    /** Propagates cancellation of the derived outcome to the underlying protocol operation. */
    static CompletableFuture<Outcome> linked(CompletableFuture<?> operation, CompletableFuture<Outcome> outcome) {
        outcome.whenComplete((ignored, failure) -> {
            if (outcome.isCancelled()) operation.cancel(true);
        });
        return outcome;
    }

    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
