package dev.mars.qraft.core.exceptions;

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


/**
 * Base exception class for all Qraft-related exceptions.
 * Provides a common hierarchy for error handling throughout the system.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-17
 * @version 1.0
 */
public class QraftException extends Exception {
    
    public QraftException(String message) {
        super(message);
    }
    
    public QraftException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public QraftException(Throwable cause) {
        super(cause);
    }
}
