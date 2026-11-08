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

package dev.mars.qraft.state.acl;

/**
 * What a binding rule binds a login token to (authentication list, Phase 1), as Consul's {@code BindType}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public enum BindType {
    SERVICE, NODE, ROLE, POLICY;

    public String apiName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static BindType ofApiName(String name) {
        for (BindType type : values()) {
            if (type.apiName().equals(name)) return type;
        }
        throw new IllegalArgumentException("BindType must be service, node, role or policy, not " + name);
    }
}
