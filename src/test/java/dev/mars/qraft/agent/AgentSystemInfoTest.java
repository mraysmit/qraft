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

package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the wire form of {@link AgentSystemInfo}, the system facts an agent reports: the exact JSON field set, a
 * round trip that keeps every value, and the fields {@code toString} names.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-27
 * @version 2.0
 */
class AgentSystemInfoTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void serializesOnlyReportedFieldsAndNoDerivedScores() throws Exception {
        TreeSet<String> fields = new TreeSet<>();
        new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(new AgentSystemInfo()))
                .fieldNames().forEachRemaining(fields::add);

        assertEquals(new TreeSet<>(Set.of("operatingSystem", "architecture", "javaVersion",
                "totalMemory", "availableMemory", "totalDiskSpace", "availableDiskSpace", "cpuCores",
                "cpuUsage", "loadAverage")), fields);
    }

    @Test
    void aJsonRoundTripKeepsEveryValue() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        AgentSystemInfo read = mapper.readValue(mapper.writeValueAsString(reported()), AgentSystemInfo.class);

        assertEquals("Linux", read.getOperatingSystem());
        assertEquals("x86_64", read.getArchitecture());
        assertEquals("21.0.1", read.getJavaVersion());
        assertEquals(16 * GIB, read.getTotalMemory());
        assertEquals(8 * GIB, read.getAvailableMemory());
        assertEquals(1024 * GIB, read.getTotalDiskSpace());
        assertEquals(500 * GIB, read.getAvailableDiskSpace());
        assertEquals(12, read.getCpuCores());
        assertEquals(45.5, read.getCpuUsage());
        assertEquals(2.5, read.getLoadAverage());
    }

    @Test
    void toStringNamesThePlatformMemoryAndCpuWithTheirValues() {
        String text = reported().toString();

        for (String expected : List.of("operatingSystem='Linux'", "architecture='x86_64'", "javaVersion='21.0.1'",
                "totalMemory=" + 16 * GIB, "availableMemory=" + 8 * GIB, "cpuCores=12", "cpuUsage=45.5")) {
            assertTrue(text.contains(expected), expected + " in " + text);
        }
    }

    /** System facts with every field set to a value that differs from its default. */
    private static AgentSystemInfo reported() {
        AgentSystemInfo info = new AgentSystemInfo();
        info.setOperatingSystem("Linux");
        info.setArchitecture("x86_64");
        info.setJavaVersion("21.0.1");
        info.setTotalMemory(16 * GIB);
        info.setAvailableMemory(8 * GIB);
        info.setTotalDiskSpace(1024 * GIB);
        info.setAvailableDiskSpace(500 * GIB);
        info.setCpuCores(12);
        info.setCpuUsage(45.5);
        info.setLoadAverage(2.5);
        return info;
    }
}
