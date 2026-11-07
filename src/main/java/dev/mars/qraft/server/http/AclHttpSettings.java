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

package dev.mars.qraft.server.http;

import dev.mars.qraft.common.acl.AclDefaultPolicy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What the HTTP API needs to know of a server's ACL settings: whether ACLs are enabled, the server's data
 * directory, where an operator writes {@code acl-bootstrap-reset} (ACL list, proposal 12), the default policy
 * that decides a request no rule of its token matches, and the hash of the secret in
 * {@code server.acl.initialManagementTokenFile}, or null when no file is configured. While a file is configured, the
 * bootstrap endpoint stays closed until a token with that hash is installed, so that no bootstrap can be applied
 * before the file's token (the owner's decision of 2026-11-07). Only the hash is held, never the secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.2
 */
public record AclHttpSettings(boolean enabled, Path dataDirectory, AclDefaultPolicy defaultPolicy,
                              String managementSecretHash) {
    /** The name of the file that holds a bootstrap reset index, as in Consul. */
    public static final String BOOTSTRAP_RESET_FILE = "acl-bootstrap-reset";

    public AclHttpSettings {
        if (enabled) {
            Objects.requireNonNull(dataDirectory, "dataDirectory");
            Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        }
    }

    /** Settings with no management token file. */
    public AclHttpSettings(boolean enabled, Path dataDirectory, AclDefaultPolicy defaultPolicy) {
        this(enabled, dataDirectory, defaultPolicy, null);
    }

    /** ACLs disabled: every request is allowed, and the ACL API answers 401 {@code acl_disabled}. */
    public static AclHttpSettings disabled() {
        return new AclHttpSettings(false, null, null, null);
    }
}
