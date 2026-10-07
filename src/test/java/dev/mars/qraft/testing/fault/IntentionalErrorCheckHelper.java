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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * Test logging helper registered as an appender in {@code logback-test.xml}.
 * It writes nothing and shows every event to
 * {@link IntentionalErrorsHelper}, which counts intentional errors and records unlabelled errors for the running test.
 * The other appenders still receive every event.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class IntentionalErrorCheckHelper extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        IntentionalErrorsHelper.record(event);
    }

    /** The root logger of the running Logback configuration. */
    static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** Throws unless a started check is attached to {@code root}, because the errors logged would go unchecked. */
    static void requireAttachedTo(Logger root) {
        for (Iterator<Appender<ILoggingEvent>> appenders = root.iteratorForAppenders(); appenders.hasNext(); ) {
            if (appenders.next() instanceof IntentionalErrorCheckHelper check && check.isStarted()) return;
        }
        throw new AssertionError("The intentional-error check is not attached to the root logger, so errors would go"
                + " unchecked. Keep logback-test.xml on the test runtime classpath, and do not reset or reconfigure"
                + " Logback in a test.");
    }
}
