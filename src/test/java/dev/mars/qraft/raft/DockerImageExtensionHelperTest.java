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

import dev.mars.qraft.testing.fault.DockerLogExtensionHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link DockerImageExtensionHelper}. The runtime image is built in a class callback, which JUnit's
 * {@code @Timeout} does not bound, and not inside the first test that starts a cluster: on 2026-11-19 a build whose base
 * image had left Docker's cache took 73 s of {@code DockerAclTest}'s 90 s limit, and the test timed out.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-19
 * @version 1.0
 */
class DockerImageExtensionHelperTest {

    @Test
    void aClassTaggedDockerHasTheImageBuiltBeforeItsTestsStart() {
        AtomicInteger builds = new AtomicInteger();

        new DockerImageExtensionHelper(builds::incrementAndGet).beforeAll(classContext(Set.of("docker")));

        assertEquals(1, builds.get());
    }

    @Test
    void aClassNotTaggedDockerBuildsNothing() {
        AtomicInteger builds = new AtomicInteger();

        new DockerImageExtensionHelper(builds::incrementAndGet).beforeAll(classContext(Set.of("e2e")));

        assertEquals(0, builds.get());
    }

    @Test
    void itIsRegisteredForEveryClassAfterTheDockerLogCaptureOpens() {
        List<String> registered = ServiceLoader.load(Extension.class).stream()
                .map(provider -> provider.type().getName()).toList();

        int capture = registered.indexOf(DockerLogExtensionHelper.class.getName());
        int image = registered.indexOf(DockerImageExtensionHelper.class.getName());
        assertTrue(image >= 0, "registered through META-INF/services: " + registered);
        assertTrue(capture >= 0 && capture < image,
                "a failed build is reported inside the class's log capture: " + registered);
    }

    /** A class's context that answers only its tags, which is all the helper may read. */
    private static ExtensionContext classContext(Set<String> tags) {
        return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
                new Class<?>[]{ExtensionContext.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getTags" -> tags;
                    case "toString" -> "class context tagged " + tags;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
