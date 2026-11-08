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

package dev.mars.qraft.common.events;

import java.util.Objects;

/**
 * Why a subscription ended, and the cursor a new one resumes after (architecture sections 8 and 11): it was cancelled,
 * fell behind and overflowed its queue, its consumer failed, its source closed, or its cursor was older than the
 * retained history or of another boot. A subscriber is told; it is never left to miss events silently.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record SubscriptionEnd(Reason reason, EventCursor resumeAfter) {
    public enum Reason { CANCELLED, OVERFLOW, FAILED, CLOSED, HISTORY_GAP, OTHER_BOOT }

    public SubscriptionEnd {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(resumeAfter, "resumeAfter");
    }
}
