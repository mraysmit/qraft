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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Objects;

/**
 * A resource record of an answer (DNS discovery list): its owner name, its time to live and its data, which also gives
 * its type.
 *
 * @param name the owner name, with no final dot
 * @param ttl  its time to live, in seconds
 * @param data what it holds
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public record DnsRecord(String name, long ttl, Data data) {
    public DnsRecord {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(data, "data");
        if (ttl < 0 || ttl > 0x7fff_ffffL) throw new IllegalArgumentException("ttl out of range: " + ttl);
    }

    /** The record's type. */
    public int type() {
        return data.type();
    }

    /** What a record holds. */
    public sealed interface Data permits A, Aaaa, Srv, Soa {
        int type();
    }

    /** An IPv4 address. */
    public record A(Inet4Address address) implements Data {
        public A {
            Objects.requireNonNull(address, "address");
        }

        @Override
        public int type() {
            return DnsCodec.TYPE_A;
        }
    }

    /** An IPv6 address. */
    public record Aaaa(Inet6Address address) implements Data {
        public Aaaa {
            Objects.requireNonNull(address, "address");
        }

        @Override
        public int type() {
            return DnsCodec.TYPE_AAAA;
        }
    }

    /** A service's port on a target host (RFC 2782). */
    public record Srv(int priority, int weight, int port, String target) implements Data {
        public Srv {
            Objects.requireNonNull(target, "target");
        }

        @Override
        public int type() {
            return DnsCodec.TYPE_SRV;
        }
    }

    /** A zone's start of authority; its minimum is the time a negative answer may be kept. */
    public record Soa(String primary, String mailbox, long serial, long refresh, long retry, long expire, long minimum)
            implements Data {
        public Soa {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(mailbox, "mailbox");
        }

        @Override
        public int type() {
            return DnsCodec.TYPE_SOA;
        }
    }
}
