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

package dev.mars.qraft.agent.catalog;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ControllerRetryPolicy} exponential backoff with a cap and injected jitter, and
 * prompt cancellation of a pending backoff.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class ControllerRetryPolicyTest {
    @Test
    void growsExponentiallyCapsAndUsesInjectedJitter() {
        ControllerRetryPolicy minimumJitter = new ControllerRetryPolicy(100, 1_000, () -> 0.0);
        assertEquals(50, minimumJitter.delayMillis(0));
        assertEquals(100, minimumJitter.delayMillis(1));
        assertEquals(400, minimumJitter.delayMillis(3));
        assertEquals(500, minimumJitter.delayMillis(20));

        ControllerRetryPolicy maximumJitter = new ControllerRetryPolicy(100, 1_000, () -> 0.999999);
        assertEquals(100, maximumJitter.delayMillis(0));
        assertEquals(1_000, maximumJitter.delayMillis(20));
    }

    @Test
    void cancellingBackoffCompletesPromptlyWithoutRunningItsAction() throws Exception {
        try (var scheduler = new ScheduledThreadPoolExecutor(1)) {
            ControllerRetryPolicy policy = new ControllerRetryPolicy(10_000, 10_000, () -> 0.0);
            var delay = policy.delay(scheduler, 0);
            assertTrue(delay.cancel(false));
            assertTrue(delay.isCancelled());
        }
    }
}
