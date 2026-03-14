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

package dev.mars.qraft.core.exceptions;

/**
 * Structured error codes for Qraft operations. Each code uniquely identifies a failure
 * scenario for DevOps/observability tooling (alerting, dashboards, log queries).
 *
 * <p>Error code format: {@code QRAFT-XXYY} where:
 * <ul>
 *   <li>XX = category (10=FTP, 11=SFTP, 12=HTTP, 13=SMB, 14=NFS, 90=protocol routing)</li>
 *   <li>YY = specific error within category</li>
 * </ul>
 *
 * <p>Usage pattern in protocol adapters:
 * <pre>
 * logger.error("[{}] FTP download failed: requestId={}, host={}, error={}",
 *     QRAFT_1001.code(), requestId, host, e.getMessage());
 * logger.debug("FTP download exception details for request: {}", requestId, e);
 * </pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-17
 * @version 1.0
 */
public enum QraftErrorCode {

    // ── FTP/FTPS errors (10xx) ──────────────────────────────────────────────────
    /** FTP/FTPS transfer failed at top-level transfer() method */
    QRAFT_1000("QRAFT-1000", "FTP transfer failed"),
    /** FTP/FTPS download failed (network, I/O, protocol error) */
    QRAFT_1001("QRAFT-1001", "FTP download failed"),
    /** FTP/FTPS upload failed */
    QRAFT_1002("QRAFT-1002", "FTP upload failed"),
    /** Source file does not exist for FTP upload */
    QRAFT_1003("QRAFT-1003", "FTP upload source file not found"),
    /** FTP server rejected connection (non-220 welcome) */
    QRAFT_1004("QRAFT-1004", "FTP connection rejected by server"),
    /** FTP authentication failed (non-230 response) */
    QRAFT_1005("QRAFT-1005", "FTP authentication failed"),
    /** Failed to set FTP binary transfer mode */
    QRAFT_1006("QRAFT-1006", "FTP binary mode setup failed"),
    /** FTP destination URI missing required host */
    QRAFT_1007("QRAFT-1007", "FTP destination URI missing host"),
    /** FTP destination URI missing required path */
    QRAFT_1008("QRAFT-1008", "FTP destination URI missing path"),
    /** FTP server closed connection unexpectedly (EOF on readline) */
    QRAFT_1009("QRAFT-1009", "FTP server closed connection (EOF)"),

    // ── SFTP errors (11xx) ──────────────────────────────────────────────────────
    /** SFTP transfer failed at top-level transfer() method */
    QRAFT_1100("QRAFT-1100", "SFTP transfer failed"),
    /** SFTP transfer routing failed (in performSftpTransfer) */
    QRAFT_1101("QRAFT-1101", "SFTP transfer routing failed"),
    /** SFTP download failed */
    QRAFT_1102("QRAFT-1102", "SFTP download failed"),
    /** SFTP upload failed */
    QRAFT_1103("QRAFT-1103", "SFTP upload failed"),

    // ── HTTP/HTTPS errors (12xx) ────────────────────────────────────────────────
    /** HTTP transfer failed at sync wrapper */
    QRAFT_1200("QRAFT-1200", "HTTP transfer failed"),
    /** HTTP request validation failed */
    QRAFT_1201("QRAFT-1201", "HTTP request validation failed"),
    /** HTTP destination directory creation failed */
    QRAFT_1202("QRAFT-1202", "HTTP destination directory creation failed"),
    /** HTTP download received non-200 response */
    QRAFT_1203("QRAFT-1203", "HTTP download failed: non-200 response"),
    /** HTTP download received empty response body */
    QRAFT_1204("QRAFT-1204", "HTTP download failed: empty response"),
    /** HTTP download checksum mismatch */
    QRAFT_1205("QRAFT-1205", "HTTP download checksum mismatch"),
    /** HTTP download pipeline error */
    QRAFT_1206("QRAFT-1206", "HTTP download pipeline failed"),
    /** HTTP download setup error (before request sent) */
    QRAFT_1207("QRAFT-1207", "HTTP download setup failed"),
    /** HTTP upload source file not found */
    QRAFT_1208("QRAFT-1208", "HTTP upload source file not found"),
    /** HTTP upload pre-upload checksum mismatch */
    QRAFT_1209("QRAFT-1209", "HTTP upload checksum mismatch"),
    /** HTTP PUT received non-success response */
    QRAFT_1210("QRAFT-1210", "HTTP PUT failed: non-success response"),
    /** HTTP upload pipeline error */
    QRAFT_1211("QRAFT-1211", "HTTP upload pipeline failed"),
    /** HTTP upload setup error (before request sent) */
    QRAFT_1212("QRAFT-1212", "HTTP upload setup failed"),
    /** HTTP validation: source URI is null */
    QRAFT_1213("QRAFT-1213", "HTTP validation: source URI is null"),
    /** HTTP validation: destination path is null for download */
    QRAFT_1214("QRAFT-1214", "HTTP validation: destination path is null"),
    /** HTTP validation: destination URI is null for upload */
    QRAFT_1215("QRAFT-1215", "HTTP validation: destination URI is null"),
    /** HTTP validation: protocol cannot handle request type */
    QRAFT_1216("QRAFT-1216", "HTTP validation: unsupported request type"),

    // ── SMB errors (13xx) ───────────────────────────────────────────────────────
    /** SMB transfer failed at top-level transfer() method */
    QRAFT_1300("QRAFT-1300", "SMB transfer failed"),
    /** SMB download failed */
    QRAFT_1301("QRAFT-1301", "SMB download failed"),
    /** SMB upload failed */
    QRAFT_1302("QRAFT-1302", "SMB upload failed"),
    /** Source file does not exist for SMB upload */
    QRAFT_1303("QRAFT-1303", "SMB upload source file not found"),

    // ── NFS errors (14xx) ───────────────────────────────────────────────────────
    /** NFS transfer failed at top-level transfer() method */
    QRAFT_1400("QRAFT-1400", "NFS transfer failed"),
    /** NFS download failed */
    QRAFT_1401("QRAFT-1401", "NFS download failed"),
    /** NFS upload failed */
    QRAFT_1402("QRAFT-1402", "NFS upload failed"),
    /** Source file does not exist for NFS upload */
    QRAFT_1403("QRAFT-1403", "NFS upload source file not found"),

    // ── Protocol routing errors (90xx) ──────────────────────────────────────────
    /** Remote-to-remote transfers not supported */
    QRAFT_9001("QRAFT-9001", "Remote-to-remote transfer not supported"),
    /** Unknown transfer direction */
    QRAFT_9002("QRAFT-9002", "Unknown transfer direction");

    private final String code;
    private final String description;

    QraftErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /** Returns the error code string, e.g. {@code "QRAFT-1001"} */
    public String code() {
        return code;
    }

    /** Returns a human-readable description of the error */
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return code + ": " + description;
    }
}
