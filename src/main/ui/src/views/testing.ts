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

// Test support only: nothing outside a test imports it.

import type { Bootstrap } from '../bootstrap';
import { createSession, type Session } from '../session';
import { ApiError, type CheckState, type EventQuery, type QraftApi, type Read, type ReadOptions, type Scope,
  type WatchHandlers } from '../api/client';
import type { KvEntry } from '../api/schemas';
import {
  ACL_STATUS,
  ANONYMOUS_TOKEN,
  CHECK,
  CONFIGURATION,
  cursor,
  EVENT_PAGE,
  EXPLANATION,
  FILE_TOKEN,
  FOLLOWER_MEMBERS,
  GLOBAL_MANAGEMENT_POLICY,
  HEALTH_ENTRY,
  INSTANCE,
  MANAGEMENT_TOKEN,
  MEMBERS,
  NODE,
  POLICY,
  RAFT_STATUS,
  READ_ONLY_POLICY,
  SERVICE_EVENT,
  STORAGE,
  TOKEN,
} from '../api/fixtures';
import {
  AclStatusSchema,
  type CatalogNode,
  CatalogNodeSchema,
  type CheckEntry,
  ConfigurationSchema,
  type EventPage,
  ExplanationSchema,
  EventPageSchema,
  type QraftEvent,
  QraftEventSchema,
  type HealthEntry,
  HealthEntrySchema,
  type Members,
  MembersSchema,
  type Storage,
  StorageSchema,
  type RaftStatus,
  RaftStatusSchema,
  type Policy,
  PolicySchema,
  type ServiceSummary,
  type HeldKey,
  HeldKeySchema,
  type NamespaceHoldings,
  type Scopes,
  type SessionEntry,
  SessionEntrySchema,
  type Sessions,
  type Token,
  TokenSchema,
} from '../api/schemas';

export const BOOTSTRAP: Bootstrap = {
  apiBasePath: '/',
  uiPath: '/ui/',
  features: {},
  authMode: 'token',
  securityProfile: 'production',
  cspNonce: 'dGVzdC1ub25jZQ==',
};

export function read<T>(data: T, index: number | null = 12, meta: Partial<Omit<Read<T>, 'data' | 'index'>> = {}):
  Promise<Read<T>> {
  return Promise.resolve({
    data,
    index,
    requestId: 'req-1',
    answeredBy: 'server-1',
    consistency: 'default',
    knownLeader: true,
    lastContactMillis: 0,
    filtered: false,
    ...meta,
  });
}

/** A blocking query to a server where nothing changes: it waits until the view ends it. */
export function unchanged<T>(signal: AbortSignal | undefined): Promise<T> {
  return new Promise((_resolve, reject) => {
    signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  });
}

export const raftStatus = (changes: Partial<RaftStatus> = {}): RaftStatus =>
  RaftStatusSchema.parse({ ...RAFT_STATUS, ...changes });

export const node = (changes: Partial<CatalogNode> = {}): CatalogNode => CatalogNodeSchema.parse({ ...NODE, ...changes });

export function entry(
  service: Partial<HealthEntry['service']> = {},
  checks: Partial<HealthEntry['checks'][number]>[] = [{}],
  nodeStatus: HealthEntry['nodeStatus'] = 'healthy',
): HealthEntry {
  return HealthEntrySchema.parse({
    ...HEALTH_ENTRY,
    service: { ...INSTANCE, ...service },
    checks: checks.map((check) => ({ ...CHECK, ...check })),
    nodeStatus,
  });
}

/** The membership as the leader answers it, with any field replaced. */
export const members = (changes: Partial<Members> = {}): Members => MembersSchema.parse({ ...MEMBERS, ...changes });

/** The membership as a follower answers it. */
export const followerMembers = (): Members => MembersSchema.parse(FOLLOWER_MEMBERS);

/** The storage read, with any field replaced. */
export const storage = (changes: Partial<Storage> = {}): Storage => StorageSchema.parse({ ...STORAGE, ...changes });

