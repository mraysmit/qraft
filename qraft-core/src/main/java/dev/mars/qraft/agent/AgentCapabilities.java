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
package dev.mars.qraft.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Capabilities advertised by an agent during registration and discovery.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
public class AgentCapabilities {
    @JsonProperty("supportedServices")
    private Set<String> supportedServices = new HashSet<>();
    @JsonProperty("availableRegions")
    private Set<String> availableRegions = new HashSet<>();
    @JsonProperty("customCapabilities")
    private Map<String, Object> customCapabilities = new HashMap<>();
    @JsonProperty("systemInfo")
    private AgentSystemInfo systemInfo;
    @JsonProperty("networkInfo")
    private AgentNetworkInfo networkInfo;

    public Set<String> getSupportedServices() { return supportedServices; }
    public void setSupportedServices(Set<String> services) { supportedServices = services == null ? new HashSet<>() : services; }
    public void addSupportedService(String service) { supportedServices.add(service); }
    public Set<String> getAvailableRegions() { return availableRegions; }
    public void setAvailableRegions(Set<String> regions) { availableRegions = regions == null ? new HashSet<>() : regions; }
    public void addAvailableRegion(String region) { availableRegions.add(region); }
    public Map<String, Object> getCustomCapabilities() { return customCapabilities; }
    public void setCustomCapabilities(Map<String, Object> capabilities) { customCapabilities = capabilities == null ? new HashMap<>() : capabilities; }
    public void addCustomCapability(String key, Object value) { customCapabilities.put(key, value); }
    public AgentSystemInfo getSystemInfo() { return systemInfo; }
    public void setSystemInfo(AgentSystemInfo value) { systemInfo = value; }
    public AgentNetworkInfo getNetworkInfo() { return networkInfo; }
    public void setNetworkInfo(AgentNetworkInfo value) { networkInfo = value; }

    @Override
    public String toString() {
        return "AgentCapabilities{" +
                "supportedServices=" + supportedServices +
                ", availableRegions=" + availableRegions +
                '}';
    }
}
