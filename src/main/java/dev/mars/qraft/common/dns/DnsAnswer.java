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

/**
 * What a query is answered with (DNS discovery list): its response code and its three sections of records.
 *
 * @param rcode         the response code, such as {@link DnsCodec#NXDOMAIN}
 * @param authoritative whether the answer is the zone's own, which every answer in the domain is
 * @param answers       the answer section
 * @param authority     the authority section, which holds the SOA of a negative answer
 * @param additional    the additional section, such as the addresses of SRV targets
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-09
 * @version 1.0
 */
public record DnsAnswer(int rcode, boolean authoritative, List<DnsRecord> answers, List<DnsRecord> authority,
                        List<DnsRecord> additional) {
    public DnsAnswer {
        answers = List.copyOf(answers);
        authority = List.copyOf(authority);
        additional = List.copyOf(additional);
    }

    /** An answer with no records and the code {@code rcode}, which the domain does not vouch for. */
    public static DnsAnswer of(int rcode) {
        return new DnsAnswer(rcode, false, List.of(), List.of(), List.of());
    }
}
