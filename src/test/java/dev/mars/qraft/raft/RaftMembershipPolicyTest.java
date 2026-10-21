/*
 * Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boundary tests for local policy validation and complete heartbeat-round conversion.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-19
 * @version 1.0
 */
class RaftMembershipPolicyTest {
    @ParameterizedTest
    @CsvSource({"-1,250,60000,10000,3", "10000,-1,60000,10000,3",
            "10000,250,0,10000,3", "10000,250,-1,10000,3",
            "10000,250,60000,0,3", "10000,250,60000,-1,3",
            "10000,250,60000,10000,0", "10000,250,60000,10000,-1"})
    void rejectsInvalidFieldsEvenWhenCleanupIsDisabled(long stabilization, long lag, long failure,
                                                       long interval, int minimum) {
        assertThrows(IllegalArgumentException.class,
                () -> new RaftMembershipPolicy(stabilization, lag, false, failure, interval, minimum));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void validatesCleanupAgainstTheFinalNodeTimings(boolean enabled) {
        RaftMembershipPolicy policy = new RaftMembershipPolicy(0, 0, enabled, 5000, 1000, 1);
        policy.validateFor(5000, 1000);
        assertThrows(IllegalArgumentException.class, () -> policy.validateFor(5001, 1000));
        assertThrows(IllegalArgumentException.class, () -> policy.validateFor(5000, 1001));
    }

    @ParameterizedTest
    @CsvSource({"0,1000", "-1,1000", "5000,0", "5000,-1"})
    void rejectsNonpositiveNodeTimings(long election, long heartbeat) {
        RaftMembershipPolicy policy = RaftMembershipPolicy.defaults(5000, 1000);
        assertThrows(IllegalArgumentException.class, () -> RaftMembershipPolicy.defaults(election, heartbeat));
        assertThrows(IllegalArgumentException.class, () -> policy.validateFor(election, heartbeat));
    }

    @ParameterizedTest
    @CsvSource({"0,1000,0", "1000,1000,1", "1001,1000,2",
            "9223372036854775807,1000,9223372036854776", "9223372036854775807,1,9223372036854775807"})
    void roundsDurationsUpWithoutOverflow(long duration, long heartbeat, long expected) {
        RaftMembershipPolicy policy = new RaftMembershipPolicy(duration, 0, false,
                Math.max(1, duration), Math.max(1, duration), 1);
        assertEquals(expected, policy.promotionStabilizationRounds(heartbeat));
        assertEquals(duration == 0 ? 1 : expected, policy.cleanupFailureTimeoutRounds(heartbeat));
        assertEquals(duration == 0 ? 1 : expected, policy.cleanupIntervalRounds(heartbeat));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidHeartbeatDuringConversion(long heartbeat) {
        RaftMembershipPolicy policy = RaftMembershipPolicy.defaults(5000, 1000);
        assertThrows(IllegalArgumentException.class, () -> policy.promotionStabilizationRounds(heartbeat));
        assertThrows(IllegalArgumentException.class, () -> policy.cleanupFailureTimeoutRounds(heartbeat));
        assertThrows(IllegalArgumentException.class, () -> policy.cleanupIntervalRounds(heartbeat));
    }

    @Test
    void defaultCleanupBoundsExpandToTheNodeTimings() {
        assertEquals(new RaftMembershipPolicy(10_000, 250, true, 60_000, 10_000, 3),
                RaftMembershipPolicy.defaults(5000, 1000));
        assertEquals(new RaftMembershipPolicy(10_000, 250, true, Long.MAX_VALUE, Long.MAX_VALUE, 3),
                RaftMembershipPolicy.defaults(Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
