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
import { createApi, MalformedResponseError } from './client';
import { totalsOf, validScopeName } from './tenancy';

// UI-6 Tenants, Namespaces and Audit task list, Phase 1: the scopes read, and a tenant's totals.

function server(answer: (url: string, init?: RequestInit) => Response) {
  const calls: { url: string; init: RequestInit | undefined }[] = [];
  const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    return Promise.resolve(answer(String(input), init));
  };
  return { calls, fetcher: fetcher as typeof fetch };
}

const json = (body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json', ...headers } });

const PROD = { Name: 'prod', Description: 'production', CreateIndex: 5, ModifyIndex: 6, Services: 2, Instances: 3,
  Keys: 3, HeldKeys: 1, Sessions: ['s-1'] };
const EMPTY = { Name: 'default', Description: '', CreateIndex: 4, ModifyIndex: 4, Services: 0, Instances: 0, Keys: 0,
  HeldKeys: 0, Sessions: [] };
const ACME = { Name: 'acme', Description: 'Acme Ltd', CreateIndex: 4, ModifyIndex: 4, Namespaces: [EMPTY, PROD] };

describe('the scopes read', () => {
  it('reads every tenant with what each namespace holds, in the consistency and blocking query asked', async () => {
    const { calls, fetcher } = server(() => json({ Tenants: [ACME] }, { 'X-Qraft-Index': '9' }));

    const scopes = await createApi('/', fetcher).scopes({ consistency: 'stale', index: 8, wait: '30s' });

    expect(calls[0]?.url).toBe('/v1/internal/ui/scopes?stale&index=8&wait=30s');
    expect(scopes.data.Tenants[0]?.Namespaces[1]).toEqual(PROD);
    expect(scopes.index).toBe(9);
  });

  it('refuses an answer not in the form it reads', async () => {
    const { fetcher } = server(() => json({ Tenants: [{ ...ACME, Namespaces: [{ ...PROD, Keys: 'three' }] }] }));

    await expect(createApi('/', fetcher).scopes()).rejects.toBeInstanceOf(MalformedResponseError);
  });

  it("sums a tenant's namespaces", () => {
    expect(totalsOf(ACME)).toEqual({ namespaces: 2, services: 2, instances: 3, keys: 3, heldKeys: 1 });
  });
});

describe('the tenancy writes', () => {
  // Phase 2: creating a tenant or namespace, changing its description, and deleting it (decisions 3 and 10).
  it('send each change as the tenancy API takes it', async () => {
    const answer = (url: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') return true;
      return url.includes('namespace') ? { Name: 'staging', Tenant: 'acme', Description: '' }
        : { Name: 'acme', Description: 'Acme' };
    };
    const { calls, fetcher } = server((url, init) => json(answer(url, init)));
    const api = createApi('/', fetcher);

    await api.createTenant('acme', 'Acme');
    await api.updateTenant('acme', 'Acme');
    await api.deleteTenant('acme');
    await api.createNamespace('acme', 'staging', '');
    await api.updateNamespace('acme', 'staging', 'Staging');
    await api.deleteNamespace('acme', 'prod');

    expect(calls.map((call) => [call.init?.method, call.url, call.init?.body ?? null,
      (call.init?.headers as Record<string, string>)['X-Qraft-Tenant'] ?? null])).toEqual([
      ['PUT', '/v1/tenant', JSON.stringify({ Name: 'acme', Description: 'Acme' }), null],
      ['PUT', '/v1/tenant/acme', JSON.stringify({ Description: 'Acme' }), null],
      ['DELETE', '/v1/tenant/acme', null, null],
      ['PUT', '/v1/namespace', JSON.stringify({ Name: 'staging', Tenant: 'acme', Description: '' }), null],
      ['PUT', '/v1/namespace/staging', JSON.stringify({ Description: 'Staging' }), 'acme'],
      ['DELETE', '/v1/namespace/prod', null, 'acme'],
    ]);
  });

  it('check a name as the server does', () => {
    for (const valid of ['acme', 'a', 'acme-2', 'x'.repeat(63)]) expect(validScopeName(valid), valid).toBe(true);
    for (const invalid of ['', 'Acme', 'a_b', '-a', 'a-', 'a.b', 'x'.repeat(64)]) {
      expect(validScopeName(invalid), invalid).toBe(false);
    }
  });
});

describe('the audit read', () => {
  // Phase 3: the answering server's audit file, read from its end with its filters (decision 5).
  const RECORD = { Type: 'audit.http_request', Timestamp: '2026-11-17T10:00:05Z', Node: 's1', AccessorId: 'a2',
    Method: 'GET', Route: '/v1/acl/tokens', Status: 200, Tenant: 'default', Namespace: 'default', RequestId: 'r5' };

  it('sends its filters, and reads the records, lost stretches and unreadable lines', async () => {
    const answer = { Configured: true, File: '/data/audit.json', Size: 900, Scanned: 900, Complete: true,
      Records: [RECORD, { Type: 'audit.events_lost' }, { Type: 'audit.unreadable_line' }] };
    const { calls, fetcher } = server(() => json(answer));

    const audit = await createApi('/', fetcher).audit({ accessor: 'a 2', result: 'denied', before: '2026-11-17T10:00:06Z' });

    expect(calls[0]?.url).toBe('/v1/internal/ui/audit?limit=100&accessor=a+2&result=denied&before=2026-11-17T10%3A00%3A06Z');
    expect(audit.data.Records).toHaveLength(3);
    expect(audit.data.Records[0]).toMatchObject({ AccessorId: 'a2', Status: 200 });
  });

  it('reads a server with no audit file', async () => {
    const { fetcher } = server(() => json({ Configured: false, File: null, Size: 0, Scanned: 0, Complete: true, Records: [] }));

    expect((await createApi('/', fetcher).audit({})).data.Configured).toBe(false);
  });
});
