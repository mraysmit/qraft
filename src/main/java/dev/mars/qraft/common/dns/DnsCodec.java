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
 * Reads DNS queries and writes their answers (RFC 1035, RFC 2782 and RFC 6891's OPT record), for the few messages
 * Qraft's DNS interface exchanges (DNS discovery list, decision 9).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public final class DnsCodec {
    public static final int TYPE_A = 1;
    public static final int TYPE_NS = 2;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_SOA = 6;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_OPT = 41;
    public static final int TYPE_ANY = 255;
    public static final int CLASS_IN = 1;
    public static final int CLASS_ANY = 255;
    public static final int NOERROR = 0;
    public static final int FORMERR = 1;
    public static final int SERVFAIL = 2;
    public static final int NXDOMAIN = 3;
    public static final int NOTIMP = 4;
    public static final int REFUSED = 5;
    /** The size of a UDP answer to a query with no EDNS record. */
    public static final int UDP_SIZE = 512;
    /** The largest UDP answer this server sends, and the size its own EDNS record offers. */
    public static final int MAX_UDP_SIZE = 4096;
    /** The largest answer over TCP, whose length prefix is two bytes. */
    public static final int TCP_SIZE = 65_535;

    private DnsCodec() {
    }

    /** Reads the query in the first {@code length} bytes of {@code packet}. */
    public static DnsQuery parse(byte[] packet, int length) throws DnsFormatException {
        throw new UnsupportedOperationException("not yet");
    }

    /**
     * Writes {@code answer} to {@code query} in at most {@code limit} bytes. Answer and authority records that do not
     * fit are left out and the answer is marked truncated; additional records that do not fit are left out.
     */
    public static byte[] encode(DnsQuery query, DnsAnswer answer, int limit) {
        throw new UnsupportedOperationException("not yet");
    }

    /** Writes the answer to a packet that could not be read: its header alone, with the code it was refused with. */
    public static byte[] encodeError(DnsFormatException refused) {
        throw new UnsupportedOperationException("not yet");
    }
}
