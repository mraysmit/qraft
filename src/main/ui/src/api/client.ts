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

import { z, type ZodType } from 'zod';
import {
  type AclStatus,
  AclStatusSchema,
  type Authorization,
  AuthorizationSchema,
  type CatalogNode,
  CatalogNodeSchema,
  type Deregistration,
  DeregistrationSchema,
  type CheckEntry,
  CheckEntrySchema,
  type Configuration,
  ConfigurationSchema,
  ErrorEnvelopeSchema,
  type EventCategory,
  type Explanation,
  ExplanationSchema,
  type EventPage,
  EventPageSchema,
  type HealthEntry,
  type KvEntry,
  KvEntrySchema,
  type ConsulSession,
  ConsulSessionSchema,
  type Locks,
  LocksSchema,
  type Scopes,
  ScopesSchema,
  type Audit,
  AuditSchema,
  type Sessions,
  SessionsSchema,
  type Members,
  MembersSchema,
  type QraftEvent,
  QraftEventSchema,
  type Storage,
  StorageSchema,
  HealthEntrySchema,
  type Namespace,
  NamespaceSchema,
  type Policy,
  PolicySchema,
  type RaftStatus,
  RaftStatusSchema,
  type Registration,
  RegistrationSchema,
  type Removal,
  RemovalSchema,
  ReadySchema,
  type ServiceNames,
  ServiceNamesSchema,
  type ServiceSummary,
  ServiceSummarySchema,
  type Tenant,
  TenantSchema,
  type Token,
  TokenSchema,
  WatchEndSchema,
} from './schemas';
import type { LocksFilter } from './sessions';

/** The tenant and namespace a catalog or health read is confined to (UI/UX design section 3.2). */
export interface Scope {
  tenant: string;
  namespace: string;
}

/** The scope a server reads when a request names none. */
export const DEFAULT_SCOPE: Scope = { tenant: 'default', namespace: 'default' };

/** The states the check listing takes: every check, or those in one health state. */
export type CheckState = 'any' | 'unknown' | 'passing' | 'warning' | 'critical' | 'maintenance';

/**
 * A read's consistency (UI/UX design section 4.1): `default` and `stale` are the answering server's applied state,
 * `consistent` is answered by the leader once it has confirmed it leads.
 */
export type Consistency = 'default' | 'consistent' | 'stale';

/** What ends a read early: the view closing, or live updates pausing. */
export interface Abortable {
  signal?: AbortSignal;
}

/** How a catalog, health or tenancy read is made: its consistency, and for a blocking query its index and wait. */
export interface ReadOptions extends Abortable {
  consistency?: Consistency;
  /** A blocking query answers once its index passes this one, or its wait passes. */
  index?: number;
  /** The longest a blocking query waits, such as `30s`. */
  wait?: string;
}

/** An answer with the read metadata the server sent with it (UI/UX design section 12). */
export interface Read<T> {
  data: T;
  /** The applied index the answer reflects, `X-Qraft-Index`, or null when the server sent none. */
  index: number | null;
  requestId: string | null;
  /** The server that answered, `X-Qraft-Answered-By`. */
  answeredBy: string | null;
  /** The consistency the server applied, `X-Qraft-Consistency`; null for a read that takes none. */
  consistency: Consistency | null;
  /** Whether the answering server knew a leader, `X-Qraft-Known-Leader`. */
  knownLeader: boolean | null;
  /** How long since the answering server heard from the leader, `X-Qraft-Last-Contact`. */
  lastContactMillis: number | null;
  /** Whether the server left out what the token may not read, `X-Qraft-Results-Filtered-By-ACLs`. */
  filtered: boolean;
}

export interface Readiness {
  ready: boolean;
  /** The readiness conditions the server does not meet, empty when it is ready. */
  conditions: string[];
}

/** Which events to read: after a cursor, at most a limit, in a category, a scope, of a service and of a key. */
export interface EventQuery {
  after?: string;
  limit?: number;
  category?: EventCategory;
  tenant?: string;
  namespace?: string;
  service?: string;
  /** The key's events, and the tree deletions that covered it (UI-6 Key/Value task list, decision 8). */
  key?: string;
  /** The session's own events and the locks it took and gave up (UI-6 Sessions list, decision 6). */
  session?: string;
  /** The events of one type, such as `session.destroyed`. */
  type?: string;
}

/** Why a watch ended, and the cursor to resume after. */
export type WatchEnd = z.infer<typeof WatchEndSchema>;

/** What a watch tells: each event, its end, or a failure. After any end or failure it says nothing more. */
export interface WatchHandlers {
  onEvent(event: QraftEvent): void;
  onEnd(end: WatchEnd): void;
  onError(error: unknown): void;
}

