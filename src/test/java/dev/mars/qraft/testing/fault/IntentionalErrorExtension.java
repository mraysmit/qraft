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

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails a test that logs an error it did not cause on purpose. Registered for every test through
 * {@code META-INF/services} and {@code junit.jupiter.extensions.autodetection.enabled}.
 *
 * <p>It opens a window for each test class and each test. The test's window closes after the test's own
 * {@code @AfterEach} methods, so teardown is included, and the class's window after its {@code @AfterAll} methods.
 * A window with problems fails its test or class; problems logged while no window was open fail the next class to
 * close. A test also fails when the check is not attached to the root logger, because its errors would go
 * unchecked.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public final class IntentionalErrorExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        IntentionalErrors.begin(classOwner(context));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        IntentionalErrors.begin(testOwner(context));
        IntentionalErrorCheck.requireAttachedTo(IntentionalErrorCheck.root());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        failIfAny(testOwner(context), IntentionalErrors.end(testOwner(context)));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        List<String> problems = new ArrayList<>(IntentionalErrors.end(classOwner(context)));
        IntentionalErrors.drainOutsideAnyTest().forEach(problem -> problems.add(problem + " (logged outside any test)"));
        failIfAny(classOwner(context), problems);
    }

    private static String classOwner(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName();
    }

    private static String testOwner(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName() + "#" + context.getRequiredTestMethod().getName();
    }

    private static void failIfAny(String owner, List<String> problems) {
        if (problems.isEmpty()) return;
        throw new AssertionError(owner + " logged " + problems.size() + " error(s) that no test caused on purpose."
                + " Declare an intended one in dev.mars.qraft.testing.fault; fix any other:\n  "
                + String.join("\n  ", problems));
    }
}