/** The summary the server would give for one service's entries. */
export function summaryOf(name: string, list: HealthEntry[]): ServiceSummary {
  const health = { unknown: 0, passing: 0, warning: 0, critical: 0, maintenance: 0 };
  for (const item of list) {
    const key = item.service.health.toLowerCase() as keyof typeof health;
    health[key] += 1;
  }
  const distinct = (values: (string | null)[]) => [...new Set(values.filter((value): value is string => !!value))].sort();
  return {
    name,
    tenantId: list[0]?.service.tenantId ?? 'default',
    namespace: list[0]?.service.namespace ?? 'default',
    tags: distinct(list.flatMap((item) => item.service.tags)),
    instanceCount: list.length,
    health,
    datacenters: distinct(list.map((item) => item.service.datacenter)),
    regions: distinct(list.map((item) => item.service.region)),
    modifyIndex: Math.max(0, ...list.map((item) => item.service.modifyIndex)),
  };
}

/** The check listing the server would give for the entries, in a state. */
export function checksOf(entries: Record<string, HealthEntry[]>, state: CheckState): CheckEntry[] {
  return Object.values(entries)
    .flat()
    .flatMap((item) =>
      item.checks.map((check) => ({
        ...check,
        serviceId: item.service.serviceId,
        serviceName: item.service.serviceName,
        nodeId: item.service.nodeId,
        nodeStatus: item.nodeStatus,
        tenantId: item.service.tenantId,
        namespace: item.service.namespace,
      })),
    )
    .filter((check) => state === 'any' || check.status.toLowerCase() === state);
}

/** The scopes each scoped read was asked for, in order, by read. */
export interface ScopesAsked {
  services: Scope[];
  serviceHealth: Scope[];
  servicesSummary: Scope[];
  checks: Scope[];
}

/**
 * An API that answers from the given data, whatever the scope, and records the scope each read asked for in `asked`;
 * any read can be replaced. Two tenants exist: `default`, and `acme` with namespaces `default` and `prod`.
 */
