package dev.mars.qraft.distributedstate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