/**
 * The token every request presents as `X-Qraft-Token` (UI/UX design section 10.2.2), and what is done when the server
 * no longer knows it: the session ends. A request that names its own secret, as sign-in does, does not end it.
 */
export interface Credentials {
  /** The session's secret, or null when no one is signed in. */
  secret(): string | null;
  /** The server answered 403 `token_not_found` to a request that presented the session's secret. */
  onTokenNotFound(error: ApiError): void;
}

/** One check of `POST /v1/internal/acl/authorize`: may the token in use make this request? */
export interface AuthorizationCheck {
  resource: string;
  segment?: string;
  access: 'read' | 'write';
  tenant?: string;
  namespace?: string;
}

/** A token as a create or update gives it: links name a policy by ID or by name. */
export interface TokenBody {
  description?: string;
  policies?: { id?: string; name?: string }[];
  nodeIdentities?: { nodeName: string }[];
  serviceIdentities?: { serviceName: string; tenant: string; namespace: string }[];
  /** How long the token lives, such as `30m` or `1h`; a create only. */
  expirationTtl?: string;
}

/** An instance as the interface registers it: on a node the catalog has, in a scope; its checks are kept as they are. */
export interface ServiceBody {
  serviceId: string;
  serviceName: string;
  address: string;
  port: number;
  tags: string[];
  metadata: Record<string, string>;
  enabled: boolean;
}

/** A policy as a create or update gives it. */
export interface PolicyBody {
  name: string;
  description?: string;
  rules: Record<string, unknown>;
}

/** What to explain: may this token make this request? */
export interface ExplainQuery {
  accessorId: string;
  resource: string;
  name?: string;
  access: 'read' | 'write';
  tenant?: string;
  namespace?: string;
}

