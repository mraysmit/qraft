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

package dev.mars.qraft.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the membership configuration contract for promotion and failed-voter cleanup, including
 * acceptance boundaries and rejection during parsing before runtime resources can open.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-15
 * @version 1.0
 */
class AppConfigRaftMembershipTest {
    @Test
    void acceptsAnAbsentMembershipObjectWithExistingTimingSettings() {
        AppConfig config = config("\"electionTimeoutMs\":90000,\"heartbeatIntervalMs\":20000");
        assertDoesNotThrow(config::validate);
        assertDefaults(config, 90_000, 20_000);
        assertDefaults(config(""), 60_000, 10_000);
    }

    @Test
    void acceptsEmptyMembershipAndCleanupObjects() {
        for (String membership : List.of("{}", "{\"cleanup\":{}}")) {
            AppConfig config = membership(membership);
            assertDoesNotThrow(config::validate, membership);
            assertDefaults(config, 60_000, 10_000);
            AppConfig slowIntervals = config("\"electionTimeoutMs\":90000,\"heartbeatIntervalMs\":20000,"
                    + "\"membership\":" + membership);
            assertDoesNotThrow(slowIntervals::validate);
            assertDefaults(slowIntervals, 90_000, 20_000);
        }
    }

    @Test
    void acceptsNonDefaultPromotionAndCleanupSettings() {
        AppConfig config = membership("""
                {"promotionStabilizationMs":12501,"promotionMaxTrailingEntries":17,
                 "cleanup":{"enabled":false,"failureTimeoutMs":75001,"intervalMs":12501,"minVoters":5}}
                """);
        assertDoesNotThrow(config::validate);
        assertEquals(12_501, config.getPromotionStabilizationMs());
        assertEquals(17, config.getPromotionMaxTrailingEntries());
        assertFalse(config.isRaftCleanupEnabled());
        assertEquals(75_001, config.getRaftCleanupFailureTimeoutMs());
        assertEquals(12_501, config.getRaftCleanupIntervalMs());
        assertEquals(5, config.getRaftCleanupMinVoters());
    }

    @Test
    void acceptsZeroPromotionLimitsAndCleanupAtTheTimingBoundaries() {
        AppConfig config = membership("""
                {"promotionStabilizationMs":0,"promotionMaxTrailingEntries":0,
                 "cleanup":{"enabled":true,"failureTimeoutMs":5000,"intervalMs":1000,"minVoters":1}}
                """);
        assertDoesNotThrow(config::validate);
        assertEquals(0, config.getPromotionStabilizationMs());
        assertEquals(0, config.getPromotionMaxTrailingEntries());
        assertTrue(config.isRaftCleanupEnabled());
        assertEquals(5_000, config.getRaftCleanupFailureTimeoutMs());
        assertEquals(1_000, config.getRaftCleanupIntervalMs());
        assertEquals(1, config.getRaftCleanupMinVoters());
    }

    @Test
    void preservesTheLargestRepresentableMembershipValues() {
        AppConfig config = membership("""
                {"promotionStabilizationMs":9223372036854775807,
                 "promotionMaxTrailingEntries":9223372036854775807,
                 "cleanup":{"failureTimeoutMs":9223372036854775807,"intervalMs":9223372036854775807,
                            "minVoters":2147483647}}
                """);
        assertDoesNotThrow(config::validate);
        assertEquals(Long.MAX_VALUE, config.getPromotionStabilizationMs());
        assertEquals(Long.MAX_VALUE, config.getPromotionMaxTrailingEntries());
        assertEquals(Long.MAX_VALUE, config.getRaftCleanupFailureTimeoutMs());
        assertEquals(Long.MAX_VALUE, config.getRaftCleanupIntervalMs());
        assertEquals(Integer.MAX_VALUE, config.getRaftCleanupMinVoters());
    }

    @Test
    void partiallyConfiguredMembershipRetainsDefaultsForOmittedValues() {
        AppConfig config = membership("{\"promotionMaxTrailingEntries\":7,\"cleanup\":{\"enabled\":false}}");
        assertDoesNotThrow(config::validate);
        assertEquals(10_000, config.getPromotionStabilizationMs());
        assertEquals(7, config.getPromotionMaxTrailingEntries());
        assertFalse(config.isRaftCleanupEnabled());
        assertEquals(60_000, config.getRaftCleanupFailureTimeoutMs());
        assertEquals(10_000, config.getRaftCleanupIntervalMs());
        assertEquals(3, config.getRaftCleanupMinVoters(),
                "a single-server configuration remains valid below the cleanup minimum");
    }

