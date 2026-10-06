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

import dev.mars.qraft.controller.raft.SharedDockerCluster;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Path;
import java.util.Set;

/** Audits container output around every class tagged {@code docker}. */
public final class DockerLogExtension implements BeforeAllCallback, AfterAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        if (!context.getTags().contains("docker")) return;
        ExpectedDockerErrors annotation = context.getRequiredTestClass().getAnnotation(ExpectedDockerErrors.class);
        DockerLogCapture.beginClass(context.getRequiredTestClass().getSimpleName(),
                annotation == null ? Set.of() : Set.of(annotation.value()));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!context.getTags().contains("docker")) return;
        SharedDockerCluster.captureRunningLogs();
        String configured = System.getProperty("qraft.test.log.dir", Path.of("logs").toString());
        var problems = DockerLogCapture.finishClass(Path.of(configured).resolve("docker"));
        if (!problems.isEmpty()) {
            throw new AssertionError(context.getRequiredTestClass().getSimpleName()
                    + " found " + problems.size() + " unrecognised Docker log error(s):\n  "
                    + String.join("\n  ", problems));
        }
    }
}
