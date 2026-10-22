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

package dev.mars.qraft.server.recovery;

/**
 * Raised when {@link OfflineRecovery} refuses a directory or a plan. The source is never changed by a refusal;
 * the message says why, for the operator.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class RecoveryRefusedException extends Exception {
    public RecoveryRefusedException(String message) {
        super(message);
    }

    public RecoveryRefusedException(String message, Throwable cause) {
        super(message, cause);
    }
}
