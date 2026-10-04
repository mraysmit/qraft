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

package dev.mars.qraft.agent.catalog;

/**
 * Machine-actionable result of one catalog operation against one controller.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public sealed interface CatalogOutcome
        permits CatalogOutcome.Success, CatalogOutcome.Retryable, CatalogOutcome.Rejected {

    /** A parsed 2xx response. {@code changed} is false for an idempotent no-op. */
    record Success(String serviceId, boolean changed) implements CatalogOutcome { }

    /** A transport or server outcome that may succeed at another controller or later. */
    record Retryable(String code, String message, String leaderId) implements CatalogOutcome { }

    /** A validation or semantic outcome that must not be retried unchanged. */
    record Rejected(String code, String message, String leaderId) implements CatalogOutcome { }
}
