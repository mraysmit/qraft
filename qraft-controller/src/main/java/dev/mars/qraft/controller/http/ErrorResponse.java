package dev.mars.qraft.controller.http;

/** Stable HTTP error contract. */
record ErrorResponse(String code, String message, boolean retryable, String requestId) {
}
