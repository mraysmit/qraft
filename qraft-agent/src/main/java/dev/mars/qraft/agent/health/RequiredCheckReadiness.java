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

package dev.mars.qraft.agent.health;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Readiness input derived from local check results: satisfied only when every required check has
 * produced a result and its latest result is not critical. Optional checks never affect readiness.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class RequiredCheckReadiness implements CheckResultListener {
    private final Set<CheckKey> required;
    private final Map<CheckKey, CheckStatus> latest = new ConcurrentHashMap<>();

    public RequiredCheckReadiness(List<HealthCheckDefinition> checks) {
        required = checks.stream().filter(HealthCheckDefinition::required)
                .map(check -> new CheckKey(check.serviceId(), check.checkId()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void onResult(HealthCheckDefinition check, CheckResult result) {
        CheckKey key = new CheckKey(check.serviceId(), check.checkId());
        if (required.contains(key)) latest.put(key, result.status());
    }

    public boolean isSatisfied() {
        for (CheckKey key : required) {
            CheckStatus status = latest.get(key);
            if (status == null || status == CheckStatus.CRITICAL) return false;
        }
        return true;
    }

    private record CheckKey(String serviceId, String checkId) {
    }
}
