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

import java.util.Objects;

/**
 * A {@code kubernetes} auth method's configuration (Kubernetes auth method list, decision 2), as Consul's: the cluster
 * API's address, the PEM CA its certificate must chain to, and the service account token the server reviews logins
 * under, which is a secret: it is never answered by the API, and never written by {@link #toString()}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record KubernetesConfig(String host, String caCertPem, String serviceAccountJwt) implements AuthMethodConfig {
    public KubernetesConfig {
        host = Objects.requireNonNullElse(host, "");
        caCertPem = Objects.requireNonNullElse(caCertPem, "");
        serviceAccountJwt = Objects.requireNonNullElse(serviceAccountJwt, "");
    }

    @Override
    public String toString() {
        return "KubernetesConfig[host=" + host + ", caCertPem=" + caCertPem.length() + " characters, "
                + "serviceAccountJwt=" + (serviceAccountJwt.isEmpty() ? "none" : "redacted") + "]";
    }
}