export function fakeApi(
  overrides: Partial<QraftApi> = {},
  entries: Record<string, HealthEntry[]> = { web: [entry()] },
  asked: ScopesAsked = { services: [], serviceHealth: [], servicesSummary: [], checks: [] },
): QraftApi {
  const names = Object.fromEntries(
    Object.entries(entries).map(([name, list]) => [name, [...new Set(list.flatMap((item) => item.service.tags))]]),
  );
  const DEFAULT = { tenant: 'default', namespace: 'default' };
  const api: QraftApi = {
    raftStatus: () => read(raftStatus()),
    readiness: () => read({ ready: true, conditions: [] }),
    nodes: () => read([node()]),
    services: (scope = DEFAULT) => {
      asked.services.push(scope);
      return read(names);
    },
    serviceHealth: (name, scope = DEFAULT) => {
      asked.serviceHealth.push(scope);
      return read(entries[name] ?? []);
    },
    servicesSummary: (scope = DEFAULT) => {
      asked.servicesSummary.push(scope);
      return read(Object.entries(entries).map(([name, list]) => summaryOf(name, list)));
    },
    checks: (state, scope = DEFAULT) => {
      asked.checks.push(scope);
      return read(checksOf(entries, state));
    },
    tenants: () =>
      read([
        { Name: 'acme', Description: 'Acme Ltd' },
        { Name: 'default', Description: '' },
      ]),
    namespaces: (tenant) =>
      read(
        tenant === 'acme'
          ? [
              { Name: 'default', Tenant: 'acme', Description: '' },
              { Name: 'prod', Tenant: 'acme', Description: '' },
            ]
          : [{ Name: 'default', Tenant: tenant, Description: '' }],
      ),
    members: () => read(members()),
    storage: () => read(storage()),
    configuration: () => read(ConfigurationSchema.parse(CONFIGURATION), null),
    events: () => read(EventPageSchema.parse(EVENT_PAGE), null),
    watchEvents: () => () => undefined,
    self: () => read(TokenSchema.parse(TOKEN), null),
    authorize: (checks) => read(checks.map((check) => ({ ...check, allow: true })), null),
    aclStatus: () => read(AclStatusSchema.parse(ACL_STATUS), null),
    tokens: (filter) => read(TOKENS().filter((token) => filter?.policy === undefined
      || token.policies.some((policy) => policy.id === filter.policy)), null),
    token: (accessorId) => {
      const found = TOKENS().find((token) => token.accessorId === accessorId);
      return found === undefined ? Promise.reject(missing(`/v1/acl/token/${accessorId}`)) : read(found, null);
    },
    createToken: (body) => read(TokenSchema.parse({ ...TOKEN, accessorId: CREATED_ACCESSOR, secretId: CREATED_SECRET,
      description: body.description ?? '' }), 50),
    updateToken: (accessorId) => read(TokenSchema.parse({ ...TOKEN, accessorId }), 51),
    cloneToken: (_accessorId, description) => read(TokenSchema.parse({ ...TOKEN, accessorId: CLONE_ACCESSOR,
      secretId: CLONE_SECRET, description: description ?? TOKEN.description }), 52),
    deleteToken: () => read(true, 53),
    policies: () => read(POLICIES(), null),
    policy: (id) => {
      const found = POLICIES().find((policy) => policy.id === id);
      return found === undefined ? Promise.reject(missing(`/v1/acl/policy/${id}`)) : read(found, null);
    },
    createPolicy: (body) => read(PolicySchema.parse({ id: 'c0ffee00-0000-4000-8000-000000000001', ...body }), 54),
    updatePolicy: (id, body) => read(PolicySchema.parse({ id, ...body }), 55),
    deletePolicy: () => read(true, 56),
    explain: () => read(ExplanationSchema.parse(EXPLANATION), null),
    registerService: (nodeName, scope, body) => read({ node: nodeName, service: { serviceId: body.serviceId,
      serviceName: body.serviceName, nodeId: nodeName, tenantId: scope.tenant, namespace: scope.namespace, registered: true } }, 61),
    deregisterService: (nodeName, _scope, serviceId) => read({ node: nodeName, serviceId, deregistered: true }, 62),
    removeServer: (serverId) => read({ status: 'REMOVED', message: `${serverId} removed` }, 70),
    kvLevel: (prefix) => read(levelKeys(KV(), prefix), 30),
    kvEntry: (key) => read(KV().find((entry) => entry.Key === key) ?? null, 30),
    kvKeys: (prefix) => read(KV().map((entry) => entry.Key).filter((key) => key.startsWith(prefix)), 30),
    // A key/value write answers true or false, with no index, as the server does.
    kvPut: () => read(true, null),
    kvDelete: () => read(true, null),
    kvDeleteTree: () => read(true, null),
    sessions: () => read(SESSIONS(), 44),
    createSession: () => read({ ID: '7e570000-0000-4000-8000-000000000003' }, null),
    renewSession: () => read([], null),
    destroySession: () => read(true, null),
    scopes: () => read(SCOPES(), 40),
    createTenant: (name, description) => read({ Name: name, Description: description }, null),
    updateTenant: (name, description) => read({ Name: name, Description: description }, null),
    deleteTenant: () => read(true, null),
    createNamespace: (tenant, name, description) => read({ Name: name, Tenant: tenant, Description: description }, null),
    updateNamespace: (tenant, name, description) => read({ Name: name, Tenant: tenant, Description: description }, null),
    deleteNamespace: () => read(true, null),
    audit: () => read({ Configured: true, File: '/data/qraft-audit.json', Size: 0, Scanned: 0, Complete: true,
      Next: null, Records: [] }, null),
    kvAcquire: () => read(true, null),
    kvRelease: () => read(true, null),
    locks: (filter) => read({ Held: [heldKey()].filter((held) => filter?.session === undefined
      || held.Session === filter.session), Delays: [] }, 43),
    ...overrides,
  };
  return blockingWaits(api);
}

/** An entry of the key/value store, its value given as text. */
export function kvEntry(key: string, text: string, fields: Partial<KvEntry> = {}): KvEntry {
  return { Key: key, Value: btoa(String.fromCharCode(...new TextEncoder().encode(text))), Flags: 0, CreateIndex: 20,
    ModifyIndex: 24, LockIndex: 0, ...fields };
}

/** The fake API's key/value store: two keys and a folder of one in `app/`, and a key at the top. */
export const KV = (): KvEntry[] => [
  kvEntry('app/config', '{"replicas":3}', { Flags: 7 }),
  kvEntry('app/db/url', 'jdbc:postgresql://db/app'),
  kvEntry('app/name', 'shop'),
  kvEntry('readme', 'hello'),
];

