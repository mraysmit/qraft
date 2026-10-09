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

package dev.mars.qraft.common;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents comprehensive information about a Qraft client in the fleet.
 * This class contains all the metadata needed to manage and communicate with an
 * client.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-26
 * @version 1.0
 */
public class ClientInfo {

    public static final String REGISTRATION_ID_METADATA_KEY = "qraft.registrationId";

    @JsonProperty("clientId")
    private String clientId;

    @JsonProperty("hostname")
    private String hostname;

    @JsonProperty("address")
    private String address; // String representation of InetAddress for JSON serialization

    @JsonProperty("port")
    private int port;

    @JsonProperty("capabilities")
    private ClientCapabilities capabilities;

    @JsonProperty("status")
    private ClientStatus status;

    @JsonProperty("registrationTime")
    private Instant registrationTime;

    @JsonProperty("lastHeartbeat")
    private Instant lastHeartbeat;

    @JsonProperty("version")
    private String version;

    @JsonProperty("region")
    private String region;

    @JsonProperty("datacenter")
    private String datacenter;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    /**
     * Default constructor for JSON deserialization.
     */
    public ClientInfo() {
        this.metadata = new HashMap<>();
        this.status = ClientStatus.REGISTERING;
        this.registrationTime = Instant.now();
    }

    /**
     * Constructor for creating client info with basic details.
     * 
     * @param clientId  unique identifier for the client
     * @param hostname the hostname of the client
     * @param address  the IP address of the client
     * @param port     the port the client is listening on
     */
    public ClientInfo(String clientId, String hostname, String address, int port) {
        this();
        this.clientId = clientId;
        this.hostname = hostname;
        this.address = address;
        this.port = port;
    }

    /**
     * Create a deep copy of the given ClientInfo.
     * Used by the Raft state machine to avoid in-place mutation of
     * objects visible to concurrent readers.
     *
     * @param source the client info to copy
     * @return a new ClientInfo instance with the same field values
     */
    public static ClientInfo copyOf(ClientInfo source) {
        ClientInfo copy = new ClientInfo(source.clientId, source.hostname, source.address, source.port);
        copy.capabilities = source.capabilities;
        copy.status = source.status;
        copy.registrationTime = source.registrationTime;
        copy.lastHeartbeat = source.lastHeartbeat;
        copy.version = source.version;
        copy.region = source.region;
        copy.datacenter = source.datacenter;
        if (source.metadata != null) {
            copy.metadata = new HashMap<>(source.metadata);
        }
        return copy;
    }

    /**
     * Get the unique client identifier.
     * 
     * @return the client ID
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Set the client identifier.
     * 
     * @param clientId the client ID
     */
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    /**
     * Get the client hostname.
     * 
     * @return the hostname
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Set the client hostname.
     * 
     * @param hostname the hostname
     */
    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    /**
     * Get the client IP address as a string.
     * 
     * @return the IP address
     */
    public String getAddress() {
        return address;
    }

    /**
     * Set the client IP address.
     * 
     * @param address the IP address
     */
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * Get the client port.
     * 
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Set the client port.
     * 
     * @param port the port number
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Get the client capabilities.
     * 
     * @return the capabilities
     */
    public ClientCapabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Set the client capabilities.
     * 
     * @param capabilities the capabilities
     */
    public void setCapabilities(ClientCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Get the current client status.
     * 
     * @return the status
     */
    public ClientStatus getStatus() {
        return status;
    }

    /**
     * Set the client status.
     * 
     * @param status the status
     */
    public void setStatus(ClientStatus status) {
        this.status = status;
    }

    /**
     * Get the registration timestamp.
     * 
     * @return the registration time
     */
    public Instant getRegistrationTime() {
        return registrationTime;
    }

    /**
     * Set the registration timestamp.
     * 
     * @param registrationTime the registration time
     */
    public void setRegistrationTime(Instant registrationTime) {
        this.registrationTime = registrationTime;
    }

    /**
     * Get the last heartbeat timestamp.
     * 
     * @return the last heartbeat time
     */
    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    /**
     * Set the last heartbeat timestamp.
     * 
     * @param lastHeartbeat the last heartbeat time
     */
    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    /**
     * Get the client version.
     * 
     * @return the version string
     */
    public String getVersion() {
        return version;
    }

    /**
     * Set the client version.
     * 
     * @param version the version string
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * Get the client region.
     * 
     * @return the region
     */
    public String getRegion() {
        return region;
    }

    /**
     * Set the client region.
     * 
     * @param region the region
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Get the client datacenter.
     * 
     * @return the datacenter
     */
    public String getDatacenter() {
        return datacenter;
    }

    /**
     * Set the client datacenter.
     * 
     * @param datacenter the datacenter
     */
    public void setDatacenter(String datacenter) {
        this.datacenter = datacenter;
    }

    /**
     * Get the client metadata.
     * 
     * @return the metadata map
     */
    public Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Set the client metadata.
     * 
     * @param metadata the metadata map
     */
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    /**
     * Add a metadata entry.
     * 
     * @param key   the metadata key
     * @param value the metadata value
     */
    public void addMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    /**
     * Get the client endpoint URL.
     * 
     * @return the endpoint URL
     */
    public String getEndpoint() {
        return "http://" + address + ":" + port;
    }

    /**
     * Check if the client is currently healthy.
     * Delegates to {@link ClientStatus#isHealthy()} for consistent classification.
     *
     * @return true if the client is healthy
     */
    public boolean isHealthy() {
        return status != null && status.isHealthy();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ClientInfo clientInfo = (ClientInfo) o;
        return Objects.equals(clientId, clientInfo.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId);
    }

    @Override
    public String toString() {
        return "ClientInfo{" +
                "clientId='" + clientId + '\'' +
                ", hostname='" + hostname + '\'' +
                ", address='" + address + '\'' +
                ", port=" + port +
                ", status=" + status +
                ", version='" + version + '\'' +
                ", region='" + region + '\'' +
                ", datacenter='" + datacenter + '\'' +
                '}';
    }
}
