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

package dev.mars.qraft.common;

/**
 * The version of the running Qraft build, the same for server mode and client mode.
 *
 * <p>The build writes the project's version into the manifest of the executable jar, as
 * {@code Implementation-Version}. That entry is the only source: no configuration setting names a version. A
 * process that does not run from the jar, as a test run or an IDE does not, has no such entry and reports
 * {@link #DEVELOPMENT}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
public final class QraftVersion {

    /** What a build reports when its classes do not come from the executable jar. */
    public static final String DEVELOPMENT = "development";

    private QraftVersion() {
    }

    /** The version of this build. */
    public static String current() {
        return "not implemented";
    }

    /** The version that a manifest's {@code Implementation-Version} entry gives, or {@link #DEVELOPMENT} without one. */
    static String from(String implementationVersion) {
        return "not implemented";
    }
}
