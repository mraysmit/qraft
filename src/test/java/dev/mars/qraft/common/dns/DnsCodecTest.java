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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the DNS codec (DNS discovery list, Phase 1): queries are read from packets written byte by byte here, and
 * answers are checked field by field, so that the codec is held to the RFCs rather than to itself. The listener's tests
 * check it against the JDK's own DNS client too.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
class DnsCodecTest {

    /** A query packet: the header with {@code flags} and the counts, a question, and the given additional bytes. */
    private static byte[] query(int id, int flags, int questions, String name, int type, int additionalCount,
                                byte[] additional) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, id);
        writeShort(out, flags);
        writeShort(out, questions);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, additionalCount);
        for (int i = 0; i < questions; i++) {
            writeName(out, name);
            writeShort(out, type);
            writeShort(out, DnsCodec.CLASS_IN);
        }
        out.writeBytes(additional);
        return out.toByteArray();
    }

    private static byte[] query(String name, int type) {
        return query(0x1234, 0x0100, 1, name, type, 0, new byte[0]);
    }

    /** An OPT record offering {@code size} bytes over UDP. */
    private static byte[] opt(int size) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        writeShort(out, DnsCodec.TYPE_OPT);
        writeShort(out, size);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        return out.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.writeBytes(bytes);
        }
        out.write(0);
    }

    private static DnsQuery parse(byte[] packet) throws DnsFormatException {
        return DnsCodec.parse(packet, packet.length);
    }

    private static DnsRecord a(String name, String address) throws Exception {
        return new DnsRecord(name, 0, new DnsRecord.A((Inet4Address) InetAddress.getByName(address)));
    }

    private static DnsRecord soa(String domain) {
        return new DnsRecord(domain, 0, new DnsRecord.Soa("ns." + domain, "hostmaster." + domain, 7, 3600, 600, 86400,
                0));
    }

    private static int u16(byte[] packet, int offset) {
        return ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
    }

    @Test
    void readsAQueryWithItsIdFlagAndQuestion() throws Exception {
        DnsQuery query = parse(query("Web.Service.Qraft", DnsCodec.TYPE_SRV));

        assertEquals(0x1234, query.id());
        assertTrue(query.recursionDesired());
        assertEquals(List.of("Web", "Service", "Qraft"), query.labels(), "the labels as they were written");
        assertEquals("web.service.qraft", query.name());
        assertEquals(DnsCodec.TYPE_SRV, query.type());
        assertEquals(DnsCodec.CLASS_IN, query.qclass());
        assertEquals(0, query.ednsUdpSize(), "no EDNS record");
    }

    @Test
    void readsTheUdpSizeOfAnEdnsRecord() throws Exception {
        DnsQuery query = parse(query(7, 0, 1, "web.service.qraft", DnsCodec.TYPE_A, 1, opt(1232)));

        assertFalse(query.recursionDesired());
        assertEquals(1232, query.ednsUdpSize());
    }

    @Test
    void anEdnsSizeBelow512CountsAs512() throws Exception {
        assertEquals(DnsCodec.UDP_SIZE, parse(query(7, 0, 1, "a.qraft", DnsCodec.TYPE_A, 1, opt(100))).ednsUdpSize());
    }

    @Test
    void dropsAPacketShorterThanAHeaderAndAResponse() {
        DnsFormatException tooShort = assertThrows(DnsFormatException.class,
                () -> DnsCodec.parse(new byte[11], 11));
        assertFalse(tooShort.answerable());
        DnsFormatException response = assertThrows(DnsFormatException.class,
                () -> parse(query(1, 0x8000, 1, "a.qraft", DnsCodec.TYPE_A, 0, new byte[0])));
        assertFalse(response.answerable(), "a response is never answered, so two servers cannot answer each other");
    }

    @Test
    void refusesAQueryWithoutExactlyOneQuestionAsFormErr() {
        DnsFormatException two = assertThrows(DnsFormatException.class,
                () -> parse(query(9, 0x0100, 2, "a.qraft", DnsCodec.TYPE_A, 0, new byte[0])));
        assertTrue(two.answerable());
        assertEquals(9, two.id());
        assertTrue(two.recursionDesired());
        assertEquals(DnsCodec.FORMERR, two.rcode());
        assertEquals(DnsCodec.FORMERR, assertThrows(DnsFormatException.class,
                () -> parse(query(9, 0, 0, "a.qraft", DnsCodec.TYPE_A, 0, new byte[0]))).rcode());
    }

    @Test
    void refusesAQuestionThatRunsPastThePacketOrLoopsAsFormErr() {
        byte[] whole = query("web.service.qraft", DnsCodec.TYPE_A);
        assertEquals(DnsCodec.FORMERR, assertThrows(DnsFormatException.class,
                () -> DnsCodec.parse(whole, whole.length - 3)).rcode());
        // A name that points at itself.
        byte[] loop = ByteBuffer.allocate(18).putShort((short) 3).putShort((short) 0).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .put((byte) 0xc0).put((byte) 12).putShort((short) 1).putShort((short) 1).array();
        assertEquals(DnsCodec.FORMERR, assertThrows(DnsFormatException.class, () -> parse(loop)).rcode());
    }

    @Test
    void refusesAnOpcodeOtherThanQueryAsNotImp() {
        DnsFormatException status = assertThrows(DnsFormatException.class,
                () -> parse(query(4, 2 << 11, 1, "a.qraft", DnsCodec.TYPE_A, 0, new byte[0])));
        assertTrue(status.answerable());
        assertEquals(DnsCodec.NOTIMP, status.rcode());
        assertEquals(2, status.opcode());
    }

    @Test
    void writesAnAnswerWithItsHeaderQuestionAndRecords() throws Exception {
        DnsQuery query = parse(query("Web.service.qraft", DnsCodec.TYPE_A));
        DnsAnswer answer = new DnsAnswer(DnsCodec.NOERROR, true, List.of(a("web.service.qraft", "10.0.0.7")),
                List.of(), List.of());

        byte[] packet = DnsCodec.encode(query, answer, DnsCodec.UDP_SIZE);

        assertEquals(0x1234, u16(packet, 0));
        int flags = u16(packet, 2);
        assertTrue((flags & 0x8000) != 0, "QR: a response");
        assertEquals(0, (flags >> 11) & 0xf, "opcode QUERY");
        assertTrue((flags & 0x0400) != 0, "AA: the domain's own answer");
        assertEquals(0, flags & 0x0200, "not truncated");
        assertTrue((flags & 0x0100) != 0, "RD echoed");
        assertEquals(0, flags & 0x0080, "RA: no recursion is offered");
        assertEquals(DnsCodec.NOERROR, flags & 0xf);
        assertEquals(1, u16(packet, 4));
        assertEquals(1, u16(packet, 6));
        assertEquals(0, u16(packet, 8));
        assertEquals(0, u16(packet, 10));
        byte[] question = new byte[19];
        System.arraycopy(packet, 12, question, 0, 19);
        assertArrayEquals(new byte[] {3, 'W', 'e', 'b', 7, 's', 'e', 'r', 'v', 'i', 'c', 'e', 5, 'q', 'r', 'a', 'f',
                't', 0}, question, "the question as it was asked");
        int rr = 12 + 19 + 4;
        assertEquals(0xc00c, u16(packet, rr), "the record's name points at the question's");
        assertEquals(DnsCodec.TYPE_A, u16(packet, rr + 2));
        assertEquals(DnsCodec.CLASS_IN, u16(packet, rr + 4));
        assertEquals(0, ByteBuffer.wrap(packet, rr + 6, 4).getInt(), "the TTL");
        assertEquals(4, u16(packet, rr + 10));
        assertArrayEquals(new byte[] {10, 0, 0, 7}, java.util.Arrays.copyOfRange(packet, rr + 12, rr + 16));
        assertEquals(rr + 16, packet.length);
    }

    @Test
    void writesSrvAaaaAndSoaRecordsInTheirSections() throws Exception {
        DnsQuery query = parse(query("web.service.qraft", DnsCodec.TYPE_SRV));
        DnsRecord srv = new DnsRecord("web.service.qraft", 30,
                new DnsRecord.Srv(1, 1, 8443, "fd000000000000000000000000000001.addr.qraft"));
        DnsRecord aaaa = new DnsRecord("fd000000000000000000000000000001.addr.qraft", 30,
                new DnsRecord.Aaaa((Inet6Address) InetAddress.getByName("fd00::1")));

        byte[] packet = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true, List.of(srv),
                List.of(soa("qraft")), List.of(aaaa)), DnsCodec.UDP_SIZE);

        assertEquals(1, u16(packet, 6));
        assertEquals(1, u16(packet, 8));
        assertEquals(1, u16(packet, 10));
        Reader reader = new Reader(packet);
        reader.skipQuestion();
        Reader.Rr first = reader.next();
        assertEquals("web.service.qraft", first.name());
        assertEquals(DnsCodec.TYPE_SRV, first.type());
        assertEquals(30, first.ttl());
        Reader data = first.data();
        assertEquals(1, data.u16());
        assertEquals(1, data.u16());
        assertEquals(8443, data.u16());
        assertEquals("fd000000000000000000000000000001.addr.qraft", data.name());
        Reader.Rr authority = reader.next();
        assertEquals("qraft", authority.name());
        assertEquals(DnsCodec.TYPE_SOA, authority.type());
        Reader soaData = authority.data();
        assertEquals("ns.qraft", soaData.name());
        assertEquals("hostmaster.qraft", soaData.name());
        assertEquals(7, soaData.u32());
        assertEquals(3600, soaData.u32());
        assertEquals(600, soaData.u32());
        assertEquals(86400, soaData.u32());
        assertEquals(0, soaData.u32());
        Reader.Rr extra = reader.next();
        assertEquals("fd000000000000000000000000000001.addr.qraft", extra.name());
        assertEquals(DnsCodec.TYPE_AAAA, extra.type());
        assertEquals(16, extra.length());
        assertEquals(packet.length, reader.position(), "nothing after the records");
    }

    @Test
    void compressesNamesItHasWrittenBefore() throws Exception {
        DnsQuery query = parse(query("web.service.qraft", DnsCodec.TYPE_SRV));
        List<DnsRecord> records = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            records.add(new DnsRecord("web.service.qraft", 0, new DnsRecord.Srv(1, 1, 80, "0a00000" + i + ".addr.qraft")));
        }

        byte[] packet = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(), List.of()),
                DnsCodec.UDP_SIZE);

        // The first target writes its own label and "addr", and points at the question's "qraft"; the second writes
        // its own label and points at the first's "addr.qraft". Uncompressed, each record would take 12 bytes for
        // its name pointer and fixed fields, 6 for the SRV fields and 21 for the target.
        Reader reader = new Reader(packet);
        reader.skipQuestion();
        assertEquals("0a000001.addr.qraft", reader.next().data().skip(6).name());
        assertEquals("0a000002.addr.qraft", reader.next().data().skip(6).name());
        int uncompressed = 12 + 23 + 2 * (12 + 6 + 21);
        assertTrue(packet.length < uncompressed, "names are compressed: " + packet.length + " < " + uncompressed);
    }

    @Test
    void trimsAnAnswerToItsLimitAndMarksItTruncated() throws Exception {
        DnsQuery query = parse(query("web.service.qraft", DnsCodec.TYPE_A));
        List<DnsRecord> records = new ArrayList<>();
        for (int i = 0; i < 40; i++) records.add(a("web.service.qraft", "10.0.0." + i));

        byte[] packet = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(soa("qraft")),
                List.of()), DnsCodec.UDP_SIZE);

        assertTrue(packet.length <= DnsCodec.UDP_SIZE, "fits: " + packet.length);
        assertTrue((u16(packet, 2) & 0x0200) != 0, "TC: truncated");
        int kept = u16(packet, 6);
        // 12 + 23 for the header and question, 16 a record.
        assertEquals((DnsCodec.UDP_SIZE - 35) / 16, kept);
        assertEquals(0, u16(packet, 8), "nothing after a cut answer section");
        assertEquals(35 + 16 * kept, packet.length);

        byte[] whole = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(), List.of()),
                DnsCodec.TCP_SIZE);
        assertEquals(40, u16(whole, 6), "over TCP the whole answer fits");
        assertEquals(0, u16(whole, 2) & 0x0200);
    }

    @Test
    void leavesOutAdditionalRecordsThatDoNotFitWithoutMarkingTruncation() throws Exception {
        DnsQuery query = parse(query("web.service.qraft", DnsCodec.TYPE_A));
        List<DnsRecord> extra = new ArrayList<>();
        for (int i = 0; i < 40; i++) extra.add(a("x" + i + ".addr.qraft", "10.0.1." + i));

        byte[] packet = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true,
                List.of(a("web.service.qraft", "10.0.0.1")), List.of(), extra), DnsCodec.UDP_SIZE);

        assertTrue(packet.length <= DnsCodec.UDP_SIZE);
        assertEquals(0, u16(packet, 2) & 0x0200, "the answer is whole");
        assertEquals(1, u16(packet, 6));
        assertTrue(u16(packet, 10) > 0 && u16(packet, 10) < 40, "as many additional records as fit");
    }

    @Test
    void answersAnEdnsQueryWithItsOwnOptRecordWithinTheSizeItGave() throws Exception {
        DnsQuery query = parse(query(7, 0, 1, "web.service.qraft", DnsCodec.TYPE_A, 1, opt(1232)));
        List<DnsRecord> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) records.add(a("web.service.qraft", "10.0.0." + i));

        byte[] packet = DnsCodec.encode(query, new DnsAnswer(DnsCodec.NOERROR, true, records, List.of(), List.of()),
                query.ednsUdpSize());

        assertTrue(packet.length <= 1232, "fits the size the query gave: " + packet.length);
        assertTrue((u16(packet, 2) & 0x0200) != 0);
        assertEquals(1, u16(packet, 10), "the OPT record");
        Reader reader = new Reader(packet);
        reader.skipQuestion();
        for (int i = 0; i < u16(packet, 6); i++) reader.next();
        Reader.Rr opt = reader.next();
        assertEquals("", opt.name());
        assertEquals(DnsCodec.TYPE_OPT, opt.type());
        assertEquals(DnsCodec.MAX_UDP_SIZE, opt.qclass(), "the size this server takes");
        assertEquals(packet.length, reader.position());
    }

    @Test
    void writesTheAnswerToAPacketItCouldNotReadAsItsHeaderAlone() {
        byte[] packet = DnsCodec.encodeError(new DnsFormatException("two questions", 0xbeef, 0, true,
                DnsCodec.FORMERR));

        assertEquals(12, packet.length);
        assertEquals(0xbeef, u16(packet, 0));
        int flags = u16(packet, 2);
        assertTrue((flags & 0x8000) != 0);
        assertTrue((flags & 0x0100) != 0);
        assertEquals(DnsCodec.FORMERR, flags & 0xf);
        for (int count = 4; count < 12; count += 2) assertEquals(0, u16(packet, count));
        byte[] notImp = DnsCodec.encodeError(new DnsFormatException("status", 1, 2, false, DnsCodec.NOTIMP));
        assertEquals(2, (u16(notImp, 2) >> 11) & 0xf, "the opcode echoed");
        assertEquals(DnsCodec.NOTIMP, u16(notImp, 2) & 0xf);
    }

    /** Reads the records of a packet, following compression pointers, independently of the codec. */
    private static final class Reader {
        private final byte[] packet;
        private int position;
        private final int end;

        Reader(byte[] packet) {
            this(packet, 12, packet.length);
        }

        private Reader(byte[] packet, int position, int end) {
            this.packet = packet;
            this.position = position;
            this.end = end;
        }

        record Rr(String name, int type, int qclass, long ttl, int length, Reader data) {
        }

        int position() {
            return position;
        }

        int u16() {
            int value = DnsCodecTest.u16(packet, position);
            position += 2;
            return value;
        }

        long u32() {
            long value = ByteBuffer.wrap(packet, position, 4).getInt() & 0xffff_ffffL;
            position += 4;
            return value;
        }

        Reader skip(int bytes) {
            position += bytes;
            return this;
        }

        String name() {
            StringBuilder name = new StringBuilder();
            int at = position;
            boolean jumped = false;
            while (true) {
                int length = packet[at] & 0xff;
                if (length == 0) {
                    if (!jumped) position = at + 1;
                    break;
                }
                if ((length & 0xc0) == 0xc0) {
                    if (!jumped) position = at + 2;
                    jumped = true;
                    at = DnsCodecTest.u16(packet, at) & 0x3fff;
                    continue;
                }
                if (!name.isEmpty()) name.append('.');
                name.append(new String(packet, at + 1, length, StandardCharsets.US_ASCII));
                at += 1 + length;
            }
            return name.toString();
        }

        void skipQuestion() {
            name();
            position += 4;
        }

        Rr next() {
            String name = name();
            int type = u16();
            int qclass = u16();
            long ttl = u32();
            int length = u16();
            Reader data = new Reader(packet, position, position + length);
            position += length;
            assertTrue(position <= end);
            return new Rr(name, type, qclass, ttl, length, data);
        }
    }
}
