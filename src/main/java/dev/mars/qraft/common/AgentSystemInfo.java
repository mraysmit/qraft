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

/**
 * System information for a Qraft agent.
 * Contains the hardware and operating-system details an agent reports about its node.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-26
 * @version 2.0
 */
public class AgentSystemInfo {

    @JsonProperty("operatingSystem")
    private String operatingSystem;

    @JsonProperty("architecture")
    private String architecture;

    @JsonProperty("javaVersion")
    private String javaVersion;

    @JsonProperty("totalMemory")
    private long totalMemory; // bytes

    @JsonProperty("availableMemory")
    private long availableMemory; // bytes

    @JsonProperty("totalDiskSpace")
    private long totalDiskSpace; // bytes

    @JsonProperty("availableDiskSpace")
    private long availableDiskSpace; // bytes

    @JsonProperty("cpuCores")
    private int cpuCores;

    @JsonProperty("cpuUsage")
    private double cpuUsage; // percentage (0.0 to 100.0)

    @JsonProperty("loadAverage")
    private double loadAverage;

    /**
     * Default constructor.
     */
    public AgentSystemInfo() {
    }

    /**
     * Get the operating system name.
     * 
     * @return the operating system
     */
    public String getOperatingSystem() {
        return operatingSystem;
    }

    /**
     * Set the operating system name.
     * 
     * @param operatingSystem the operating system
     */
    public void setOperatingSystem(String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    /**
     * Get the system architecture.
     * 
     * @return the architecture
     */
    public String getArchitecture() {
        return architecture;
    }

    /**
     * Set the system architecture.
     * 
     * @param architecture the architecture
     */
    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    /**
     * Get the Java version.
     * 
     * @return the Java version
     */
    public String getJavaVersion() {
        return javaVersion;
    }

    /**
     * Set the Java version.
     * 
     * @param javaVersion the Java version
     */
    public void setJavaVersion(String javaVersion) {
        this.javaVersion = javaVersion;
    }

    /**
     * Get the total system memory in bytes.
     * 
     * @return the total memory
     */
    public long getTotalMemory() {
        return totalMemory;
    }

    /**
     * Set the total system memory in bytes.
     * 
     * @param totalMemory the total memory
     */
    public void setTotalMemory(long totalMemory) {
        this.totalMemory = totalMemory;
    }

    /**
     * Get the available system memory in bytes.
     * 
     * @return the available memory
     */
    public long getAvailableMemory() {
        return availableMemory;
    }

    /**
     * Set the available system memory in bytes.
     * 
     * @param availableMemory the available memory
     */
    public void setAvailableMemory(long availableMemory) {
        this.availableMemory = availableMemory;
    }

    /**
     * Get the total disk space in bytes.
     * 
     * @return the total disk space
     */
    public long getTotalDiskSpace() {
        return totalDiskSpace;
    }

    /**
     * Set the total disk space in bytes.
     * 
     * @param totalDiskSpace the total disk space
     */
    public void setTotalDiskSpace(long totalDiskSpace) {
        this.totalDiskSpace = totalDiskSpace;
    }

    /**
     * Get the available disk space in bytes.
     * 
     * @return the available disk space
     */
    public long getAvailableDiskSpace() {
        return availableDiskSpace;
    }

    /**
     * Set the available disk space in bytes.
     * 
     * @param availableDiskSpace the available disk space
     */
    public void setAvailableDiskSpace(long availableDiskSpace) {
        this.availableDiskSpace = availableDiskSpace;
    }

    /**
     * Get the number of CPU cores.
     * 
     * @return the CPU cores
     */
    public int getCpuCores() {
        return cpuCores;
    }

    /**
     * Set the number of CPU cores.
     * 
     * @param cpuCores the CPU cores
     */
    public void setCpuCores(int cpuCores) {
        this.cpuCores = cpuCores;
    }

    /**
     * Get the current CPU usage percentage.
     * 
     * @return the CPU usage (0.0 to 100.0)
     */
    public double getCpuUsage() {
        return cpuUsage;
    }

    /**
     * Set the current CPU usage percentage.
     * 
     * @param cpuUsage the CPU usage (0.0 to 100.0)
     */
    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    /**
     * Get the system load average.
     * 
     * @return the load average
     */
    public double getLoadAverage() {
        return loadAverage;
    }

    /**
     * Set the system load average.
     * 
     * @param loadAverage the load average
     */
    public void setLoadAverage(double loadAverage) {
        this.loadAverage = loadAverage;
    }

    @Override
    public String toString() {
        return "AgentSystemInfo{" +
                "operatingSystem='" + operatingSystem + '\'' +
                ", architecture='" + architecture + '\'' +
                ", javaVersion='" + javaVersion + '\'' +
                ", totalMemory=" + totalMemory +
                ", availableMemory=" + availableMemory +
                ", cpuCores=" + cpuCores +
                ", cpuUsage=" + cpuUsage +
                '}';
    }
}
