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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which failure {@link Completions#unwrap} reads from what a stage hands its callbacks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-12
 * @version 1.0
 */
class CompletionsTest {

    @Test
    void aFailurePassedOnByAnEarlierStageIsReadWithoutItsWrapper() {
        IOException failure = new IOException("disk");
        Throwable seen = CompletableFuture.<String>failedFuture(failure)
                .thenApply(String::length)
                .handle((length, error) -> error)
                .join();

        assertSame(failure, Completions.unwrap(seen));
    }

    @Test
    void aFailureThatIsNotWrappedIsReadAsItIs() {
        IOException failure = new IOException("disk");

        assertSame(failure, Completions.unwrap(failure));
    }

    @Test
    void aWrapperWithoutACauseIsTheFailure() {
        CompletionException bare = new CompletionException("no cause", null);

        assertSame(bare, Completions.unwrap(bare));
    }

    @Test
    void noFailureIsReadAsNone() {
        assertNull(Completions.unwrap(null));
    }
}
