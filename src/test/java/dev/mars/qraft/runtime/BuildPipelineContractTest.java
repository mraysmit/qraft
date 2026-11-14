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

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jenkins pipeline runs the frontend tests once, in the default stage. On build 75 (2026-11-14) the end-to-end and
 * Docker stages ran Vitest again on the same source, and one of its tests passed Vitest's 5 s in the Docker stage on a
 * node whose four executors other jobs share; a second run tests nothing the first did not.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
class BuildPipelineContractTest {
    private static final Path REPOSITORY = Path.of("").toAbsolutePath();

    @Test
    void theFrontendTestsRunInTheDefaultStageAndNotInTheTaggedStages() throws Exception {
        String pipeline = Files.readString(REPOSITORY.resolve("Jenkinsfile"));

        assertFalse(stage(pipeline, "Default tests and coverage gates").contains("-DskipVitest"),
                "the default stage runs Vitest");
        assertTrue(stage(pipeline, "End-to-end tests").contains("-DskipVitest=true"), "the end-to-end stage skips it");
        assertTrue(stage(pipeline, "Docker cluster tests").contains("-DskipVitest=true"), "the Docker stage skips it");
    }

    @Test
    void theVitestRunObeysItsOwnSwitchWhichFollowsSkipTests() throws Exception {
        String pom = Files.readString(REPOSITORY.resolve("pom.xml"));

        assertTrue(pom.contains("<skipVitest>${skipTests}</skipVitest>"), "-DskipTests skips Vitest too");
        int vitest = pom.indexOf("<id>vitest</id>");
        assertTrue(vitest > 0, "the vitest execution");
        String execution = pom.substring(vitest, pom.indexOf("</execution>", vitest));
        assertTrue(execution.contains("<skip>${skipVitest}</skip>"), execution);
    }

    /** The text of a Jenkinsfile stage, from its declaration to the next one. */
    private static String stage(String pipeline, String name) {
        int start = pipeline.indexOf("stage('" + name + "')");
        assertTrue(start >= 0, "no stage " + name);
        int next = pipeline.indexOf("stage('", start + 1);
        return pipeline.substring(start, next < 0 ? pipeline.length() : next);
    }
}
