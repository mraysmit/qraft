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

package dev.mars.qraft.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link TestPortsHelper}. Jenkins build 85 failed {@code KubernetesAuthEndToEndTest} when the kernel offered
 * port 35461 for server2's API gRPC port after the test had already given it to server1's Raft port, which server1 had
 * bound by then.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-20
 * @version 1.0
 */
class TestPortsHelperTest {

    @Test
    void aPortTheSourceOffersAgainIsSkippedForTheNextOne() throws IOException {
        Iterator<Integer> offers = List.of(35461, 35461, 39659).iterator();
        TestPortsHelper ports = new TestPortsHelper(offers::next);

        assertEquals(35461, ports.next());
        assertEquals(39659, ports.next());
    }

    @Test
    void aSourceThatOffersOnlyPortsAlreadyGivenOutIsRefusedRatherThanAskedForever() throws IOException {
        TestPortsHelper ports = new TestPortsHelper(() -> 35461);
        ports.next();

        IOException refused = assertThrows(IOException.class, ports::next);
        assertTrue(refused.getMessage().contains("35461"), refused.getMessage());
    }

    @Test
    void theSystemsPortsAreAllDistinct() throws IOException {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) assertTrue(seen.add(TestPortsHelper.freePort()), "port given out twice");
    }
}
