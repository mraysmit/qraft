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

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit test support helper that builds the runtime image before the tests of a class tagged {@code docker}.
 * Registered through {@code META-INF/services}, after {@code DockerLogExtensionHelper}, so a failed build is reported
 * inside the class's log capture.
 *
 * <p>The build is made here, in a class callback, because JUnit's {@code @Timeout} bounds test and lifecycle methods
 * but not extension callbacks. Made inside the first test to start a cluster, the build counted against that test's
 * limit: on 2026-11-19 the base image had left Docker's build cache, the build pulled it and took 73 s of
 * {@code DockerAclTest}'s 90 s, and the test timed out. With the cache it takes about a second.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-19
 * @version 1.0
 */
public final class DockerImageExtensionHelper implements BeforeAllCallback {
    private final Runnable imageBuild;

    /** The helper JUnit registers through {@code META-INF/services}, building with the shared fixture. */
    public DockerImageExtensionHelper() {
        this(SharedDockerClusterFixture::ensureImageBuilt);
    }

    DockerImageExtensionHelper(Runnable imageBuild) {
        this.imageBuild = imageBuild;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (context.getTags().contains("docker")) imageBuild.run();
    }
}
