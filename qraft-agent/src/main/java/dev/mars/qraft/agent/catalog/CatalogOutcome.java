package dev.mars.qraft.agent.catalog;

/** Machine-actionable result of one catalog operation against one controller. */
public sealed interface CatalogOutcome
        permits CatalogOutcome.Success, CatalogOutcome.Retryable, CatalogOutcome.Rejected {

    /** A parsed 2xx response. {@code changed} is false for an idempotent no-op. */
    record Success(String serviceId, boolean changed) implements CatalogOutcome { }

    /** A transport or server outcome that may succeed at another controller or later. */
    record Retryable(String code, String message, String leaderId) implements CatalogOutcome { }

    /** A validation or semantic outcome that must not be retried unchanged. */
    record Rejected(String code, String message, String leaderId) implements CatalogOutcome { }
}
