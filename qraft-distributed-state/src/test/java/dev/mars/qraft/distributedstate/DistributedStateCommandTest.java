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

package dev.mars.qraft.distributedstate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link DistributedStateCommand} factory methods and rejection of null required values.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class DistributedStateCommandTest {

    @Test
    void factoryMethodsCreateExpectedCommands() {
        assertEquals(new DistributedStateCommand.Put("key", "value"),
                DistributedStateCommand.put("key", "value"));
        assertEquals(new DistributedStateCommand.Delete("key"),
                DistributedStateCommand.delete("key"));
    }

    @Test
    void commandsRejectNullRequiredValues() {
        assertThrows(NullPointerException.class, () -> new DistributedStateCommand.Put(null, "value"));
        assertThrows(NullPointerException.class, () -> new DistributedStateCommand.Put("key", null));
        assertThrows(NullPointerException.class, () -> new DistributedStateCommand.Delete(null));
    }
}
