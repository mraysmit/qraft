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
  type CatalogNode,
  CatalogNodeSchema,
  type CheckEntry,
  CheckEntrySchema,
  ErrorEnvelopeSchema,
  type HealthEntry,
  HealthEntrySchema,
  type Namespace,
  NamespaceSchema,
  type RaftStatus,
  RaftStatusSchema,
  ReadySchema,
  type ServiceNames,
  ServiceNamesSchema,
  type ServiceSummary,
  ServiceSummarySchema,
  type Tenant,
  TenantSchema,
} from './schemas';

/** The tenant and namespace a catalog or health read is confined to (UI/UX design section 3.2). */
export interface Scope {
  tenant: string;
  namespace: string;
}

/** The scope a server reads when a request names none. */
export const DEFAULT_SCOPE: Scope = { tenant: 'default', namespace: 'default' };

/** The states the check listing takes: every check, or those in one health state. */
export type CheckState = 'any' | 'unknown' | 'passing' | 'warning' | 'critical' | 'maintenance';

/** An answer with the read metadata the server sent with it. */
export interface Read<T> {
  data: T;
  /** The applied index the answer reflects, `X-Qraft-Index`, or null when the server sent none. */
  index: number | null;
  requestId: string | null;
}

export interface Readiness {
  ready: boolean;
  /** The readiness conditions the server does not meet, empty when it is ready. */
  conditions: string[];
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

/**
 * The reads the views make, all through the public HTTP API. Each catalog and health read names its scope, the
 * default when none is given; a node, the Raft status and readiness have none.
 */
export interface QraftApi {
  raftStatus(): Promise<Read<RaftStatus>>;
  readiness(): Promise<Read<Readiness>>;
  nodes(): Promise<Read<CatalogNode[]>>;
  services(scope?: Scope): Promise<Read<ServiceNames>>;
  serviceHealth(name: string, scope?: Scope): Promise<Read<HealthEntry[]>>;
  servicesSummary(scope?: Scope): Promise<Read<ServiceSummary[]>>;
  checks(state: CheckState, scope?: Scope): Promise<Read<CheckEntry[]>>;
  tenants(): Promise<Read<Tenant[]>>;
  namespaces(tenant: string): Promise<Read<Namespace[]>>;
}

/** A client for the API below `apiBasePath`, the bootstrap object's base path, which ends with `/`. */
export function createApi(apiBasePath: string, fetcher: typeof fetch = fetch): QraftApi {
  const base = apiBasePath.endsWith('/') ? apiBasePath : `${apiBasePath}/`;

  async function send(path: string, headers: Record<string, string> = {}): Promise<Response> {
    try {
      return await fetcher(base + path, { headers: { Accept: 'application/json', ...headers } });
    } catch (failure) {
      const reason = failure instanceof Error ? failure.message : String(failure);
      throw new ApiError(`/${path}`, 0, 'unreachable', `The server could not be reached: ${reason}`, true, null, null);
    }
  }

  async function body(path: string, response: Response): Promise<unknown> {
    const text = await response.text();
    try {
      return JSON.parse(text) as unknown;
    } catch {
      if (response.ok) throw new MalformedResponseError(`/${path}`, requestIdOf(response), 'the body is not JSON');
      return undefined;
    }
  }

  function refusal(path: string, response: Response, content: unknown): ApiError {
    const envelope = ErrorEnvelopeSchema.safeParse(content);
    const requestId = requestIdOf(response);
    const leaderId = response.headers.get('X-Qraft-Leader-Id');
    if (!envelope.success) {
      return new ApiError(`/${path}`, response.status, `http_${response.status}`,
        `The server answered ${response.status} ${response.statusText}`.trim(), false, requestId, leaderId);
    }
    const { code, message, retryable } = envelope.data;
    return new ApiError(`/${path}`, response.status, code, message, retryable, envelope.data.requestId ?? requestId,
      leaderId);
  }

  function parse<T>(path: string, response: Response, schema: ZodType<T>, content: unknown): T {
    const parsed = schema.safeParse(content);
    if (!parsed.success) {
      throw new MalformedResponseError(`/${path}`, requestIdOf(response), z.prettifyError(parsed.error));
    }
    return parsed.data;
  }

  async function read<T>(path: string, schema: ZodType<T>, headers: Record<string, string> = {}): Promise<Read<T>> {
    const response = await send(path, headers);
    const content = await body(path, response);
    if (!response.ok) throw refusal(path, response, content);
    return { data: parse(path, response, schema, content), index: indexOf(response), requestId: requestIdOf(response) };
  }

  return {
    raftStatus: () => read('raft/status', RaftStatusSchema),
    nodes: () => read('v1/catalog/nodes', z.array(CatalogNodeSchema)),
    services: (scope = DEFAULT_SCOPE) => read('v1/catalog/services', ServiceNamesSchema, scopeHeaders(scope)),
    serviceHealth: (name, scope = DEFAULT_SCOPE) =>
      read(`v1/health/service/${encodeURIComponent(name)}`, z.array(HealthEntrySchema), scopeHeaders(scope)),
    servicesSummary: (scope = DEFAULT_SCOPE) =>
      read('v1/internal/ui/services', z.array(ServiceSummarySchema), scopeHeaders(scope)),
    checks: (state, scope = DEFAULT_SCOPE) =>
      read(`v1/health/state/${state}`, z.array(CheckEntrySchema), scopeHeaders(scope)),
    tenants: () => read('v1/tenants', z.array(TenantSchema)),
    namespaces: (tenant) => read('v1/namespaces', z.array(NamespaceSchema), { 'X-Qraft-Tenant': tenant }),
    async readiness() {
      const path = 'health/ready';
      const response = await send(path);
      const content = await body(path, response);
      const meta = { index: indexOf(response), requestId: requestIdOf(response) };
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

function scopeHeaders(scope: Scope): Record<string, string> {
  return { 'X-Qraft-Tenant': scope.tenant, 'X-Qraft-Namespace': scope.namespace };
}

function requestIdOf(response: Response): string | null {
  return response.headers.get('X-Request-Id');
}

function indexOf(response: Response): number | null {
  const header = response.headers.get('X-Qraft-Index');
  if (header === null || !/^\d+$/.test(header)) return null;
  return Number(header);
}
