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

package dev.mars.qraft.testing;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.util.List;

/**
 * Reads what a {@link ListAppender} has captured while the code under test may still be logging.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-21
 * @version 1.0
 */
public final class LogCapturesHelper {
    private LogCapturesHelper() { }

    /**
     * The events {@code appender} holds now. Logback appends under the appender's own lock
     * ({@code AppenderBase.doAppend} is synchronized), so the copy is taken under it: a reader that streams the list
     * while another thread logs meets a {@code ConcurrentModificationException}.
     */
    public static List<ILoggingEvent> snapshot(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }
}
