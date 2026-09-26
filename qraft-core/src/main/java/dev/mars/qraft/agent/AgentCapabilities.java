/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 * @version 1.0
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
    public boolean supportsService(String service) { return supportedServices.contains(service); }
    public Set<String> getAvailableRegions() { return availableRegions; }
    public void setAvailableRegions(Set<String> regions) { availableRegions = regions == null ? new HashSet<>() : regions; }
    public void addAvailableRegion(String region) { availableRegions.add(region); }
    public boolean isAvailableInRegion(String region) { return availableRegions.isEmpty() || availableRegions.contains(region); }
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
