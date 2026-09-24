package dev.mars.qraft.catalog;

/**
 * Health state advertised for a registered service instance.
 */
public enum ServiceHealth {
    UNKNOWN,
    PASSING,
    WARNING,
    FAILING
}