/** A request the server refused or could not answer, or a server that could not be reached (status 0). */
export class ApiError extends Error {
  constructor(
    readonly path: string,
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryable: boolean,
    readonly requestId: string | null,
    readonly leaderId: string | null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** An answer that is not the shape the interface reads: shown as a failure, never as partial data. */
export class MalformedResponseError extends Error {
  constructor(
    readonly path: string,
    readonly requestId: string | null,
    detail: string,
  ) {
    super(`The server's answer to ${path} is not in the expected form: ${detail}`);
    this.name = 'MalformedResponseError';
  }
}

/** Whether `error` is a read ended by its signal, which is not a failure to show. */
export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * The reads the views make, all through the public HTTP API. Each catalog and health read names its scope, the
 * default when none is given; a node, the Raft status and readiness have none. The operator reads answer the
 * answering server's own state and take no consistency.
 */
export interface QraftApi {
  raftStatus(options?: Abortable): Promise<Read<RaftStatus>>;
  readiness(options?: Abortable): Promise<Read<Readiness>>;
  nodes(options?: ReadOptions): Promise<Read<CatalogNode[]>>;
  services(scope?: Scope, options?: ReadOptions): Promise<Read<ServiceNames>>;
  serviceHealth(name: string, scope?: Scope, options?: ReadOptions): Promise<Read<HealthEntry[]>>;
  servicesSummary(scope?: Scope, options?: ReadOptions): Promise<Read<ServiceSummary[]>>;
  checks(state: CheckState, scope?: Scope, options?: ReadOptions): Promise<Read<CheckEntry[]>>;
  tenants(options?: ReadOptions): Promise<Read<Tenant[]>>;
  namespaces(tenant: string, options?: ReadOptions): Promise<Read<Namespace[]>>;
  /** The membership as the answering server sees it: progress and quorum on the leader only. */
  members(options?: Abortable): Promise<Read<Members>>;
  /** The answering server's storage and snapshot state. */
  storage(options?: Abortable): Promise<Read<Storage>>;
  /** The answering server's effective configuration, its secrets withheld. */
  configuration(options?: Abortable): Promise<Read<Configuration>>;
  /** A page of the answering server's event journal. */
  events(query: EventQuery, options?: Abortable): Promise<Read<EventPage>>;
  /** Follows the answering server's events after the query's cursor; the returned function ends the watch. */
  watchEvents(query: EventQuery, handlers: WatchHandlers): () => void;
  /** The token in use, `GET /v1/acl/token/self`; or the token of `secret`, a secret being signed in with. */
  self(options?: Abortable & { secret?: string }): Promise<Read<Token>>;
  /** What the token in use may do, each check answered in order. */
  authorize(checks: AuthorizationCheck[], options?: Abortable): Promise<Read<Authorization[]>>;
  /** The security status: whether the cluster's access control can be trusted. */
  aclStatus(options?: Abortable): Promise<Read<AclStatus>>;
  /** The tokens, or those linked to a policy; every secret hidden. */
  tokens(filter?: { policy?: string }, options?: Abortable): Promise<Read<Token[]>>;
  token(accessorId: string, options?: Abortable): Promise<Read<Token>>;
  /** Creates a token; the answer holds its secret, once. Not idempotent: never sent again unasked. */
  createToken(body: TokenBody): Promise<Read<Token>>;
  updateToken(accessorId: string, body: TokenBody): Promise<Read<Token>>;
  /** A new token with the same links and expiry, and a new secret, answered once. */
  cloneToken(accessorId: string, description?: string): Promise<Read<Token>>;
  /** Deletes, which is revocation. */
  deleteToken(accessorId: string): Promise<Read<boolean>>;
  policies(options?: Abortable): Promise<Read<Policy[]>>;
  policy(id: string, options?: Abortable): Promise<Read<Policy>>;
  createPolicy(body: PolicyBody): Promise<Read<Policy>>;
  updatePolicy(id: string, body: PolicyBody): Promise<Read<Policy>>;
  deletePolicy(id: string): Promise<Read<boolean>>;
  /** The server's explanation of whether a token may make a request, and why. */
  explain(query: ExplainQuery, options?: Abortable): Promise<Read<Explanation>>;
  /** Registers, or registers again, an instance on `node` in `scope`. Idempotent for the same instance ID. */
  registerService(node: string, scope: Scope, service: ServiceBody): Promise<Read<Registration>>;
  /** Deregisters an instance from `node` in `scope`. Idempotent. */
  deregisterService(node: string, scope: Scope, serviceId: string): Promise<Read<Deregistration>>;
  /**
   * Removes a server by its ID. Without access control it needs the operator token, given for this request only;
   * with it, the session's token with `operator:write`.
   */
  removeServer(serverId: string, operatorToken?: string): Promise<Read<Removal>>;
  /**
   * The keys of one level below `prefix`, a folder ending in `/` (UI-6 Key/Value task list, decision 3); an empty
   * folder, which the server answers 404, has none.
   */
  kvLevel(prefix: string, scope?: Scope, options?: ReadOptions): Promise<Read<string[]>>;
  /** An entry, or null when the key does not exist. */
  kvEntry(key: string, scope?: Scope, options?: ReadOptions): Promise<Read<KvEntry | null>>;
  /** Every key under `prefix`, at any depth; a blocking query when given an index, as a watch of the prefix is. */
  kvKeys(prefix: string, scope?: Scope, options?: ReadOptions): Promise<Read<string[]>>;
  /**
   * Writes `value` as the key's raw body with its flags, if the key is still at `cas`, 0 for one that does not exist
   * (UI-6 Key/Value task list, decision 5): true when written, false when the entry had moved on.
   */
  kvPut(key: string, scope: Scope | undefined, value: string, at: { flags: number; cas: number }): Promise<Read<boolean>>;
  /** Deletes the key if it is still at `cas`: true when deleted, false when the entry had moved on. */
  kvDelete(key: string, scope: Scope | undefined, cas: number): Promise<Read<boolean>>;
  /** Deletes every key under `prefix`; the server refuses it unless the token may write every one. */
  kvDeleteTree(prefix: string, scope?: Scope): Promise<Read<boolean>>;
  /**
   * Every session the token may read, which belong to no scope, with the leader's timers (UI-6 Sessions, Locks and
   * Watches task list, decision 2); a server that does not lead answers them unknown.
   */
  sessions(options?: ReadOptions): Promise<Read<Sessions>>;
  /** The keys sessions hold across scopes, or one session's, and the lock delays still running (decision 4). */
  locks(filter?: LocksFilter, options?: ReadOptions): Promise<Read<Locks>>;
  /** Creates a session bound to a node; the answer names it. Not idempotent: never sent again unasked (decision 7). */
  createSession(body: SessionBody): Promise<Read<{ ID: string }>>;
  /** Renews a session on the leader, which a follower forwards; 404 `session_not_found` once it has ended. */
  renewSession(id: string): Promise<Read<ConsulSession[]>>;
  /** Destroys a session, releasing or deleting its keys; idempotent, as one that has ended is destroyed already. */
  destroySession(id: string): Promise<Read<boolean>>;
  /**
   * Acquires the key for `session`, writing `value` with its flags (decision 9): true when the session holds it, false
   * when another does or its lock delay runs. The server takes no compare-and-set with it, as Consul's does not.
   */
  kvAcquire(key: string, scope: Scope | undefined, value: string, at: { flags: number; session: string }): Promise<Read<boolean>>;
  /** Releases the key `session` holds, writing `value`: true when released, false when the session did not hold it. */
  kvRelease(key: string, scope: Scope | undefined, value: string, at: { flags: number; session: string }): Promise<Read<boolean>>;
  /** Every tenant with what each of its namespaces holds (UI-6 Tenants, Namespaces and Audit task list, decision 2). */
  scopes(options?: ReadOptions): Promise<Read<Scopes>>;
  /** Creates a tenant with its default namespace; 409 `tenant_exists` for one that exists. Not sent again unasked. */
  createTenant(name: string, description: string): Promise<Read<Tenant>>;
  updateTenant(name: string, description: string): Promise<Read<Tenant>>;
  /** Deletes a tenant with all its namespaces hold; 404 for one that is gone. */
  deleteTenant(name: string): Promise<Read<boolean>>;
  /** Creates a namespace in a tenant; 409 `namespace_exists` for one that exists. Not sent again unasked. */
  createNamespace(tenant: string, name: string, description: string): Promise<Read<Namespace>>;
  updateNamespace(tenant: string, name: string, description: string): Promise<Read<Namespace>>;
  /** Deletes a namespace with what it holds; 404 for one that is gone. */
  deleteNamespace(tenant: string, name: string): Promise<Read<boolean>>;
  /** The answering server's audit records, newest first, as `query` filters them (decision 5). */
  audit(query: AuditQuery, options?: Abortable): Promise<Read<Audit>>;
}

/** What the audit read takes besides its limit (UI-6 Tenants, Namespaces and Audit task list, decision 5). */
export interface AuditQuery {
  accessor?: string;
  requestId?: string;
  method?: string;
  route?: string;
  tenant?: string;
  namespace?: string;
  result?: 'allowed' | 'denied' | 'failed';
  /** An instant: only the records written before it. */
  before?: string;
  /** The byte offset a read goes on from back towards the file's start: the last answer's `Next`, the next page back. */
  from?: string;
}

/** The audit read's filters in the order they are sent and written into an address. */
export const AUDIT_FILTERS = ['accessor', 'requestId', 'method', 'route', 'tenant', 'namespace', 'result', 'before',
  'from'] as const;

/** How many records one audit read answers. */
export const AUDIT_PAGE = 100;

/** A session as a create gives it, in Consul's fields: durations as Go writes them, such as `30s`; a TTL empty for none. */
export interface SessionBody {
  Node: string;
  Name: string;
  TTL: string;
  LockDelay: string;
  Behavior: 'release' | 'delete';
}

/**
 * A client for the API below `apiBasePath`, the bootstrap object's base path, which ends with `/`. Every request
 * presents the token `credentials` hold, if any.
 */
export function createApi(apiBasePath: string, fetcher: typeof fetch = fetch, credentials?: Credentials): QraftApi {
  const base = apiBasePath.endsWith('/') ? apiBasePath : `${apiBasePath}/`;

  /** `headers` with the session's token, unless they name a secret of their own. */
  function presenting(headers: Record<string, string>): Record<string, string> {
    if (TOKEN_HEADER in headers) return headers;
    const secret = credentials?.secret() ?? null;
    return secret === null ? headers : { ...headers, [TOKEN_HEADER]: secret };
  }

  async function send(path: string, headers: Record<string, string> = {}, signal?: AbortSignal,
    accept = 'application/json', method = 'GET', payload?: unknown): Promise<Response> {
    try {
      const init: RequestInit = { method, headers: { Accept: accept, ...presenting(headers) } };
      if (payload instanceof RawBody) {
        init.headers = { ...init.headers, 'Content-Type': 'application/octet-stream' };
        init.body = payload.text;
      } else if (payload !== undefined) {
        init.headers = { ...init.headers, 'Content-Type': 'application/json' };
        init.body = JSON.stringify(payload);
      }
      if (signal !== undefined) init.signal = signal;
      return await fetcher(base + path, init);
    } catch (failure) {
      if (isAbort(failure)) throw failure;
      const reason = failure instanceof Error ? failure.message : String(failure);
      throw new ApiError(`/${pathOf(path)}`, 0, 'unreachable', `The server could not be reached: ${reason}`, true,
        null, null);
    }
  }

  async function body(path: string, response: Response): Promise<unknown> {
    const text = await response.text();
    try {
      return JSON.parse(text) as unknown;
    } catch {
      if (response.ok) throw new MalformedResponseError(`/${pathOf(path)}`, requestIdOf(response), 'the body is not JSON');
      return undefined;
    }
  }

  /**
   * The error for a refused request. A 403 `token_not_found` to a request that presented the session's secret ends
   * the session; one to a request that named its own secret, `ownSecret`, does not.
   */
  function refusal(path: string, response: Response, content: unknown, ownSecret = false): ApiError {
    const error = refusalOf(path, response, content);
    if (!ownSecret && error.status === 403 && error.code === 'token_not_found' && credentials?.secret() != null) {
      credentials.onTokenNotFound(error);
    }
    return error;
  }

  function refusalOf(path: string, response: Response, content: unknown): ApiError {
    const envelope = ErrorEnvelopeSchema.safeParse(content);
    const requestId = requestIdOf(response);
    const leaderId = response.headers.get('X-Qraft-Leader-Id');
    if (!envelope.success) {
      return new ApiError(`/${pathOf(path)}`, response.status, `http_${response.status}`,
        `The server answered ${response.status} ${response.statusText}`.trim(), false, requestId, leaderId);
    }
    const { code, message, retryable } = envelope.data;
    return new ApiError(`/${pathOf(path)}`, response.status, code, message, retryable,
      envelope.data.requestId ?? requestId, leaderId);
  }

  function parse<T>(path: string, response: Response, schema: ZodType<T>, content: unknown): T {
    const parsed = schema.safeParse(content);
    if (!parsed.success) {
      throw new MalformedResponseError(`/${pathOf(path)}`, requestIdOf(response), z.prettifyError(parsed.error));
    }
    return parsed.data;
  }

  async function read<T>(path: string, schema: ZodType<T>, headers: Record<string, string> = {},
    signal?: AbortSignal, method = 'GET', payload?: unknown): Promise<Read<T>> {
    const response = await send(path, headers, signal, 'application/json', method, payload);
    const content = await body(path, response);
    if (!response.ok) throw refusal(path, response, content, TOKEN_HEADER in headers);
    return { data: parse(path, response, schema, content), ...metaOf(response) };
  }

  /**
   * A request that changes state. Sent and never answered, its outcome is unknown, not a failure: it may have been
   * applied (UI/UX design section 11), and a request that is not idempotent must not be sent again blindly.
   */
  async function write<T>(path: string, schema: ZodType<T>, method: string, payload?: unknown,
    headers: Record<string, string> = {}): Promise<Read<T>> {
    try {
      return await read(path, schema, headers, undefined, method, payload);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 0) {
        throw new ApiError(failure.path, 0, 'outcome_unknown',
          `No answer came after the request was sent: ${failure.message}`, false, null, null);
      }
      throw failure;
    }
  }

  /** A catalog, health or tenancy read, with its consistency and blocking-query options in its query. */
  function optioned<T>(path: string, schema: ZodType<T>, options: ReadOptions = {},
    headers: Record<string, string> = {}): Promise<Read<T>> {
    const query = optionsQuery(options);
    const joined = query.length === 0 ? path : `${path}${path.includes('?') ? '&' : '?'}${query.join('&')}`;
    return read(joined, schema, headers, options.signal);
  }

  /**
   * A key/value read: `missing` stands for the 404 `key_not_found` of a key or prefix that does not exist, which is
   * an answer, with its index, and not a failure.
   */
  async function kv<T>(key: string, given: string[], schema: ZodType<T>, missing: T, scope: Scope,
    options: ReadOptions): Promise<Read<T>> {
    const query = [...given, ...optionsQuery(options)];
    const path = `v1/kv/${kvPath(key)}${query.length === 0 ? '' : `?${query.join('&')}`}`;
    const response = await send(path, scopeHeaders(scope), options.signal);
    const content = await body(path, response);
    if (response.status === 404 && ErrorEnvelopeSchema.safeParse(content).data?.code === 'key_not_found') {
      return { data: missing, ...metaOf(response) };
    }
    if (!response.ok) throw refusal(path, response, content);
    return { data: parse(path, response, schema, content), ...metaOf(response) };
  }

  function watchEvents(query: EventQuery, handlers: WatchHandlers): () => void {
    const path = `v1/operator/events/watch${eventQueryText(query)}`;
    const control = new AbortController();
    let done = false;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    const finish = (tell: () => void) => {
      if (done) return;
      done = true;
      tell();
    };

    const follow = async () => {
      const response = await send(path, {}, control.signal, 'text/event-stream');
      if (!response.ok) throw refusal(path, response, await body(path, response));
      if (response.body === null) throw closed(path, response);
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done: ended } = await reader.read();
        if (ended) throw closed(path, response);
        buffer += decoder.decode(value, { stream: true }).replaceAll('\r\n', '\n');
        let boundary = buffer.indexOf('\n\n');
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary);
          buffer = buffer.slice(boundary + 2);
          boundary = buffer.indexOf('\n\n');
          const { type, data } = frameOf(frame);
          if (data === null) continue;
          let content: unknown;
          try {
            content = JSON.parse(data) as unknown;
          } catch {
            throw new MalformedResponseError(`/${pathOf(path)}`, requestIdOf(response), 'an event is not JSON');
          }
          if (type === 'end') {
            const end = parse(path, response, WatchEndSchema, content);
            finish(() => handlers.onEnd(end));
            return;
          }
          const event = parse(path, response, QraftEventSchema, content);
          if (done) return;
          handlers.onEvent(event);
        }
      }
    };

    follow().catch((error: unknown) => {
      if (isAbort(error) || control.signal.aborted) return;
      finish(() => handlers.onError(error));
    }).finally(() => {
      reader?.cancel().catch(() => undefined);
    });

    return () => {
      done = true;
      control.abort();
      reader?.cancel().catch(() => undefined);
    };
  }

  return {
    raftStatus: (options = {}) => read('raft/status', RaftStatusSchema, {}, options.signal),
    nodes: (options) => optioned('v1/catalog/nodes', z.array(CatalogNodeSchema), options),
    services: (scope = DEFAULT_SCOPE, options) =>
      optioned('v1/catalog/services', ServiceNamesSchema, options, scopeHeaders(scope)),
    serviceHealth: (name, scope = DEFAULT_SCOPE, options) =>
      optioned(`v1/health/service/${encodeURIComponent(name)}`, z.array(HealthEntrySchema), options, scopeHeaders(scope)),
    servicesSummary: (scope = DEFAULT_SCOPE, options) =>
      optioned('v1/internal/ui/services', z.array(ServiceSummarySchema), options, scopeHeaders(scope)),
    checks: (state, scope = DEFAULT_SCOPE, options) =>
      optioned(`v1/health/state/${state}`, z.array(CheckEntrySchema), options, scopeHeaders(scope)),
    tenants: (options) => optioned('v1/tenants', z.array(TenantSchema), options),
    namespaces: (tenant, options) =>
      optioned('v1/namespaces', z.array(NamespaceSchema), options, { 'X-Qraft-Tenant': tenant }),
    members: (options = {}) => read('v1/operator/raft/members', MembersSchema, {}, options.signal),
    storage: (options = {}) => read('v1/operator/raft/storage', StorageSchema, {}, options.signal),
    configuration: (options = {}) => read('v1/operator/configuration', ConfigurationSchema, {}, options.signal),
    events: (query, options = {}) =>
      read(`v1/operator/events${eventQueryText(query)}`, EventPageSchema, {}, options.signal),
    watchEvents,
    self: (options = {}) => read('v1/acl/token/self', TokenSchema,
      options.secret === undefined ? {} : { [TOKEN_HEADER]: options.secret }, options.signal),
    authorize: (checks, options = {}) =>
      read('v1/internal/acl/authorize', z.array(AuthorizationSchema), {}, options.signal, 'POST', checks),
    aclStatus: (options = {}) => read('v1/internal/ui/acl/status', AclStatusSchema, {}, options.signal),
    tokens: (filter = {}, options = {}) => read(filter.policy === undefined ? 'v1/acl/tokens'
      : `v1/acl/tokens?policy=${encodeURIComponent(filter.policy)}`, z.array(TokenSchema), {}, options.signal),
    token: (accessorId, options = {}) => read(`v1/acl/token/${segment(accessorId)}`, TokenSchema, {}, options.signal),
    createToken: (body) => write('v1/acl/token', TokenSchema, 'PUT', body),
    updateToken: (accessorId, body) => write(`v1/acl/token/${segment(accessorId)}`, TokenSchema, 'PUT', body),
    cloneToken: (accessorId, description) => write(`v1/acl/token/${segment(accessorId)}/clone`, TokenSchema, 'PUT',
      description === undefined ? {} : { description }),
    deleteToken: (accessorId) => write(`v1/acl/token/${segment(accessorId)}`, z.boolean(), 'DELETE'),
    policies: (options = {}) => read('v1/acl/policies', z.array(PolicySchema), {}, options.signal),
    policy: (id, options = {}) => read(`v1/acl/policy/${segment(id)}`, PolicySchema, {}, options.signal),
    createPolicy: (body) => write('v1/acl/policy', PolicySchema, 'PUT', body),
    updatePolicy: (id, body) => write(`v1/acl/policy/${segment(id)}`, PolicySchema, 'PUT', body),
    deletePolicy: (id) => write(`v1/acl/policy/${segment(id)}`, z.boolean(), 'DELETE'),
    explain: (query, options = {}) => read('v1/acl/explain', ExplanationSchema, {}, options.signal, 'POST', query),
    registerService: (node, scope, service) => write('v1/catalog/register', RegistrationSchema, 'PUT', { service },
      { 'X-Qraft-Node': node, ...scopeHeaders(scope) }),
    deregisterService: (node, scope, serviceId) => write('v1/catalog/deregister', DeregistrationSchema, 'PUT', { serviceId },
      { 'X-Qraft-Node': node, ...scopeHeaders(scope) }),
    removeServer: (serverId, operatorToken) => write(`v1/operator/raft/peer?id=${encodeURIComponent(serverId)}`,
      RemovalSchema, 'DELETE', undefined, operatorToken === undefined ? {} : { [TOKEN_HEADER]: operatorToken }),
    kvLevel: (prefix, scope = DEFAULT_SCOPE, options = {}) =>
      kv(prefix, ['keys', 'separator=/'], z.array(z.string()), [], scope, options),
    kvEntry: async (key, scope = DEFAULT_SCOPE, options = {}) => {
      const answer = await kv<KvEntry[] | null>(key, [], z.array(KvEntrySchema), null, scope, options);
      return { ...answer, data: answer.data?.[0] ?? null };
    },
    kvKeys: (prefix, scope = DEFAULT_SCOPE, options = {}) => kv(prefix, ['keys'], z.array(z.string()), [], scope, options),
    kvPut: (key, scope = DEFAULT_SCOPE, value, at) => write(`v1/kv/${kvPath(key)}?flags=${at.flags}&cas=${at.cas}`,
      z.boolean(), 'PUT', new RawBody(value), scopeHeaders(scope)),
    kvDelete: (key, scope = DEFAULT_SCOPE, cas) => write(`v1/kv/${kvPath(key)}?cas=${cas}`, z.boolean(), 'DELETE',
      undefined, scopeHeaders(scope)),
    kvDeleteTree: (prefix, scope = DEFAULT_SCOPE) => write(`v1/kv/${kvPath(prefix)}?recurse`, z.boolean(), 'DELETE',
      undefined, scopeHeaders(scope)),
    sessions: (options) => optioned('v1/internal/ui/sessions', SessionsSchema, options),
    locks: (filter = {}, options) => optioned(filter.session === undefined ? 'v1/internal/ui/locks'
      : `v1/internal/ui/locks?session=${encodeURIComponent(filter.session)}`, LocksSchema, options),
    createSession: (body) => write('v1/session/create', z.object({ ID: z.string().min(1) }), 'PUT', body),
    renewSession: (id) => write(`v1/session/renew/${segment(id)}`, z.array(ConsulSessionSchema), 'PUT'),
    destroySession: (id) => write(`v1/session/destroy/${segment(id)}`, z.boolean(), 'PUT'),
    kvAcquire: (key, scope = DEFAULT_SCOPE, value, at) => write(`v1/kv/${kvPath(key)}?flags=${at.flags}&acquire=${
      encodeURIComponent(at.session)}`, z.boolean(), 'PUT', new RawBody(value), scopeHeaders(scope)),
    kvRelease: (key, scope = DEFAULT_SCOPE, value, at) => write(`v1/kv/${kvPath(key)}?flags=${at.flags}&release=${
      encodeURIComponent(at.session)}`, z.boolean(), 'PUT', new RawBody(value), scopeHeaders(scope)),
    scopes: (options) => optioned('v1/internal/ui/scopes', ScopesSchema, options),
    createTenant: (name, description) => write('v1/tenant', TenantSchema, 'PUT', { Name: name, Description: description }),
    updateTenant: (name, description) => write(`v1/tenant/${segment(name)}`, TenantSchema, 'PUT',
      { Description: description }),
    deleteTenant: (name) => write(`v1/tenant/${segment(name)}`, z.boolean(), 'DELETE'),
    createNamespace: (tenant, name, description) => write('v1/namespace', NamespaceSchema, 'PUT',
      { Name: name, Tenant: tenant, Description: description }),
    updateNamespace: (tenant, name, description) => write(`v1/namespace/${segment(name)}`, NamespaceSchema, 'PUT',
      { Description: description }, { 'X-Qraft-Tenant': tenant }),
    deleteNamespace: (tenant, name) => write(`v1/namespace/${segment(name)}`, z.boolean(), 'DELETE', undefined,
      { 'X-Qraft-Tenant': tenant }),
    audit: (query, options = {}) => {
      const parameters = new URLSearchParams({ limit: String(AUDIT_PAGE) });
      for (const name of AUDIT_FILTERS) {
        const value = query[name];
        if (value !== undefined && value !== '') parameters.set(name, value);
      }
      return read(`v1/internal/ui/audit?${parameters.toString()}`, AuditSchema, {}, options.signal);
    },
    async readiness(options = {}) {
      const path = 'health/ready';
      const response = await send(path, {}, options.signal);
      const content = await body(path, response);
      const meta = metaOf(response);
      if (response.ok) {
        parse(path, response, ReadySchema, content);
        return { data: { ready: true, conditions: [] }, ...meta };
      }
      const envelope = ErrorEnvelopeSchema.safeParse(content);
      if (response.status === 503 && envelope.success && envelope.data.code === 'not_ready') {
        return { data: { ready: false, conditions: envelope.data.conditions ?? [] }, ...meta };
      }
      throw refusal(path, response, content);
    },
  };
}

