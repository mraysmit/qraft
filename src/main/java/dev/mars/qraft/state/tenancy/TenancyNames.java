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

package dev.mars.qraft.state.tenancy;

import java.util.regex.Pattern;

/**
 * The names tenants and namespaces take (tenancy list, decision 2): lower-case DNS labels, letters, digits and hyphens,
 * 1 to 63 characters, starting and ending with a letter or digit.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class TenancyNames {
    private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private TenancyNames() { }

    public static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** What a valid name is, for a refusal. */
    public static String rule() {
        return "lower-case letters, digits and hyphens, 1 to 63 characters, starting and ending with a letter or digit";
    }
}
