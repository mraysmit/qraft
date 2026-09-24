package dev.mars.qraft.agent.catalog;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
