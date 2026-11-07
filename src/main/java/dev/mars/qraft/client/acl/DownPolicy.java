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

/**
 * What a client decides when no server answers its resolution of a token (ACL list, proposal 10), with Consul's
 * meanings: {@code extend-cache} uses what is cached however old, and the default policy where nothing is;
 * {@code async-cache} does the same while it asks again in the background; {@code allow} and {@code deny} allow or
 * deny everything that is not freshly cached.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public enum DownPolicy {
    EXTEND_CACHE("extend-cache"), ASYNC_CACHE("async-cache"), ALLOW("allow"), DENY("deny");

    private final String setting;

    DownPolicy(String setting) {
        this.setting = setting;
    }

    /** The policy's name in {@code client.acl.downPolicy}. */
    public String setting() {
        return setting;
    }

    /** The policy named {@code setting}, refused with {@link IllegalArgumentException} when there is none. */
    public static DownPolicy of(String setting) {
        for (DownPolicy policy : values()) {
            if (policy.setting.equals(setting)) return policy;
        }
        throw new IllegalArgumentException("client.acl.downPolicy is extend-cache, async-cache, allow or deny, not "
                + setting);
    }
}
