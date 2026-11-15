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
import { ApiError, createApi } from './client';
import { EXPLANATION, POLICY, TOKEN } from './fixtures';

// UI-5 task list, Phase 2: the token and policy reads and writes, the explanation, and the outcome of a write that was
// sent and never answered (UI/UX design section 11).

interface Call {
  url: string;
  method: string;
  body: unknown;
}

function server(answer: (call: Call) => Response) {
  const calls: Call[] = [];
  const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
    const call = {
      url: String(input),
      method: init?.method ?? 'GET',
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)) as unknown,
    };
    calls.push(call);
    return Promise.resolve(answer(call));
  };
  return { calls, fetcher: fetcher as typeof fetch };
}

function json(body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json', ...headers } });
}

const created = { ...TOKEN, secretId: 'made-up-secret-for-the-test' };

describe('token and policy requests', () => {
  it('lists, reads, creates, updates, clones and deletes tokens', async () => {
    const { calls, fetcher } = server((call) => {
      if (call.method === 'DELETE') return json(true, { 'X-Qraft-Index': '41', 'X-Request-Id': 'req-del' });
      if (call.url.endsWith('/v1/acl/tokens') || call.url.includes('/v1/acl/tokens?')) return json([TOKEN]);
      return json(call.method === 'PUT' && !call.url.match(/token\/[^/]+$/) ? created : TOKEN, { 'X-Qraft-Index': '40' });
    });
    const api = createApi('/', fetcher);
    const body = {
      description: 'web deployer',
      policies: [{ id: POLICY.id }],
      nodeIdentities: [{ nodeName: 'client-a' }],
      serviceIdentities: [{ serviceName: 'web', tenant: 'default', namespace: 'default' }],
      expirationTtl: '1h',
    };

    await api.tokens();
    await api.tokens({ policy: POLICY.id });
    await api.token(TOKEN.accessorId);
    const made = await api.createToken(body);
    await api.updateToken(TOKEN.accessorId, { description: 'renamed', policies: [{ id: POLICY.id }] });
    const clone = await api.cloneToken(TOKEN.accessorId, 'replacement');
    const deleted = await api.deleteToken(TOKEN.accessorId);

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /v1/acl/tokens',
      `GET /v1/acl/tokens?policy=${POLICY.id}`,
      `GET /v1/acl/token/${TOKEN.accessorId}`,
      'PUT /v1/acl/token',
      `PUT /v1/acl/token/${TOKEN.accessorId}`,
      `PUT /v1/acl/token/${TOKEN.accessorId}/clone`,
      `DELETE /v1/acl/token/${TOKEN.accessorId}`,
    ]);
    expect(calls[3]?.body).toEqual(body);
    expect(calls[5]?.body).toEqual({ description: 'replacement' });
    expect(made.data.secretId).toBe('made-up-secret-for-the-test');
    expect(made.index).toBe(40);
    expect(clone.data.secretId).toBe('made-up-secret-for-the-test');
    expect(deleted.data).toBe(true);
    expect(deleted.requestId).toBe('req-del');
  });

  it('lists, reads, creates, updates and deletes policies', async () => {
    const { calls, fetcher } = server((call) => {
      if (call.method === 'DELETE') return json(true);
      return json(call.url.endsWith('/policies') ? [POLICY] : POLICY);
    });
    const api = createApi('/', fetcher);
    const body = { name: 'web-writer', description: 'writes web', rules: { service: { web: { policy: 'write' } } } };

    const listed = await api.policies();
    await api.policy(POLICY.id);
    await api.createPolicy(body);
    await api.updatePolicy(POLICY.id, body);
    await api.deletePolicy(POLICY.id);

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /v1/acl/policies',
      `GET /v1/acl/policy/${POLICY.id}`,
      'PUT /v1/acl/policy',
      `PUT /v1/acl/policy/${POLICY.id}`,
      `DELETE /v1/acl/policy/${POLICY.id}`,
    ]);
    expect(calls[2]?.body).toEqual(body);
    expect(listed.data[0]?.rules).toEqual(POLICY.rules);
  });

  it('asks the server to explain a decision, which it makes with the authorizer it enforces with', async () => {
    const { calls, fetcher } = server(() => json(EXPLANATION));

    const explained = await createApi('/', fetcher).explain({
      accessorId: TOKEN.accessorId, resource: 'service', name: 'web', access: 'write', tenant: 'default', namespace: 'default',
    });

    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toBe('/v1/acl/explain');
    expect(calls[0]?.body).toEqual({
      accessorId: TOKEN.accessorId, resource: 'service', name: 'web', access: 'write', tenant: 'default', namespace: 'default',
    });
    expect(explained.data.allowed).toBe(true);
    expect(explained.data.policy?.name).toBe('web-writer');
    expect(explained.data.rule?.access).toBe('write');
  });
});

