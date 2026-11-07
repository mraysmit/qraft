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

package dev.mars.qraft.client.acl;

import java.util.Arrays;
import java.util.Optional;

/**
 * A client's four tokens (ACL list, proposal 13), after Consul's agent tokens, under Qraft's names: the {@code default}
 * token, for a request with none; the {@code client} token, Consul's {@code agent}, for the client's own calls for its
 * node; the {@code configFileRegistration} token, for the services and checks in the configuration file; and the
 * {@code recovery} token, Consul's {@code agent_recovery}, for the client's own endpoints with no server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public enum TokenKind {
    DEFAULT("default"), CLIENT("client"), CONFIG_FILE_REGISTRATION("configFileRegistration"), RECOVERY("recovery");

    private final String apiName;

    TokenKind(String apiName) {
        this.apiName = apiName;
    }

    /** The kind's name in {@code PUT /v1/client/token/{kind}} and in what is persisted. */
    public String apiName() {
        return apiName;
    }

    /** The kind named {@code apiName}, if there is one. */
    public static Optional<TokenKind> fromApiName(String apiName) {
        return Arrays.stream(values()).filter(kind -> kind.apiName.equals(apiName)).findFirst();
    }
}
