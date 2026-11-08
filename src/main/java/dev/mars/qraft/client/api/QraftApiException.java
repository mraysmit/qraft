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

package dev.mars.qraft.client.api;

/**
 * A request the server refused (client library list, decision 2): its HTTP status, the error code the server gave, and
 * its message.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class QraftApiException extends RuntimeException {
    private final int status;
    private final String code;

    public QraftApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public QraftApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "unanswered";
    }

    /** The HTTP status, or 0 when no server answered. */
    public int status() {
        return status;
    }

    /** The server's error code, such as {@code key_not_found}, or {@code unanswered}. */
    public String code() {
        return code;
    }
}
