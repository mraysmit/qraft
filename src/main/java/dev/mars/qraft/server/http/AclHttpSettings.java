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

import java.nio.file.Path;
import java.util.Objects;

/**
 * What the HTTP API needs to know of a server's ACL settings: whether ACLs are enabled, and the server's data
 * directory, where an operator writes {@code acl-bootstrap-reset} (ACL list, proposal 12).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public record AclHttpSettings(boolean enabled, Path dataDirectory) {
    /** The name of the file that holds a bootstrap reset index, as in Consul. */
    public static final String BOOTSTRAP_RESET_FILE = "acl-bootstrap-reset";

    public AclHttpSettings {
        if (enabled) Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /** ACLs disabled: the ACL API answers 401 {@code acl_disabled}. */
    public static AclHttpSettings disabled() {
        return new AclHttpSettings(false, null);
    }
}
