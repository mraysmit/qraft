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

import java.util.List;
import java.util.Locale;

/**
 * A DNS query as it was asked (DNS discovery list): its ID, whether recursion was desired, its one question, and the
 * UDP size its EDNS record gives.
 *
 * @param id               the query's ID, which its answer carries
 * @param recursionDesired the query's RD flag, which its answer echoes
 * @param labels           the question's name, label by label, as the query wrote it
 * @param type             the question's type
 * @param qclass           the question's class
 * @param ednsUdpSize      the UDP size the query's EDNS record gives, or 0 when it has none
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public record DnsQuery(int id, boolean recursionDesired, List<String> labels, int type, int qclass, int ednsUdpSize) {
    public DnsQuery {
        labels = List.copyOf(labels);
    }

    /** The question's name in lower case, its labels joined by dots, with no final dot. */
    public String name() {
        return String.join(".", labels).toLowerCase(Locale.ROOT);
    }
}
