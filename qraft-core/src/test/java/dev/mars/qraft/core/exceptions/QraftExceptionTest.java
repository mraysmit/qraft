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

package dev.mars.qraft.core.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests that {@link InvalidTransitionException} preserves transition context and message, and that
 * {@link QraftException} cause constructors work.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class QraftExceptionTest {
    private enum State { CURRENT, REQUESTED, VALID }

    @Test
    void preservesInvalidTransitionContext() {
        InvalidTransitionException exception = new InvalidTransitionException(
                "agent-1", State.CURRENT, State.REQUESTED, new State[]{State.VALID});

        assertEquals("agent-1", exception.getEntityId());
        assertSame(State.CURRENT, exception.getCurrentState());
        assertSame(State.REQUESTED, exception.getRequestedState());
        assertEquals(1, exception.getValidTransitions().length);
        assertEquals("Invalid transition for 'agent-1': CURRENT -> REQUESTED. Valid targets: [VALID]",
                exception.getMessage());
    }

    @Test
    void supportsCauseConstructors() {
        IllegalStateException cause = new IllegalStateException("cause");
        QraftException withMessage = new QraftException("message", cause);
        QraftException withCause = new QraftException(cause);

        assertEquals("message", withMessage.getMessage());
        assertSame(cause, withMessage.getCause());
        assertSame(cause, withCause.getCause());
    }
}
