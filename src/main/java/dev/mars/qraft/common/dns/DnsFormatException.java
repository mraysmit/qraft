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

package dev.mars.qraft.common.dns;

/**
 * A packet that is not a query this server answers (DNS discovery list, decision 7). One whose header could be read is
 * answered with {@link #rcode()} under its ID; one whose header could not, or that is itself a response, is dropped.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public final class DnsFormatException extends Exception {
    private static final long serialVersionUID = 1L;
    private final int id;
    private final int opcode;
    private final boolean recursionDesired;
    private final int rcode;

    /** A packet to drop. */
    public DnsFormatException(String message) {
        this(message, -1, 0, false, DnsCodec.FORMERR);
    }

    /** A packet to answer with {@code rcode} under {@code id}, echoing its opcode and RD flag. */
    public DnsFormatException(String message, int id, int opcode, boolean recursionDesired, int rcode) {
        super(message, null, false, false);
        this.id = id;
        this.opcode = opcode;
        this.recursionDesired = recursionDesired;
        this.rcode = rcode;
    }

    /** Whether the packet is answered at all. */
    public boolean answerable() {
        return id >= 0;
    }

    public int id() {
        return id;
    }

    public int opcode() {
        return opcode;
    }

    public boolean recursionDesired() {
        return recursionDesired;
    }

    public int rcode() {
        return rcode;
    }
}
