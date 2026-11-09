# Task List: DNS Discovery

**Date:** 2026-11-09
**Status:** In progress. Started on 2026-11-09 for the feature validation's last open row, DNS discovery (item 10), when
the owner answered the report that it waited for their decision with "proceed". It runs under the owner's instruction
of 2026-11-07 to keep working without stopping for questions, so the decisions in section 4 were taken as the
recommended options, and the owner can reverse any of them.
**Last updated:** 2026-11-09 (the list written)
**Follows:** [`task-list-write-forwarding-2026-11-08.md`](../docs/archive/task-list-write-forwarding-2026-11-08.md)
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 13
**Validation:** [`QRAFT_FEATURE_VALIDATION_2026-10-21.md`](QRAFT_FEATURE_VALIDATION_2026-10-21.md), the DNS discovery
row and item 10
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

An application finds a service's healthy instances, and a node's address, with an ordinary DNS lookup, as it does
with Consul's DNS interface, from a client or a server, with no Qraft library and no HTTP call.

## 2. Where Qraft stands (2026-11-09)

- Discovery is HTTP only: `GET /v1/health/service/{name}` and `GET /v1/catalog/nodes` on a server, and the same paths
  on a client's local API, which forwards them to the servers.
- The servers decide what a read answers: its ACL filtering, its tenant and namespace, and whether the server is ready
  to read. The client keeps no copy of the catalog.

## 3. Reference: Consul's DNS interface

- Every agent serves DNS, on port 8600 by default, over UDP and TCP, for the `consul.` domain.
- `[tag.]<service>.service[.<namespace>.ns][.<partition>.ap][.<datacenter>.dc].consul` answers A and AAAA records of
  the service's instances that have no critical check; `only_passing` leaves out those with a warning too.
- SRV records, for the same name or for the RFC 2782 form `_<service>._<tag>.service.consul`, where `_tcp` means any
  tag, give each instance's port. The target is `<hex address>.addr.<datacenter>.consul` when the instance has its
  own address, and its A or AAAA record goes in the additional section.
- `<node>.node.consul` answers the node's address.
- A name that is not found is answered NXDOMAIN with the domain's SOA in the authority section. The TTL is 0 by
  default. Answers are shuffled. Over UDP an answer is trimmed to 512 bytes, or to the size the query's EDNS record
  gives, and marked truncated.
- DNS queries carry no token: the agent reads with its `dns` token, else its `default` token.
- Names outside the domain go to the configured recursors, or are refused.

## 4. Decisions

Taken on 2026-11-09 as the recommended options, under the owner's instruction; each can be reversed.

1. **Both modes serve DNS, and it is off until a port is set:** `server.dns` and `client.dns`, each with `host`
   (default: the HTTP host on a server, the bind address on a client), `port`, `domain` (default `qraft`),
   `ttlSeconds` (default 0) and `onlyPassing` (default false). Off by default, so no existing deployment gains a
   listener. The examples use 8600.
2. **DNS asks the same reads as HTTP.** A server answers a lookup from its own `/v1/health/service/{name}` and
   `/v1/catalog/nodes` routes, run in memory through their filters and handlers as a forwarded write is; a client
   forwards them to the servers as its local API does. So ACLs, tenancy, readiness and health mean in DNS what they
   mean in HTTP, and are decided in one place.
3. **Tokens.** A server reads under the token in `server.dns.tokenFile`, else none; a client under its default token,
   as Consul's agent does with no `dns` token. A separate `dns` token kind is left for later.
4. **Names.** `[tag.]<service>.service[.<namespace>.ns][.<tenant>.ap].<domain>`, the RFC 2782 form, `<node>.node.<domain>`
   and `<hex>.addr.<domain>`. A tenant is Consul's admin partition (tenancy list, decision 1), so it takes Consul's
   `ap` label. With neither label a server reads the default tenant and namespace and a client its own, as its local
   API does. Qraft is one Consul datacenter, so a `dc` label is not answered: such a name is NXDOMAIN.
5. **Health.** An instance is answered when its health is passing, warning or unknown (it has no checks, which
   Consul counts as passing) and its node is not unreachable; with `onlyPassing`, a warning leaves it out.
6. **Records.** A and AAAA answers hold the instances whose address is an IP address. An instance whose address is a
   host name appears only in SRV answers, with that name as the target, since a name may hold one CNAME at most. An
   SRV answer's target for an IP address is its `addr` name, with the address in the additional section. `ANY` is
   answered as A and AAAA. Answers are shuffled. The apex answers its SOA.
7. **Codes.** A name in the domain that answers nothing is NXDOMAIN when it names nothing and NOERROR with no answer
   when it names something without records of the type asked, both with the SOA. A name outside the domain is
   REFUSED: there are no recursors, and recursion is not offered. A read the servers refuse for its token is REFUSED;
   a read that fails, or finds no server, is SERVFAIL. A query that cannot be parsed is FORMERR when its header can be
   read, and is dropped otherwise; an opcode other than QUERY is NOTIMP.
8. **Sizes.** Over UDP an answer fits 512 bytes, or the EDNS size the query gives, at most 4,096; records that do not
   fit are left out and the answer is marked truncated, so a resolver asks again over TCP, which answers in full.
9. **A codec of our own,** in `dev.mars.qraft.common.dns`: the messages are few and fixed, and a library for them
   would be a new dependency. Tests check it against the JDK's own DNS client (JNDI), which shares no code with it.
10. **Logging.** A lookup that fails is a caller's outcome, not a fault of the server's: it is answered with its code
    and logged at debug. A listener that cannot bind fails the start.

## 5. Rules

- Strict TDD: a failing test first for each behaviour, seen red, then the code.
- The three suites run locally from the copy at `C:\qx7`, and every log is audited, at the end of each phase that
  changes the runtime.
- Every unflagged error line or stack trace is remediated (AGENTS.md).
- New Java files carry the Apache header and the `@author`, `@since` and `@version` tags. The tree is CRLF.

## 6. Phases

### Phase 1. The codec

- [ ] Parsing a query: the header, one question, and an EDNS record's UDP size; FORMERR and NOTIMP cases.
- [ ] Writing an answer: A, AAAA, SRV and SOA records, with name compression, and trimming to a size with the
  truncation flag.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 2. Lookups and the listener

- [ ] The resolver: names, health, records, codes and shuffling, over a stub of the reads.
- [ ] The listener: UDP and TCP, checked with the JDK's DNS client.

**Exit:** the new tests seen red first; `mvn install`.

### Phase 3. Server mode and client mode

- [ ] `server.dns` and `client.dns` read and checked.
- [ ] A server answers from its own routes, under its DNS token; a client from the servers, under its default token.
- [ ] End to end: a client's DNS answers a service registered through another client, and stops answering an
  instance once its check is critical.

**Exit:** the new tests seen red first; the three suites locally, audited.

### Phase 4. Operations

- [ ] The image exposes 8600 over UDP and TCP; the Compose example serves DNS; the operations guide, the design, the
  validation, the Consul plan and the handover are brought up to date.

**Exit:** the three suites locally, audited, and the list complete and archived.

## 7. Out of scope

- Recursors, prepared queries (`.query.`), a `dns` token kind, TXT records of node metadata, and other datacenters.
