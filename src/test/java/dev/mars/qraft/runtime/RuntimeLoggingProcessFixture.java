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

package dev.mars.qraft.runtime;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

/**
 * Child-JVM fixture for RuntimeLoggingTest. Launches a real mode with production Logback,
 * writes a marker, and closes every resource so its startup log files can be inspected.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
public final class RuntimeLoggingProcessFixture {
    private RuntimeLoggingProcessFixture() { }

    public static void main(String[] args) throws Exception {
        RuntimeLifecycle lifecycle = QraftRuntimeApplication.launch(args);
        try {
            LoggerFactory.getLogger(RuntimeLoggingProcessFixture.class).info("PHASE3_STARTUP_MARKER");
        } finally {
            lifecycle.closeAsync().get(15, TimeUnit.SECONDS);
            ((LoggerContext) LoggerFactory.getILoggerFactory()).stop();
        }
    }
}