/** The header a request's token is presented in; never a query parameter, which reaches logs. */
const TOKEN_HEADER = 'X-Qraft-Token';

/** One path segment, encoded. */
function segment(value: string): string {
  return encodeURIComponent(value);
}

/** A request's path without its query, as errors name it. */
function pathOf(path: string): string {
  const query = path.indexOf('?');
  return query < 0 ? path : path.slice(0, query);
}

function closed(path: string, response: Response): ApiError {
  return new ApiError(`/${pathOf(path)}`, response.status, 'watch_closed', 'The event watch closed without its end',
    true, requestIdOf(response), null);
}

/** A server-sent event frame's type and data; data is null for a comment or a frame without data. */
function frameOf(frame: string): { type: string; data: string | null } {
  let type = 'message';
  const data: string[] = [];
  for (const line of frame.split('\n')) {
    if (line === '' || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
    if (field === 'event') type = value;
    else if (field === 'data') data.push(value);
  }
  return { type, data: data.length === 0 ? null : data.join('\n') };
}

function eventQueryText(query: EventQuery): string {
  const parameters = new URLSearchParams();
  if (query.after !== undefined) parameters.set('after', query.after);
  if (query.limit !== undefined) parameters.set('limit', String(query.limit));
  if (query.category !== undefined) parameters.set('category', query.category);
  if (query.tenant !== undefined) parameters.set('tenant', query.tenant);
  if (query.namespace !== undefined) parameters.set('namespace', query.namespace);
  if (query.service !== undefined) parameters.set('service', query.service);
  if (query.key !== undefined) parameters.set('key', query.key);
  if (query.session !== undefined) parameters.set('session', query.session);
  if (query.type !== undefined) parameters.set('type', query.type);
  const text = parameters.toString();
  return text === '' ? '' : `?${text}`;
}

function scopeHeaders(scope: Scope): Record<string, string> {
  return { 'X-Qraft-Tenant': scope.tenant, 'X-Qraft-Namespace': scope.namespace };
}

function requestIdOf(response: Response): string | null {
  return response.headers.get('X-Request-Id');
}

function metaOf(response: Response): Omit<Read<unknown>, 'data'> {
  const consistency = response.headers.get('X-Qraft-Consistency');
  const known = response.headers.get('X-Qraft-Known-Leader');
  return {
    index: numberOf(response.headers.get('X-Qraft-Index')),
    requestId: requestIdOf(response),
    answeredBy: response.headers.get('X-Qraft-Answered-By'),
    consistency: consistency === 'default' || consistency === 'consistent' || consistency === 'stale' ? consistency : null,
    knownLeader: known === 'true' ? true : known === 'false' ? false : null,
    lastContactMillis: numberOf(response.headers.get('X-Qraft-Last-Contact')),
    filtered: response.headers.get('X-Qraft-Results-Filtered-By-ACLs') === 'true',
  };
}

/** A request body sent as it is, not as JSON: a key/value value. */
class RawBody {
  constructor(readonly text: string) {}
}

/** The consistency and blocking-query options of a read, as query parameters. */
function optionsQuery(options: ReadOptions): string[] {
  const query: string[] = [];
  if (options.consistency === 'consistent' || options.consistency === 'stale') query.push(options.consistency);
  if (options.index !== undefined) {
    query.push(`index=${options.index}`);
    if (options.wait !== undefined) query.push(`wait=${options.wait}`);
  }
  return query;
}

/** A key in an address: each segment encoded, its `/` separators kept. */
function kvPath(key: string): string {
  return key.split('/').map(encodeURIComponent).join('/');
}

function numberOf(header: string | null): number | null {
  if (header === null || !/^\d+$/.test(header)) return null;
  return Number(header);
}