    @ParameterizedTest(name = "rejects {1} in {0}")
    @MethodSource("invalidValues")
    void rejectsInvalidValuesDuringParsing(String document, String setting, String reason) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> membership(document), document);
        assertTrue(refused.getMessage().contains(setting), refused.getMessage());
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
    }

    static Stream<Arguments> invalidValues() {
        Stream<Arguments> promotionTypes = Stream.of("promotionStabilizationMs", "promotionMaxTrailingEntries")
                .flatMap(field -> List.of("null", "true", "\"100\"", "1.5", "[]", "{}",
                                "9223372036854775808")
                        .stream().map(value -> Arguments.of("{\"" + field + "\":" + value + "}",
                                field, "integer")));
        Stream<Arguments> cleanupTypes = Stream.of("failureTimeoutMs", "intervalMs", "minVoters")
                .flatMap(field -> List.of("null", "true", "\"100\"", "1.5", "[]", "{}",
                                "9223372036854775808")
                        .stream().map(value -> Arguments.of("{\"cleanup\":{\"" + field + "\":" + value + "}}",
                                field, "integer")));
        Stream<Arguments> booleanTypes = List.of("null", "0", "\"false\"", "[]", "{}")
                .stream().map(value -> Arguments.of("{\"cleanup\":{\"enabled\":" + value + "}}",
                        "enabled", "boolean"));
        Stream<Arguments> ranges = Stream.of(
                Arguments.of("{\"promotionStabilizationMs\":-1}", "promotionStabilizationMs", "negative"),
                Arguments.of("{\"promotionMaxTrailingEntries\":-1}", "promotionMaxTrailingEntries", "negative"),
                Arguments.of("{\"cleanup\":{\"failureTimeoutMs\":0}}", "failureTimeoutMs", "positive"),
                Arguments.of("{\"cleanup\":{\"failureTimeoutMs\":-1}}", "failureTimeoutMs", "positive"),
                Arguments.of("{\"cleanup\":{\"intervalMs\":0}}", "intervalMs", "positive"),
                Arguments.of("{\"cleanup\":{\"intervalMs\":-1}}", "intervalMs", "positive"),
                Arguments.of("{\"cleanup\":{\"minVoters\":0}}", "minVoters", "positive"),
                Arguments.of("{\"cleanup\":{\"minVoters\":-1}}", "minVoters", "positive"),
                Arguments.of("{\"cleanup\":{\"minVoters\":2147483648}}", "minVoters", "integer"),
                Arguments.of("{\"cleanup\":{\"failureTimeoutMs\":4999}}", "failureTimeoutMs", "electionTimeoutMs"),
                Arguments.of("{\"cleanup\":{\"intervalMs\":999}}", "intervalMs", "heartbeatIntervalMs"),
                Arguments.of("{\"cleanup\":{\"enabled\":false,\"failureTimeoutMs\":4999}}",
                        "failureTimeoutMs", "electionTimeoutMs"),
                Arguments.of("{\"cleanup\":{\"enabled\":false,\"intervalMs\":999}}",
                        "intervalMs", "heartbeatIntervalMs"),
                Arguments.of("{\"cleanup\":{\"enabled\":false,\"minVoters\":0}}", "minVoters", "positive"));
        return Stream.of(promotionTypes, cleanupTypes, booleanTypes, ranges).flatMap(stream -> stream);
    }

    @Test
    void comparesExplicitCleanupDurationsWithTheConfiguredRaftIntervals() {
        for (String cleanup : List.of("{\"failureTimeoutMs\":89999}", "{\"intervalMs\":19999}")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> config("\"electionTimeoutMs\":90000,\"heartbeatIntervalMs\":20000,"
                            + "\"membership\":{\"cleanup\":" + cleanup + "}"));
            String setting = cleanup.contains("failureTimeoutMs") ? "failureTimeoutMs" : "intervalMs";
            assertTrue(refused.getMessage().contains(setting), refused.getMessage());
        }
        assertDoesNotThrow(() -> config("""
                "electionTimeoutMs":90000,"heartbeatIntervalMs":20000,
                "membership":{"cleanup":{"failureTimeoutMs":90000,"intervalMs":20000}}
                """).validate());
    }

    @Test
    void rejectsUnknownMembershipAndCleanupSettingsAtTheirActualLocation() {
        IllegalArgumentException membership = assertThrows(IllegalArgumentException.class,
                () -> membership("{\"unexpected\":1}"));
        assertEquals("Unknown server.raft.membership setting: unexpected", membership.getMessage());
        IllegalArgumentException cleanup = assertThrows(IllegalArgumentException.class,
                () -> membership("{\"cleanup\":{\"unexpected\":1}}"));
        assertEquals("Unknown server.raft.membership.cleanup setting: unexpected", cleanup.getMessage());
    }

    @Test
    void requiresMembershipAndCleanupToBeObjects() {
        for (String value : List.of("null", "[]", "true", "1", "\"settings\"")) {
            IllegalArgumentException membership = assertThrows(IllegalArgumentException.class,
                    () -> membership(value));
            assertTrue(membership.getMessage().contains("membership"), membership.getMessage());
            assertTrue(membership.getMessage().contains("object"), membership.getMessage());
            IllegalArgumentException cleanup = assertThrows(IllegalArgumentException.class,
                    () -> membership("{\"cleanup\":" + value + "}"));
            assertTrue(cleanup.getMessage().contains("cleanup"), cleanup.getMessage());
            assertTrue(cleanup.getMessage().contains("object"), cleanup.getMessage());
        }
    }

    @Test
    void rejectsDuplicateMembershipSettings() {
        for (String document : List.of("{\"promotionStabilizationMs\":1,\"promotionStabilizationMs\":2}",
                "{\"cleanup\":{\"enabled\":true,\"enabled\":false}}")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> membership(document));
            assertEquals("Server configuration is not valid JSON", refused.getMessage());
        }
    }

    private static AppConfig membership(String document) {
        return config("\"membership\":" + document);
    }

    private static AppConfig config(String raft) {
        return AppConfig.fromJson("{\"version\":1,\"server\":{\"id\":\"server-a\",\"raft\":{" + raft + "}}}");
    }

    private static void assertDefaults(AppConfig config, long failureTimeoutMs, long intervalMs) {
        assertEquals(10_000, config.getPromotionStabilizationMs());
        assertEquals(250, config.getPromotionMaxTrailingEntries());
        assertTrue(config.isRaftCleanupEnabled());
        assertEquals(failureTimeoutMs, config.getRaftCleanupFailureTimeoutMs());
        assertEquals(intervalMs, config.getRaftCleanupIntervalMs());
        assertEquals(3, config.getRaftCleanupMinVoters());
    }
}
