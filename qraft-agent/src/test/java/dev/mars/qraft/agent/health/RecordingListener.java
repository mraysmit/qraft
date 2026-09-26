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

package dev.mars.qraft.agent.health;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Collects delivered check results; probes complete on protocol threads, so reads wait for delivery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class RecordingListener implements CheckResultListener {
    private final BlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();

    @Override
    public void onResult(HealthCheckDefinition check, CheckResult result) {
        deliveries.add(new Delivery(check, result));
    }

    CheckResult next() throws InterruptedException {
        return nextDelivery().result();
    }

    Delivery nextDelivery() throws InterruptedException {
        Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(delivery, "expected a check result");
        return delivery;
    }

    void assertNoResult() throws InterruptedException {
        assertNull(deliveries.poll(100, TimeUnit.MILLISECONDS), "unexpected check result");
    }

    record Delivery(HealthCheckDefinition check, CheckResult result) {
    }
}
