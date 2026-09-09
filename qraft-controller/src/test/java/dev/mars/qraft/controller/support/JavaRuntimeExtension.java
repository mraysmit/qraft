package dev.mars.qraft.controller.support;

import dev.mars.qraft.controller.runtime.JavaRuntime;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

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
