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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LogCapturesHelper}. A local run on 2026-11-21 failed {@code QraftServerAclStartupTest} with a
 * {@code ConcurrentModificationException}: it streamed a {@code ListAppender}'s list while the server's threads were
 * logging into it. Logback appends under the appender's own lock ({@code AppenderBase.doAppend} is synchronized), so a
 * reader that takes the same lock never meets a list being changed.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-21
 * @version 1.0
 */
class LogCapturesHelperTest {

    @Test
    void aSnapshotWaitsForAnAppendInProgressAndHoldsWhatItAdded() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread appending = new Thread(() -> {
            synchronized (appender) {
                holding.countDown();
                await(release);
                appender.list.add(event("added while held"));
            }
        });
        appending.start();
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        AtomicReference<List<ILoggingEvent>> read = new AtomicReference<>();
        Thread reading = new Thread(() -> read.set(LogCapturesHelper.snapshot(appender)));
        reading.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reading.getState() != Thread.State.BLOCKED && reading.getState() != Thread.State.TERMINATED
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Thread.State whileHeld = reading.getState();
        release.countDown();
        appending.join(10_000);
        reading.join(10_000);

        assertEquals(Thread.State.BLOCKED, whileHeld, "the snapshot waits for the append that holds the lock");
        assertEquals(List.of("added while held"),
                read.get().stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void aSnapshotIsACopyThatLaterAppendsLeaveAlone() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        appender.doAppend(event("first"));

        List<ILoggingEvent> snapshot = LogCapturesHelper.snapshot(appender);
        appender.doAppend(event("second"));

        assertEquals(List.of("first"), snapshot.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private static ILoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage(message);
        return event;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