/** What the server answers for one level below `prefix` with the `/` separator: keys, and folders ending in `/`. */
export function levelKeys(entries: readonly KvEntry[], prefix: string): string[] {
  const keys = entries.map((entry) => entry.Key).filter((key) => key.startsWith(prefix)).map((key) => {
    const slash = key.indexOf('/', prefix.length);
    return slash < 0 ? key : key.slice(0, slash + 1);
  });
  return [...new Set(keys)];
}

/**
 * Each catalog read of `api`, given an index, waits as a server where nothing changes would, whatever the test's own
 * read does, so a view's live updates send it once and then wait.
 */
function blockingWaits(api: QraftApi): QraftApi {
  const waiting = <A extends unknown[], R>(at: number, read: (...args: A) => Promise<R>) => (...args: A) => {
    const options = args[at] as ReadOptions | undefined;
    return options?.index === undefined ? read(...args) : unchanged<R>(options.signal);
  };
  return {
    ...api,
    nodes: waiting(0, api.nodes),
    services: waiting(1, api.services),
    serviceHealth: waiting(2, api.serviceHealth),
    servicesSummary: waiting(1, api.servicesSummary),
    checks: waiting(2, api.checks),
    kvLevel: waiting(2, api.kvLevel),
    kvEntry: waiting(2, api.kvEntry),
    sessions: waiting(0, api.sessions),
    scopes: waiting(0, api.scopes),
    locks: waiting(1, api.locks),
  };
}

/** The instant a test's clock starts at: a session of {@link sessionEntry} lapses 25 s later. */
export const TEST_NOW = Date.parse('2026-11-17T12:00:00Z');

/**
 * A scheduler the test fires by hand: `pending` holds what is due, `fire` runs it all; its clock starts at
 * {@link TEST_NOW} and moves only with `advance`.
 */
export function manualScheduler() {
  const pending: { ms: number; run: () => void }[] = [];
  let millis = TEST_NOW;
  return {
    pending,
    scheduler: {
      after(ms: number, run: () => void) {
        const entry = { ms, run };
        pending.push(entry);
        return () => {
          const at = pending.indexOf(entry);
          if (at >= 0) pending.splice(at, 1);
        };
      },
      now: () => millis,
    },
    fire() {
      pending.splice(0).forEach((entry) => entry.run());
    },
    advance(ms: number) {
      millis += ms;
    },
  };
}

/**
 * Hides the tab or shows it again, as a browser does when its user switches tabs or minimises the window: the document's
 * visibility changes and it says so. jsdom's document is always visible; `setupTests.ts` makes it so again after each
 * test.
 */
export function tabVisibility(state: 'hidden' | 'visible') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => state === 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
}

/** The test API's sessions: one with a TTL that holds a key, and one without. */
export const SESSION_A = '4f3c2a10-0000-4000-8000-000000000001';
export const SESSION_B = '4f3c2a10-0000-4000-8000-000000000002';

/** A session as the sessions read answers it, worker on node-a unless changed. */
export function sessionEntry(fields: Partial<SessionEntry> = {}): SessionEntry {
  return SessionEntrySchema.parse({ ID: SESSION_A, Name: 'worker', Node: 'node-a', LockDelay: 15_000_000_000,
    Behavior: 'release', TTL: '30s', CreateIndex: 40, ModifyIndex: 40, RenewedAt: null,
    ExpiresAt: '2026-11-17T12:00:25Z', ...fields });
}

/** The key session A holds in acme/prod, unless changed. */
export function heldKey(fields: Partial<HeldKey> = {}): HeldKey {
  return HeldKeySchema.parse({ Tenant: 'acme', Namespace: 'prod', Key: 'app/leader', Session: SESSION_A, LockIndex: 2,
    CreateIndex: 41, ModifyIndex: 43, ...fields });
}

/** A namespace's holdings, empty unless changed. */
export function holdings(name: string, fields: Partial<NamespaceHoldings> = {}): NamespaceHoldings {
  return { Name: name, Description: '', CreateIndex: 4, ModifyIndex: 4, Services: 0, Instances: 0, Keys: 0,
    HeldKeys: 0, Sessions: [], ...fields };
}

