package dev.mars.qraft.agent.catalog;

/** Machine-actionable result of reading one node-scoped service identity. */
public sealed interface CatalogLookupOutcome permits CatalogLookupOutcome.Present,
        CatalogLookupOutcome.Absent, CatalogLookupOutcome.Retryable, CatalogLookupOutcome.Rejected {

    record Present() implements CatalogLookupOutcome { }
    record Absent() implements CatalogLookupOutcome { }
    record Retryable(String code, String message, String leaderId) implements CatalogLookupOutcome { }
    record Rejected(String code, String message, String leaderId) implements CatalogLookupOutcome { }
}
