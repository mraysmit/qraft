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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final int HEADER = 12;
    private static final int QR = 0x8000;
    private static final int AA = 0x0400;
    private static final int TC = 0x0200;
    private static final int RD = 0x0100;
    private static final int OPT_SIZE = 11;
    private static final int MAX_NAME = 255;

    private DnsCodec() {
    }

    /** Reads the query in the first {@code length} bytes of {@code packet}. */
    public static DnsQuery parse(byte[] packet, int length) throws DnsFormatException {
        if (length < HEADER || length > packet.length) throw new DnsFormatException("shorter than a header");
        int id = u16(packet, 0);
        int flags = u16(packet, 2);
        if ((flags & QR) != 0) throw new DnsFormatException("a response, not a query");
        int opcode = (flags >> 11) & 0xf;
        boolean rd = (flags & RD) != 0;
        if (opcode != 0) throw new DnsFormatException("opcode " + opcode, id, opcode, rd, NOTIMP);
        int questions = u16(packet, 4);
        if (questions != 1) {
            throw new DnsFormatException(questions + " questions", id, opcode, rd, FORMERR);
        }
        int records = u16(packet, 6) + u16(packet, 8);
        int additional = u16(packet, 10);
        try {
            Cursor cursor = new Cursor(packet, length, HEADER);
            List<String> labels = cursor.name();
            int type = cursor.u16();
            int qclass = cursor.u16();
            for (int i = 0; i < records; i++) cursor.skipRecord();
            int ednsSize = 0;
            for (int i = 0; i < additional; i++) {
                List<String> owner = cursor.name();
                int recordType = cursor.u16();
                int recordClass = cursor.u16();
                cursor.skip(4);
                cursor.skip(cursor.u16());
                if (recordType == TYPE_OPT && owner.isEmpty()) ednsSize = Math.max(UDP_SIZE, recordClass);
            }
            return new DnsQuery(id, rd, labels, type, qclass, ednsSize);
        } catch (IllegalArgumentException malformed) {
            throw new DnsFormatException(malformed.getMessage(), id, opcode, rd, FORMERR);
        }
    }

    /**
     * Writes {@code answer} to {@code query} in at most {@code limit} bytes. Answer and authority records that do not
     * fit are left out and the answer is marked truncated; additional records that do not fit are left out.
     */
    public static byte[] encode(DnsQuery query, DnsAnswer answer, int limit) {
        Writer out = new Writer();
        out.u16(query.id());
        out.u16(0);
        for (int count = 0; count < 4; count++) out.u16(0);
        out.name(query.labels());
        out.u16(query.type());
        out.u16(query.qclass());
        int room = limit - (query.ednsUdpSize() > 0 ? OPT_SIZE : 0);
        boolean truncated = false;
        int[] counts = new int[3];
        List<List<DnsRecord>> sections = List.of(answer.answers(), answer.authority(), answer.additional());
        for (int section = 0; section < 3 && !truncated; section++) {
            for (DnsRecord record : sections.get(section)) {
                int mark = out.mark();
                out.record(record);
                if (out.size() > room) {
                    out.reset(mark);
                    // Leaving out an additional record leaves the answer whole (RFC 2181, section 9).
                    truncated = section < 2;
                    break;
                }
                counts[section]++;
            }
        }
        if (query.ednsUdpSize() > 0) {
            out.u8(0);
            out.u16(TYPE_OPT);
            out.u16(MAX_UDP_SIZE);
            out.u32(0);
            out.u16(0);
        }
        byte[] packet = out.toByteArray();
        int flags = QR | (query.recursionDesired() ? RD : 0) | (answer.authoritative() ? AA : 0)
                | (truncated ? TC : 0) | (answer.rcode() & 0xf);
        put16(packet, 2, flags);
        put16(packet, 4, 1);
        put16(packet, 6, counts[0]);
        put16(packet, 8, counts[1]);
        put16(packet, 10, counts[2] + (query.ednsUdpSize() > 0 ? 1 : 0));
        return packet;
    }

    /** Writes the answer to a packet that could not be read: its header alone, with the code it was refused with. */
    public static byte[] encodeError(DnsFormatException refused) {
        if (!refused.answerable()) throw new IllegalArgumentException("A dropped packet has no answer");
        byte[] packet = new byte[HEADER];
        put16(packet, 0, refused.id());
        put16(packet, 2, QR | ((refused.opcode() & 0xf) << 11) | (refused.recursionDesired() ? RD : 0)
                | (refused.rcode() & 0xf));
        return packet;
    }

    private static int u16(byte[] packet, int offset) {
        return ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
    }

    private static void put16(byte[] packet, int offset, int value) {
        packet[offset] = (byte) (value >> 8);
        packet[offset + 1] = (byte) value;
    }

    /** Reads a packet forward from a position, refusing anything that runs past its end. */
    private static final class Cursor {
        private final byte[] packet;
        private final int end;
        private int position;

        Cursor(byte[] packet, int end, int position) {
            this.packet = packet;
            this.end = end;
            this.position = position;
        }

        private void need(int at, int bytes) {
            if (at + bytes > end) throw new IllegalArgumentException("runs past the end of the packet");
        }

        int u16() {
            need(position, 2);
            int value = DnsCodec.u16(packet, position);
            position += 2;
            return value;
        }

        void skip(int bytes) {
            need(position, bytes);
            position += bytes;
        }

        void skipRecord() {
            name();
            skip(8);
            skip(u16());
        }

        /** A name, following compression pointers backwards only, so a name cannot loop. */
        List<String> name() {
            List<String> labels = new ArrayList<>();
            int at = position;
            int limit = at;
            boolean jumped = false;
            int length = 0;
            while (true) {
                need(at, 1);
                int size = packet[at] & 0xff;
                if (size == 0) {
                    if (!jumped) position = at + 1;
                    return labels;
                }
                if ((size & 0xc0) == 0xc0) {
                    need(at, 2);
                    int target = DnsCodec.u16(packet, at) & 0x3fff;
                    if (target >= limit) throw new IllegalArgumentException("a name points forward or at itself");
                    if (!jumped) position = at + 2;
                    jumped = true;
                    at = target;
                    limit = target;
                    continue;
                }
                if ((size & 0xc0) != 0) throw new IllegalArgumentException("an unknown label type");
                need(at + 1, size);
                length += size + 1;
                if (length > MAX_NAME) throw new IllegalArgumentException("a name longer than 255 bytes");
                labels.add(new String(packet, at + 1, size, StandardCharsets.ISO_8859_1));
                at += 1 + size;
            }
        }
    }

    /** Writes a message, compressing each name against those written before it. */
    private static final class Writer {
        private byte[] bytes = new byte[UDP_SIZE];
        private int size;
        private final Map<String, Integer> written = new HashMap<>();

        int size() {
            return size;
        }

        int mark() {
            return size;
        }

        /** Takes back what was written after {@code mark}, and the names it held. */
        void reset(int mark) {
            size = mark;
            written.values().removeIf(offset -> offset >= mark);
        }

        private void ensure(int more) {
            if (size + more > bytes.length) bytes = java.util.Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
        }

        void u8(int value) {
            ensure(1);
            bytes[size++] = (byte) value;
        }

        void u16(int value) {
            u8(value >> 8);
            u8(value);
        }

        void u32(long value) {
            u16((int) (value >> 16));
            u16((int) value);
        }

        void write(byte[] data) {
            ensure(data.length);
            System.arraycopy(data, 0, bytes, size, data.length);
            size += data.length;
        }

        void name(String dotted) {
            name(dotted.isEmpty() ? List.of() : List.of(dotted.split("\\.")));
        }

        void name(List<String> labels) {
            for (int i = 0; i < labels.size(); i++) {
                String suffix = String.join(".", labels.subList(i, labels.size())).toLowerCase(Locale.ROOT);
                Integer earlier = written.get(suffix);
                if (earlier != null) {
                    u16(0xc000 | earlier);
                    return;
                }
                byte[] label = labels.get(i).getBytes(StandardCharsets.ISO_8859_1);
                if (label.length == 0 || label.length > 63) {
                    throw new IllegalArgumentException("A label must be 1 to 63 bytes: " + labels);
                }
                if (size < 0x3fff) written.put(suffix, size);
                u8(label.length);
                write(label);
            }
            u8(0);
        }

        void record(DnsRecord record) {
            name(record.name());
            u16(record.type());
            u16(CLASS_IN);
            u32(record.ttl());
            int lengthAt = size;
            u16(0);
            switch (record.data()) {
                case DnsRecord.A a -> write(a.address().getAddress());
                case DnsRecord.Aaaa aaaa -> write(aaaa.address().getAddress());
                case DnsRecord.Srv srv -> {
                    u16(srv.priority());
                    u16(srv.weight());
                    u16(srv.port());
                    name(srv.target());
                }
                case DnsRecord.Soa soa -> {
                    name(soa.primary());
                    name(soa.mailbox());
                    u32(soa.serial());
                    u32(soa.refresh());
                    u32(soa.retry());
                    u32(soa.expire());
                    u32(soa.minimum());
                }
            }
            put16(bytes, lengthAt, size - lengthAt - 2);
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(bytes, size);
        }
    }
}
