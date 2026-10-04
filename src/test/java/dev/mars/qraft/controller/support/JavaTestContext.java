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

package dev.mars.qraft.controller.support;

import dev.mars.qraft.controller.runtime.AsyncResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Completion latch for asynchronous tests: records the first failure and provides succeeding and
 * failing result handlers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class JavaTestContext {
    private final CountDownLatch completion = new CountDownLatch(1);
    private volatile Throwable failure;

    public void completeNow() { completion.countDown(); }
    public void failNow(Throwable error) { failure = error; completion.countDown(); }
    public void failNow(String message) { failNow(new AssertionError(message)); }
    public boolean failed() { return failure != null; }
    public Throwable causeOfFailure() { return failure; }
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completion.await(timeout, unit);
    }
    public void verify(Runnable assertion) {
        try { assertion.run(); } catch (Throwable error) { failNow(error); }
    }
    public <T> Consumer<AsyncResult<T>> succeeding(Consumer<T> action) {
        return result -> {
            if (result.failed()) failNow(result.cause());
            else try { action.accept(result.result()); } catch (Throwable error) { failNow(error); }
        };
    }
    public <T> Consumer<AsyncResult<T>> succeedingThenComplete() {
        return succeeding(ignored -> completeNow());
    }
    public <T> Consumer<AsyncResult<T>> failing(Consumer<Throwable> action) {
        return result -> {
            if (result.succeeded()) failNow("Expected asynchronous operation to fail");
            else try { action.accept(result.cause()); } catch (Throwable error) { failNow(error); }
        };
    }
    void assertComplete() throws Exception {
        if (!completion.await(15, TimeUnit.SECONDS)) throw new AssertionError("Asynchronous test timed out");
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }
}
