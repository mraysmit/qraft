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

package dev.mars.qraft.controller.testsupport;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Emits stable, searchable lifecycle events for remediation regression tests.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-14
 * @version 1.0
 */
public final class RemediationTestExtension implements BeforeEachCallback, TestWatcher {
    public static final String REMEDIATION_ID_MDC_KEY = "remediationId";
    public static final String REMEDIATION_PHASE_MDC_KEY = "remediationPhase";

    private static final Logger LOGGER = LoggerFactory.getLogger(RemediationTestExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        RemediationTest metadata = metadata(context);
        String scenario = scenario(metadata, context);
        MDC.put(REMEDIATION_ID_MDC_KEY, scenario);
        MDC.put(REMEDIATION_PHASE_MDC_KEY, metadata.phase());
        log(metadata.phase(), scenario, "START", null);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        finish(context, "PASS", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        finish(context, "FAIL", cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        finish(context, "ABORT", cause);
    }

    /** Marks a deliberately injected failure so it is not mistaken for a regression. */
    public static void logExpectedFailure(String checkpoint, Throwable failure) {
        logExpectedFailure(checkpoint, failure.getClass().getSimpleName(), failure.getMessage());
    }

    /** Marks an expected failure before invoking a fixture that reports only a protocol rejection. */
    public static void logExpectedFailure(String checkpoint, String failureType, String message) {
        String scenario = valueOrUnknown(MDC.get(REMEDIATION_ID_MDC_KEY));
        String phase = valueOrUnknown(MDC.get(REMEDIATION_PHASE_MDC_KEY));
        LOGGER.info("[REMEDIATION-TEST] Scenario={} Phase={} Event=EXPECTED_FAILURE Checkpoint={} Failure={} Message={}",
                scenario, phase, checkpoint, failureType, message);
    }

    private void finish(ExtensionContext context, String event, Throwable failure) {
        RemediationTest metadata = metadata(context);
        String scenario = scenario(metadata, context);
        try {
            log(metadata.phase(), scenario, event, failure);
        } finally {
            MDC.remove(REMEDIATION_ID_MDC_KEY);
            MDC.remove(REMEDIATION_PHASE_MDC_KEY);
        }
    }

    private static void log(String phase, String scenario, String event, Throwable failure) {
        if (failure == null) {
            LOGGER.info("[REMEDIATION-TEST] Scenario={} Phase={} Event={}", scenario, phase, event);
        } else {
            LOGGER.error("[REMEDIATION-TEST] Scenario={} Phase={} Event={} Failure={} Message={}",
                    scenario, phase, event, failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    private static RemediationTest metadata(ExtensionContext context) {
        return requireNonNull(
                context.getRequiredTestClass().getAnnotation(RemediationTest.class),
                "RemediationTestExtension requires @RemediationTest");
    }

    private static String scenario(RemediationTest metadata, ExtensionContext context) {
        return metadata.scenarioPrefix() + "." + context.getRequiredTestMethod().getName();
    }

    private static String valueOrUnknown(String value) {
        return Optional.ofNullable(value).filter(candidate -> !candidate.isBlank()).orElse("unknown");
    }
}
