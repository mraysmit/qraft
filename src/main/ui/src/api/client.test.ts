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

import { describe, expect, it } from 'vitest';
import { ApiError, createApi, MalformedResponseError } from './client';
import { CHECK_ENTRY, HEALTH_ENTRY, NAMESPACE, NODE, RAFT_STATUS, SUMMARY_ENTRY, TENANT } from './fixtures';

interface Call {
  url: string;
  init: RequestInit | undefined;
}

// Answers each request with what `answer` returns for its URL, and records the calls.
function server(answer: (url: string) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return Promise.resolve(answer(url));
  };
  return { calls, fetcher: fetcher as typeof fetch };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

describe('createApi', () => {
  it('reads the answering node Raft status with the applied index', async () => {
    const { calls, fetcher } = server(() => json(RAFT_STATUS, 200, { 'X-Qraft-Index': '42', 'X-Request-Id': 'r-1' }));

    const read = await createApi('/', fetcher).raftStatus();

    expect(calls[0]?.url).toBe('/raft/status');
    expect(read.data.state).toBe('LEADER');
    expect(read.data.leaderId).toBe('server-1');
    expect(read.index).toBe(42);
    expect(read.requestId).toBe('r-1');
  });

  it('builds every path below the API base path the server names', async () => {
    const { calls, fetcher } = server(() => json({}));

    await createApi('/qraft/', fetcher).services();

    expect(calls[0]?.url).toBe('/qraft/v1/catalog/services');
  });

  it('sends a service name as one path segment', async () => {
    const { calls, fetcher } = server(() => json([]));

    await createApi('/', fetcher).serviceHealth('team/payments');

    expect(calls[0]?.url).toBe('/v1/health/service/team%2Fpayments');
  });

  it('reads the service names with their tags, the nodes, and a service instances with their checks', async () => {
    const { fetcher } = server((url) => {
      if (url.endsWith('/v1/catalog/services')) return json({ web: ['http'] });
      if (url.endsWith('/v1/catalog/nodes')) return json([NODE]);
      return json([HEALTH_ENTRY]);
    });
    const api = createApi('/', fetcher);

    expect((await api.services()).data).toEqual({ web: ['http'] });
    expect((await api.nodes()).data[0]?.metadata['qraft.version']).toBe('1.0-SNAPSHOT');
    const entry = (await api.serviceHealth('web')).data[0];
    expect(entry?.service.serviceId).toBe('web-1');
    expect(entry?.checks[0]?.status).toBe('PASSING');
    expect(entry?.nodeStatus).toBe('healthy');
  });

  it('reads an index that is absent as unknown', async () => {
    const { fetcher } = server(() => json({}));

    expect((await createApi('/', fetcher).services()).index).toBeNull();
  });

  it('rejects a response that does not match its schema', async () => {
    const { fetcher } = server(() => json({ ...RAFT_STATUS, state: 'PRESIDENT' }, 200, { 'X-Request-Id': 'r-2' }));

    const failure = await createApi('/', fetcher).raftStatus().catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(MalformedResponseError);
    expect((failure as MalformedResponseError).path).toBe('/raft/status');
    expect((failure as MalformedResponseError).requestId).toBe('r-2');
    expect((failure as MalformedResponseError).message).toMatch(/state/);
  });

  it('rejects a body that is not JSON', async () => {
    const { fetcher } = server(() => new Response('<html>proxy error</html>', { status: 200 }));

    await expect(createApi('/', fetcher).nodes()).rejects.toBeInstanceOf(MalformedResponseError);
  });

  it('turns the error envelope into an error with its code, retryability, request ID and leader hint', async () => {
    const { fetcher } = server(() =>
      json(
        { code: 'leader_unavailable', error: 'leader_unavailable', message: 'No leader', retryable: true, requestId: 'r-3' },
        503,
        { 'X-Qraft-Leader-Id': 'server-2' },
      ),
    );

    const failure = (await createApi('/', fetcher).nodes().catch((error: unknown) => error)) as ApiError;

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(503);
    expect(failure.code).toBe('leader_unavailable');
    expect(failure.message).toBe('No leader');
    expect(failure.retryable).toBe(true);
    expect(failure.requestId).toBe('r-3');
    expect(failure.leaderId).toBe('server-2');
  });

  it('describes an error without an envelope by its status', async () => {
    const { fetcher } = server(() => new Response('Bad gateway', { status: 502 }));

    const failure = (await createApi('/', fetcher).nodes().catch((error: unknown) => error)) as ApiError;

    expect(failure.code).toBe('http_502');
    expect(failure.retryable).toBe(false);
  });

  it('reports a server it cannot reach as a retryable error', async () => {
    const fetcher = (() => Promise.reject(new TypeError('Failed to fetch'))) as typeof fetch;

    const failure = (await createApi('/', fetcher).nodes().catch((error: unknown) => error)) as ApiError;

    expect(failure.code).toBe('unreachable');
    expect(failure.status).toBe(0);
    expect(failure.retryable).toBe(true);
  });

  it('reads readiness, and a server that is not ready as an answer with its conditions', async () => {
    const ready = server(() => json({ status: 'ready' }));
    const notReady = server(() =>
      json({ code: 'not_ready', message: 'Server is not ready: no_leader', retryable: true, conditions: ['no_leader'] }, 503),
    );

    expect((await createApi('/', ready.fetcher).readiness()).data).toEqual({ ready: true, conditions: [] });
    expect((await createApi('/', notReady.fetcher).readiness()).data).toEqual({ ready: false, conditions: ['no_leader'] });
    expect(ready.calls[0]?.url).toBe('/health/ready');
  });
});

