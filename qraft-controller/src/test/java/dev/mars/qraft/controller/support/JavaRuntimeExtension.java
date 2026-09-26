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
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension that injects a {@link JavaRuntime} and {@link JavaTestContext}, awaits test
 * completion, and closes the runtime after each test.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class JavaRuntimeExtension implements ParameterResolver, AfterTestExecutionCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(JavaRuntimeExtension.class);

    @Override public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == JavaRuntime.class || type == JavaTestContext.class;
    }

    @Override public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NS);
        if (parameter.getParameter().getType() == JavaRuntime.class) {
            return store.getOrComputeIfAbsent("runtime", key -> JavaRuntime.create(), JavaRuntime.class);
        }
        return store.getOrComputeIfAbsent("context", key -> new JavaTestContext(), JavaTestContext.class);
    }

    @Override public void afterTestExecution(ExtensionContext context) throws Exception {
        JavaTestContext testContext = context.getStore(NS).remove("context", JavaTestContext.class);
        if (testContext != null) testContext.assertComplete();
    }

    @Override public void afterEach(ExtensionContext context) {
        JavaRuntime runtime = context.getStore(NS).remove("runtime", JavaRuntime.class);
        if (runtime != null) runtime.close();
    }
}
