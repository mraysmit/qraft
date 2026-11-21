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

package dev.mars.qraft.raft;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests of {@link DockerImageBuildHelper}: the runtime image is built once a run, and a build that fails is never tried
 * again, so a daemon fault is reported to every later Docker class rather than retried away.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-19
 * @version 1.0
 */
class DockerImageBuildHelperTest {

    @Test
    void aBuildThatSucceedsIsMadeOnceForTheWholeRun() {
        AtomicInteger builds = new AtomicInteger();
        DockerImageBuildHelper image = new DockerImageBuildHelper(builds::incrementAndGet);

        image.ensureBuilt();
        image.ensureBuilt();
        image.ensureBuilt();

        assertEquals(1, builds.get());
    }

    @Test
    void aBuildThatFailsIsNeverTriedAgainAndEveryLaterCallerIsGivenTheFirstFailure() {
        AtomicInteger builds = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("the daemon lost a snapshot its build cache names");
        DockerImageBuildHelper image = new DockerImageBuildHelper(() -> {
            builds.incrementAndGet();
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, image::ensureBuilt));
        IllegalStateException later = assertThrows(IllegalStateException.class, image::ensureBuilt);

        assertSame(failure, later.getCause(), "a later Docker class is told why the image is missing");
        assertEquals(1, builds.get(), "the failed build is not tried again");
    }
}
