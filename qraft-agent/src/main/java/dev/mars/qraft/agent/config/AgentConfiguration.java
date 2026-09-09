package dev.mars.qraft.agent.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Pure Java configuration for a Qraft discovery agent. */
public final class AgentConfiguration {
    private final String agentId;
    private final String hostname;
    private final String address;
    private final int agentPort;
    private final String region;
    private final String datacenter;
    private final String controllerUrl;
    private final long heartbeatInterval;
    private final int httpConnectionTimeout;
    private final String version;

    private AgentConfiguration(Builder b) {
        agentId = b.agentId; hostname = b.hostname; address = b.address;
        agentPort = b.agentPort; region = b.region; datacenter = b.datacenter;
        controllerUrl = b.controllerUrl; heartbeatInterval = b.heartbeatInterval;
        httpConnectionTimeout = b.httpConnectionTimeout; version = b.version;
    }

    public static AgentConfiguration fromEnvironment() {
        String host;
        String address;
        try {
            InetAddress local = InetAddress.getLocalHost();
            host = local.getHostName(); address = local.getHostAddress();
        } catch (UnknownHostException e) {
            host = "unknown"; address = "127.0.0.1";
        }
        return builder()
                .agentId(env("AGENT_ID", "agent-" + host))
                .hostname(host).address(address)
                .agentPort(integer("AGENT_PORT", 8080))
                .region(env("AGENT_REGION", "default"))
                .datacenter(env("AGENT_DATACENTER", "default"))
                .controllerUrl(env("CONTROLLER_URL", "http://localhost:8080/api/v1"))
                .heartbeatInterval(longValue("HEARTBEAT_INTERVAL", 30000))
                .httpConnectionTimeout(integer("HTTP_CONNECTION_TIMEOUT_MS", 5000))
                .version(env("AGENT_VERSION", "1.0.0"))
                .build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static int integer(String name, int fallback) {
        try { return Integer.parseInt(env(name, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    private static long longValue(String name, long fallback) {
        try { return Long.parseLong(env(name, String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    public static Builder builder() { return new Builder(); }
    public String getAgentId() { return agentId; }
    public String getHostname() { return hostname; }
    public String getAddress() { return address; }
    public int getAgentPort() { return agentPort; }
    public String getRegion() { return region; }
    public String getDatacenter() { return datacenter; }
    public String getControllerUrl() { return controllerUrl; }
    public long getHeartbeatInterval() { return heartbeatInterval; }
    public int getHttpConnectionTimeout() { return httpConnectionTimeout; }
    public int getHttpIdleTimeout() { return httpConnectionTimeout; }
    public String getVersion() { return version; }

    public static final class Builder {
        private String agentId;
        private String hostname = "unknown";
        private String address = "127.0.0.1";
        private int agentPort = 8080;
        private String region = "default";
        private String datacenter = "default";
        private String controllerUrl;
        private long heartbeatInterval = 30000;
        private int httpConnectionTimeout = 5000;
        private String version = "1.0.0";
        public Builder agentId(String v) { agentId = v; return this; }
        public Builder hostname(String v) { hostname = v; return this; }
        public Builder address(String v) { address = v; return this; }
        public Builder agentPort(int v) { agentPort = v; return this; }
        public Builder region(String v) { region = v; return this; }
        public Builder datacenter(String v) { datacenter = v; return this; }
        public Builder controllerUrl(String v) { controllerUrl = v; return this; }
        public Builder heartbeatInterval(long v) { heartbeatInterval = v; return this; }
        public Builder httpConnectionTimeout(int v) { httpConnectionTimeout = v; return this; }
        public Builder version(String v) { version = v; return this; }
        public AgentConfiguration build() {
            if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
            if (controllerUrl == null || controllerUrl.isBlank()) throw new IllegalArgumentException("controllerUrl is required");
            if (agentPort < 1 || agentPort > 65535) throw new IllegalArgumentException("agentPort is invalid");
            return new AgentConfiguration(this);
        }
    }
}
