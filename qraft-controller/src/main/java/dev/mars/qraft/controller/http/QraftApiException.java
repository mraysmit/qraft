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

package dev.mars.qraft.controller.http;

/**
 * Exception thrown by API handlers to indicate a known error condition.
 * 
 * <p>This exception carries an {@link ErrorCode} which determines the HTTP
 * status code and error format returned to the client. The 
 * {@link GlobalErrorHandler} will catch this and convert it to a standardized
 * {@link ErrorResponse}.</p>
 * 
 * <p>Usage in handlers:</p>
 * <pre>{@code
 * if (job == null) {
 *     throw QraftApiException.notFound(ErrorCode.TRANSFER_NOT_FOUND, jobId);
 * }
 * }</pre>
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-02-04
 */
public class QraftApiException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * Creates a new API exception with the given error code and message.
     */
    public QraftApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a new API exception with the given error code, message, and cause.
     */
    public QraftApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code for this exception.
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the HTTP status code for this exception.
     */
    public int getHttpStatus() {
        return errorCode.httpStatus();
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a "not found" exception.
     */
    public static QraftApiException notFound(ErrorCode code, Object... args) {
        return new QraftApiException(code, code.formatMessage(args));
    }

    /**
     * Creates a "bad request" exception.
     */
    public static QraftApiException badRequest(ErrorCode code, Object... args) {
        return new QraftApiException(code, code.formatMessage(args));
    }

    /**
     * Creates a "conflict" exception.
     */
    public static QraftApiException conflict(ErrorCode code, Object... args) {
        return new QraftApiException(code, code.formatMessage(args));
    }

    /**
     * Creates an "unavailable" exception (503).
     */
    public static QraftApiException unavailable(ErrorCode code, Object... args) {
        return new QraftApiException(code, code.formatMessage(args));
    }

    /**
     * Creates an "internal error" exception.
     */
    public static QraftApiException internal(ErrorCode code, Throwable cause, Object... args) {
        return new QraftApiException(code, code.formatMessage(args), cause);
    }

    /**
     * Creates an exception for "not leader" scenarios.
     */
    public static QraftApiException notLeader(String currentLeader) {
        return new QraftApiException(
            ErrorCode.NOT_LEADER, 
            ErrorCode.NOT_LEADER.formatMessage(currentLeader != null ? currentLeader : "unknown")
        );
    }

    /**
     * Creates an exception for "no leader" scenarios.
     */
    public static QraftApiException noLeader() {
        return new QraftApiException(ErrorCode.NO_LEADER, ErrorCode.NO_LEADER.messageTemplate());
    }
}
