package dev.mars.qraft.core.exceptions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
