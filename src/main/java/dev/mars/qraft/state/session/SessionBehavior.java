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

package dev.mars.qraft.state.session;

import java.util.Locale;

/**
 * What becomes of the keys a session holds when it is invalidated, as Consul's {@code Behavior}: they are released,
 * keeping their values, or deleted (sessions list, Phase 1).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public enum SessionBehavior {
    RELEASE,
    DELETE;

    /** The behaviour's name in Consul's API. */
    public String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The behaviour Consul's API names {@code name}. */
    public static SessionBehavior ofApiName(String name) {
        for (SessionBehavior behavior : values()) {
            if (behavior.apiName().equals(name)) return behavior;
        }
        throw new IllegalArgumentException("Behavior must be release or delete, not " + name);
    }
}