/** The test API's scopes: acme with an empty default namespace and a busy prod, and the default tenant. */
export const SCOPES = (): Scopes => ({ Tenants: [
  { Name: 'acme', Description: 'Acme Ltd', CreateIndex: 4, ModifyIndex: 4, Namespaces: [holdings('default'),
    holdings('prod', { Description: 'production', CreateIndex: 5, ModifyIndex: 6, Services: 2, Instances: 3, Keys: 3,
      HeldKeys: 1, Sessions: [SESSION_A] })] },
  { Name: 'default', Description: '', CreateIndex: 0, ModifyIndex: 0, Namespaces: [holdings('default',
    { Services: 1, Instances: 1, Keys: 4, CreateIndex: 0, ModifyIndex: 0 })] },
] });

/** The test API's sessions read. */
export const SESSIONS = (): Sessions => ({ Sessions: [sessionEntry(), sessionEntry({ ID: SESSION_B, Name: '',
  Node: 'node-b', Behavior: 'delete', TTL: '', LockDelay: 0, CreateIndex: 41, ModifyIndex: 41, ExpiresAt: null })],
TtlKnown: true });

/** An event of the journal at `sequence`, a service registration unless changed. */
export function qraftEvent(sequence: number, changes: Partial<QraftEvent> = {}): QraftEvent {
  return QraftEventSchema.parse({ ...SERVICE_EVENT, cursor: cursor(sequence), committedIndex: sequence, ...changes });
}

/** A page of the journal: its events, the cursor of the last one, complete unless said. */
export function eventPage(events: QraftEvent[], status: EventPage['status'] = 'complete', after = 0): EventPage {
  const last = events.at(-1);
  return { events, next: last === undefined ? cursor(after) : last.cursor, status, capacity: 4096 };
}

/** One watch a view opened: what it asked for, what it is told through, and whether the view closed it. */
export interface Watch {
  query: EventQuery;
  handlers: WatchHandlers;
  closed: boolean;
}

/** A `watchEvents` that records each watch the views open, for the test to drive. */
export function watchRecorder() {
  const watches: Watch[] = [];
  const watchEvents = (query: EventQuery, handlers: WatchHandlers) => {
    const watch: Watch = { query, handlers, closed: false };
    watches.push(watch);
    return () => {
      watch.closed = true;
    };
  };
  return { watches, watchEvents };
}

/** A session held in memory, signed in with `secret` when one is given. */
export function memorySession(secret?: string): Session {
  const items = new Map<string, string>();
  const session = createSession({
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => void items.set(key, value),
    removeItem: (key) => void items.delete(key),
  });
  if (secret !== undefined) session.signIn(secret);
  return session;
}

/** `api`, recording the name of each of its calls in order. */
export function recordingApi(api: QraftApi): { api: QraftApi; calls: string[] } {
  const calls: string[] = [];
  const recorded = new Proxy(api, {
    get(target, property, receiver) {
      const value: unknown = Reflect.get(target, property, receiver);
      if (typeof value !== 'function') return value;
      return (...args: unknown[]) => {
        calls.push(String(property));
        return (value as (...given: unknown[]) => unknown).apply(target, args);
      };
    },
  });
  return { api: recorded, calls };
}

/** The secret a test's created token is answered with; made up, never a real secret. */
export const CREATED_SECRET = 'made-up-secret-of-a-created-token';
export const CREATED_ACCESSOR = 'a11ce000-0000-4000-8000-000000000001';
export const CLONE_SECRET = 'made-up-secret-of-a-clone';
export const CLONE_ACCESSOR = 'c1011e00-0000-4000-8000-000000000001';

/** The tokens the test API holds: a scoped token, the bootstrap and file management tokens, and the anonymous token. */
export const TOKENS = (): Token[] => [TOKEN, MANAGEMENT_TOKEN, FILE_TOKEN, ANONYMOUS_TOKEN].map((token) => TokenSchema.parse(token));

/** The policies the test API holds: one of the operator's, and the two built-ins. */
export const POLICIES = (): Policy[] => [POLICY, GLOBAL_MANAGEMENT_POLICY, READ_ONLY_POLICY].map((policy) => PolicySchema.parse(policy));

function missing(path: string): ApiError {
  return new ApiError(path, 404, 'acl_not_found', `ACL not found: ${path}`, false, 'req-404', null);
}
