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

/**
 * The runtime image's build for a test run, made at most once. A build that fails is kept and never tried again: a
 * daemon that loses a snapshot its build cache names is a fault to investigate, not to retry, so every later caller is
 * given the first failure as its cause.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-19
 * @version 1.0
 */
final class DockerImageBuild {
    private final Runnable build;
    private boolean built;
    private Throwable failure;

    DockerImageBuild(Runnable build) {
        this.build = build;
    }

    /** Builds the image unless this run has already built it, and fails at once if this run's build failed. */
    synchronized void ensureBuilt() {
        if (built) return;
        if (failure != null) {
            throw new IllegalStateException(
                    "The runtime image's build failed earlier in this run and is not tried again", failure);
        }
        try {
            build.run();
            built = true;
        } catch (RuntimeException | Error buildFailure) {
            failure = buildFailure;
            throw buildFailure;
        }
    }
}