// UI-2 task list, Step 3: every catalog and health read names the scope it reads in.
describe('scoped reads', () => {
  const ACME_PROD = { tenant: 'acme', namespace: 'prod' };

  function header(call: Call | undefined, name: string): string | null {
    return new Headers(call?.init?.headers).get(name);
  }

  it('send the scope headers on every catalog and health read', async () => {
    const { calls, fetcher } = server((url) => {
      if (url.includes('/v1/internal/ui/services')) return json([SUMMARY_ENTRY]);
      if (url.includes('/v1/health/state/')) return json([CHECK_ENTRY]);
      if (url.includes('/v1/catalog/services')) return json({});
      return json([]);
    });
    const api = createApi('/', fetcher);

    await api.services(ACME_PROD);
    await api.serviceHealth('web', ACME_PROD);
    await api.servicesSummary(ACME_PROD);
    await api.checks('any', ACME_PROD);

    expect(calls).toHaveLength(4);
    for (const call of calls) {
      expect(header(call, 'X-Qraft-Tenant'), call.url).toBe('acme');
      expect(header(call, 'X-Qraft-Namespace'), call.url).toBe('prod');
    }
  });

  it('read the default scope when none is given, and name it', async () => {
    const { calls, fetcher } = server(() => json({}));

    await createApi('/', fetcher).services();

    expect(header(calls[0], 'X-Qraft-Tenant')).toBe('default');
    expect(header(calls[0], 'X-Qraft-Namespace')).toBe('default');
  });

  it('read the services summary and the checks in a state', async () => {
    const { calls, fetcher } = server((url) =>
      url.includes('/v1/health/state/') ? json([CHECK_ENTRY]) : json([SUMMARY_ENTRY], 200, { 'X-Qraft-Index': '9' }),
    );
    const api = createApi('/', fetcher);

    const summary = await api.servicesSummary();
    const checks = await api.checks('critical');

    expect(calls.map((call) => call.url)).toEqual(['/v1/internal/ui/services', '/v1/health/state/critical']);
    expect(summary.data[0]?.health.critical).toBe(1);
    expect(summary.index).toBe(9);
    expect(checks.data[0]?.serviceName).toBe('web');
    expect(checks.data[0]?.nodeStatus).toBe('healthy');
  });

  it('read the tenants, and the namespaces of the tenant named', async () => {
    const { calls, fetcher } = server((url) => (url.endsWith('/v1/tenants') ? json([TENANT]) : json([NAMESPACE])));
    const api = createApi('/', fetcher);

    expect((await api.tenants()).data.map((tenant) => tenant.Name)).toEqual(['acme']);
    expect((await api.namespaces('acme')).data.map((namespace) => namespace.Name)).toEqual(['prod']);
    expect(calls[1]?.url).toBe('/v1/namespaces');
    expect(header(calls[1], 'X-Qraft-Tenant')).toBe('acme');
  });

  it('refuse a tenancy answer that is not the expected shape', async () => {
    const { fetcher } = server(() => json([{ Name: 7 }]));

    await expect(createApi('/', fetcher).tenants()).rejects.toBeInstanceOf(MalformedResponseError);
  });
});
