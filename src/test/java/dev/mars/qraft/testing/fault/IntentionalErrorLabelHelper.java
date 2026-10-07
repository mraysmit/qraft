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

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Test logging helper implementing the {@code %intentional} conversion word in
 * {@code logback-test.xml}. It writes the label of an injected
 * failure or intentional error, and nothing for any other event. It never changes or drops an event.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class IntentionalErrorLabelHelper extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return IntentionalErrorsHelper.label(event);
    }
}
