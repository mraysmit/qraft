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

package dev.mars.qraft.raft;

import dev.mars.qraft.common.concurrent.Completions;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Logs why a sequenced Raft transition failed, in one way for every handler that does so. A transition refused
 * because the sequencer is draining is the expected end of a node's shutdown, not a fault: it was turned away at
 * admission, before any step of it ran, so nothing was written and nothing is half done. It is logged at debug, as
 * a refused client command is. Any other failure is an error, with its stack trace.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-24
 * @version 1.0
 */
final class TransitionFailureLog {
    private TransitionFailureLog() {
    }

    /**
     * Logs {@code failure} with {@code format}, which ends in a placeholder for the failure's message.
     *
     * @param logger where to log
     * @param format the message, such as {@code "Failed to process vote response for election term {}: {}"}
     * @param failure what the transition failed with, wrapped or not in a completion exception
     * @param leading the arguments for the placeholders before the last one
     */
    static void log(Logger logger, String format, Throwable failure, Object... leading) {
        Throwable cause = Completions.unwrap(failure);
        Object[] withMessage = Arrays.copyOf(leading, leading.length + 1);
        withMessage[leading.length] = cause.getMessage();
        if (cause instanceof RaftTransitionSequencer.DrainingException) {
            logger.debug(format, withMessage);
            return;
        }
        Object[] withTrace = Arrays.copyOf(withMessage, withMessage.length + 1);
        withTrace[withMessage.length] = cause;
        logger.error(format, withTrace);
    }
}
