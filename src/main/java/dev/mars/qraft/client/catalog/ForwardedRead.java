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

package dev.mars.qraft.client.catalog;

import java.util.Optional;

/**
 * A server's answer to a read a client forwards for an application, to be passed on as it came: its status and body,
 * and the headers that describe them.
 *
 * @param status   the server's status
 * @param body     the server's body
 * @param index    its {@code X-Qraft-Index}, when it sent one
 * @param leaderId its {@code X-Qraft-Leader-Id}, when it sent one
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public record ForwardedRead(int status, String body, Optional<String> index, Optional<String> leaderId) {
}