describe('service and membership changes', () => {
  // UI-5 task list, Phase 3: a service registered, updated or deregistered on a node in a scope, and a server removed.
  it('registers and deregisters an instance on the node and in the scope given', async () => {
    const calls: { url: string; method: string; headers: Headers; body: unknown }[] = [];
    const fetcher = ((input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method ?? 'GET', headers: new Headers(init?.headers),
        body: init?.body === undefined ? undefined : JSON.parse(String(init.body)) as unknown });
      return Promise.resolve(json(String(input).endsWith('/register')
        ? { node: 'client-a', service: { serviceId: 'web-2', serviceName: 'web', nodeId: 'client-a', tenantId: 'acme',
          namespace: 'prod', registered: true } }
        : { node: 'client-a', serviceId: 'web-2', deregistered: true }, { 'X-Qraft-Index': '60' }));
    }) as typeof fetch;
    const api = createApi('/', fetcher);
    const scope = { tenant: 'acme', namespace: 'prod' };
    const service = { serviceId: 'web-2', serviceName: 'web', address: '10.0.0.6', port: 8082, tags: ['http'],
      metadata: { owner: 'platform' }, enabled: false };

    const registered = await api.registerService('client-a', scope, service);
    const deregistered = await api.deregisterService('client-a', scope, 'web-2');

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual(['PUT /v1/catalog/register', 'PUT /v1/catalog/deregister']);
    for (const call of calls) {
      expect(call.headers.get('X-Qraft-Node')).toBe('client-a');
      expect(call.headers.get('X-Qraft-Tenant')).toBe('acme');
      expect(call.headers.get('X-Qraft-Namespace')).toBe('prod');
    }
    expect(calls[0]?.body).toEqual({ service });
    expect(calls[1]?.body).toEqual({ serviceId: 'web-2' });
    expect(registered.data.service?.registered).toBe(true);
    expect(registered.index).toBe(60);
    expect(deregistered.data.deregistered).toBe(true);
  });

  it("removes a server by its ID, with the session's token or, without access control, the operator token given", async () => {
    const calls: { url: string; method: string; token: string | null }[] = [];
    const fetcher = ((input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method ?? 'GET', token: new Headers(init?.headers).get('X-Qraft-Token') });
      return Promise.resolve(json({ status: 'REMOVED', message: 'server-3 removed' }));
    }) as typeof fetch;
    const api = createApi('/', fetcher, { secret: () => 'session-secret', onTokenNotFound: () => undefined });

    const removed = await api.removeServer('server-3-id');
    await api.removeServer('server-3-id', 'operator-token-typed');

    expect(calls).toEqual([
      { url: '/v1/operator/raft/peer?id=server-3-id', method: 'DELETE', token: 'session-secret' },
      { url: '/v1/operator/raft/peer?id=server-3-id', method: 'DELETE', token: 'operator-token-typed' },
    ]);
    expect(removed.data.status).toBe('REMOVED');
  });
});

describe('a write sent and never answered', () => {
  it('has an unknown outcome, never a failure that could be retried blindly', async () => {
    const fetcher = (() => Promise.reject(new TypeError('Failed to fetch'))) as typeof fetch;
    const api = createApi('/', fetcher);

    const write = await api.deleteToken(TOKEN.accessorId).catch((error: unknown) => error);
    const read = await api.tokens().catch((error: unknown) => error);

    expect(write).toBeInstanceOf(ApiError);
    expect((write as ApiError).code).toBe('outcome_unknown');
    expect((write as ApiError).retryable).toBe(false);
    expect((read as ApiError).code, 'a read that is not answered changed nothing').toBe('unreachable');
  });
});
