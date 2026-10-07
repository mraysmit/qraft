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

package dev.mars.qraft.controller.support;

import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.concurrent.TimeUnit;

/**
 * JUnit test support extension that injects a {@link JavaRuntime} and {@link JavaTestContextHelper}, awaits test
 * completion, and closes the runtime after each test, waiting for it to stop. A runtime injected into a
 * class-level method is closed after the class.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class JavaRuntimeExtensionHelper
        implements ParameterResolver, AfterTestExecutionCallback, AfterEachCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(JavaRuntimeExtensionHelper.class);

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == JavaRuntime.class || type == JavaTestContextHelper.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NS);
        if (parameter.getParameter().getType() == JavaRuntime.class) {
            return store.getOrComputeIfAbsent("runtime", key -> JavaRuntime.create(), JavaRuntime.class);
        }
        return store.getOrComputeIfAbsent("context", key -> new JavaTestContextHelper(), JavaTestContextHelper.class);
    }

    @Override public void afterTestExecution(ExtensionContext context) throws Exception {
        JavaTestContextHelper testContext = context.getStore(NS).remove("context", JavaTestContextHelper.class);
        if (testContext != null) testContext.assertComplete();
    }

    @Override public void afterEach(ExtensionContext context) throws Exception {
        close(context.getStore(NS).remove("runtime", JavaRuntime.class));
    }

    /** Closes a runtime a class-level method such as {@code @BeforeAll} resolved, which no test owns. */
    @Override public void afterAll(ExtensionContext context) throws Exception {
        close(context.getStore(NS).remove("runtime", JavaRuntime.class));
    }

    private static void close(JavaRuntime runtime) throws Exception {
        if (runtime != null) runtime.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
