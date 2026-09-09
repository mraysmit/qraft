package dev.mars.qraft.agent.service;

import dev.mars.qraft.agent.config.AgentConfiguration;

import java.util.concurrent.atomic.AtomicBoolean;

/** Local health state for the discovery agent. */
public final class HealthService {
    private final AgentConfiguration config;
    private final AtomicBoolean running = new AtomicBoolean();
    public HealthService(AgentConfiguration config) { this.config = config; }
    public void start() { running.set(true); }
    public void shutdown() { running.set(false); }
    public boolean isHealthy() { return running.get(); }
    public String agentId() { return config.getAgentId(); }
}
