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

package dev.mars.qraft.common.dns;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The HTTP reads a DNS lookup is answered from (DNS discovery list, decision 2): a server runs them on its own routes,
 * and a client forwards them to the servers, each under its DNS token.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
@FunctionalInterface
public interface DnsReads {
    /**
     * The answer to {@code GET pathAndQuery} with the scope headers {@code scope}, such as {@code X-Qraft-Tenant};
     * empty when no server could be asked.
     */
    CompletableFuture<Optional<Read>> read(String pathAndQuery, Map<String, String> scope);

    /** A read's status and body. */
    record Read(int status, String body) {
    }
}
