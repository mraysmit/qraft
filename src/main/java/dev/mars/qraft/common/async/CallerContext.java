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

package dev.mars.qraft.common.async;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * The logging (MDC) and tracing (OpenTelemetry) context of the thread that captured it, so that work
 * running later on another thread logs and traces as the work that asked for it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class CallerContext {
    private final Map<String, String> mdc;
    private final Context telemetry;

    private CallerContext(Map<String, String> mdc, Context telemetry) {
        this.mdc = mdc;
        this.telemetry = telemetry;
    }

    /** Captures the current thread's context. */
    public static CallerContext capture() {
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return new CallerContext(mdc == null ? Map.of() : new HashMap<>(mdc), Context.current());
    }

    /** Runs {@code task} under this context, then restores the running thread's own context. */
    public void run(Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (Scope ignored = telemetry.makeCurrent()) {
            if (mdc.isEmpty()) MDC.clear();
            else MDC.setContextMap(mdc);
            task.run();
        } finally {
            if (previous == null || previous.isEmpty()) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
