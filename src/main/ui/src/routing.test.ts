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
import { DEFAULT_SCOPE } from './api/client';
import { consistencyOf, hrefOf, routeOf, scopeOf } from './routing';

describe('routes', () => {
  it('read the views below the interface path', () => {
    expect(routeOf('/ui/', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui/services', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui/nodes', '/ui/')).toEqual({ view: 'nodes' });
    expect(routeOf('/ui/services/web', '/ui/')).toEqual({ view: 'service', name: 'web' });
    expect(routeOf('/console/nodes', '/console/')).toEqual({ view: 'nodes' });
  });

  it('take anything else for an unknown view', () => {
    for (const path of ['/ui/nowhere', '/ui/constructor', '/ui/__proto__', '/ui/services/a/b', '/ui/services/', '/other/', '/ui/services/%E0%A4%A']) {
      expect(routeOf(path, '/ui/'), path).toEqual({ view: 'unknown' });
    }
  });

  it('write a service name as one segment with no dot, so the server never takes it for a file', () => {
    const href = hrefOf({ view: 'service', name: 'team/payments.v2' }, '/ui/');

    expect(href).toBe('/ui/services/team%2Fpayments%2Ev2');
    expect(href.slice(href.lastIndexOf('/') + 1)).not.toContain('.');
    expect(routeOf(href, '/ui/')).toEqual({ view: 'service', name: 'team/payments.v2' });
  });

  it('write the other views below the interface path', () => {
    expect(hrefOf({ view: 'services' }, '/console/')).toBe('/console/');
    expect(hrefOf({ view: 'nodes' }, '/console/')).toBe('/console/nodes');
    expect(hrefOf({ view: 'checks' }, '/console/')).toBe('/console/checks');
    expect(routeOf('/console/checks', '/console/')).toEqual({ view: 'checks' });
  });

  // UI-2 task list, decision 5: the selected scope is in the address, so a view in a scope can be reloaded and shared.
  it('carry a scope other than the default in the query, and leave the default out', () => {
    const acme = { tenant: 'acme', namespace: 'prod' };

    expect(hrefOf({ view: 'nodes' }, '/ui/', acme)).toBe('/ui/nodes?tenant=acme&namespace=prod');
    expect(hrefOf({ view: 'service', name: 'web' }, '/ui/', acme)).toBe('/ui/services/web?tenant=acme&namespace=prod');
    expect(hrefOf({ view: 'nodes' }, '/ui/', DEFAULT_SCOPE)).toBe('/ui/nodes');
    expect(hrefOf({ view: 'services' }, '/ui/', { tenant: 'a&b', namespace: 'c d' })).toBe(
      '/ui/?tenant=a%26b&namespace=c+d',
    );
  });

  it('read the scope from the query, the default for what is missing or blank', () => {
    expect(scopeOf('?tenant=acme&namespace=prod')).toEqual({ tenant: 'acme', namespace: 'prod' });
    expect(scopeOf('?tenant=acme')).toEqual({ tenant: 'acme', namespace: 'default' });
    expect(scopeOf('')).toEqual(DEFAULT_SCOPE);
    expect(scopeOf('?tenant=&namespace=%20')).toEqual(DEFAULT_SCOPE);
    expect(scopeOf('?tenant=a%26b&namespace=c+d')).toEqual({ tenant: 'a&b', namespace: 'c d' });
  });
});

describe('Operate routes', () => {
  it('read and write the Cluster Overview, Raft Members and Storage & Snapshots views', () => {
    for (const view of ['cluster', 'members', 'storage'] as const) {
      expect(routeOf(`/ui/${view}`, '/ui/')).toEqual({ view });
      expect(hrefOf({ view }, '/ui/')).toBe(`/ui/${view}`);
    }
  });
});

describe('consistency and the UI-4 views', () => {
  // UI-4 task list, decision 4: the consistency chosen is in the address, as the scope is.
  it('carry a consistency other than the default in the query, after the scope', () => {
    expect(hrefOf({ view: 'nodes' }, '/ui/', DEFAULT_SCOPE, 'stale')).toBe('/ui/nodes?consistency=stale');
    expect(hrefOf({ view: 'services' }, '/ui/', { tenant: 'acme', namespace: 'prod' }, 'consistent')).toBe(
      '/ui/?tenant=acme&namespace=prod&consistency=consistent',
    );
    expect(hrefOf({ view: 'nodes' }, '/ui/', DEFAULT_SCOPE, 'default')).toBe('/ui/nodes');
  });

  it('read the consistency from the query, the default for anything else', () => {
    expect(consistencyOf('?consistency=consistent')).toBe('consistent');
    expect(consistencyOf('?tenant=a&consistency=stale')).toBe('stale');
    expect(consistencyOf('')).toBe('default');
    expect(consistencyOf('?consistency=eventual')).toBe('default');
  });

  it('read and write the Events and Configuration views', () => {
    expect(routeOf('/ui/events', '/ui/')).toEqual({ view: 'events' });
    expect(routeOf('/ui/configuration', '/ui/')).toEqual({ view: 'configuration' });
    expect(hrefOf({ view: 'events' }, '/ui/')).toBe('/ui/events');
    expect(hrefOf({ view: 'configuration' }, '/ui/', DEFAULT_SCOPE, 'stale')).toBe('/ui/configuration?consistency=stale');
  });
});

describe('the Access routes and the audit link', () => {
  // UI-5 task list, Phase 2: the Access section's pages, and the Events page filtered to a request's audit events.
  it('read and write the Status, Tokens, Policies and Explain pages, and a token and a policy', () => {
    const id = '6a1f0c2e-3b7d-4c55-9a10-2f4e8d1b7c90';
    expect(routeOf('/ui/access', '/ui/')).toEqual({ view: 'access' });
    expect(routeOf('/ui/access/tokens', '/ui/')).toEqual({ view: 'tokens' });
    expect(routeOf(`/ui/access/tokens/${id}`, '/ui/')).toEqual({ view: 'token', accessorId: id });
    expect(routeOf('/ui/access/policies', '/ui/')).toEqual({ view: 'policies' });
    expect(routeOf(`/ui/access/policies/${id}`, '/ui/')).toEqual({ view: 'policy', id });
    expect(routeOf('/ui/access/explain', '/ui/')).toEqual({ view: 'explain' });
    expect(routeOf('/ui/access/tokens/a/b', '/ui/')).toEqual({ view: 'unknown' });
    expect(hrefOf({ view: 'tokens' }, '/ui/')).toBe('/ui/access/tokens');
    expect(hrefOf({ view: 'token', accessorId: id }, '/ui/')).toBe(`/ui/access/tokens/${id}`);
    expect(hrefOf({ view: 'policies' }, '/ui/')).toBe('/ui/access/policies');
    expect(hrefOf({ view: 'policy', id }, '/ui/')).toBe(`/ui/access/policies/${id}`);
    expect(hrefOf({ view: 'explain' }, '/ui/')).toBe('/ui/access/explain');
  });

  it("read and write the Events page's category and correlation ID, after the scope and consistency", () => {
    expect(routeOf('/ui/events', '/ui/', '?category=audit&correlation=req-7')).toEqual(
      { view: 'events', category: 'audit', correlation: 'req-7' });
    expect(routeOf('/ui/events', '/ui/', '?category=everything')).toEqual({ view: 'events' });
    expect(hrefOf({ view: 'events', category: 'audit', correlation: 'req-7' }, '/ui/', DEFAULT_SCOPE, 'stale')).toBe(
      '/ui/events?consistency=stale&category=audit&correlation=req-7');
  });

  // UI-6 Key/Value task list, decision 3: the folder or key is in the query, since the server takes an address with
  // an empty segment for none and one whose last segment has a dot for a file, and a key may have either.
  it('read and write the Key/Value browser, a folder or a key in the query, after the scope and consistency', () => {
    expect(routeOf('/ui/kv', '/ui/')).toEqual({ view: 'kv', path: '' });
    expect(routeOf('/ui/kv', '/ui/', '?path=app%2Fdb%2F')).toEqual({ view: 'kv', path: 'app/db/' });
    expect(routeOf('/ui/kv', '/ui/', '?path=app//a b.json')).toEqual({ view: 'kv', path: 'app//a b.json' });
    expect(routeOf('/ui/kv/app', '/ui/')).toEqual({ view: 'unknown' });
    expect(hrefOf({ view: 'kv', path: '' }, '/ui/')).toBe('/ui/kv');
    expect(hrefOf({ view: 'kv', path: 'app/config.json' }, '/ui/')).toBe('/ui/kv?path=app%2Fconfig.json');
    expect(hrefOf({ view: 'kv', path: 'app/' }, '/ui/', { tenant: 'acme', namespace: 'prod' }, 'stale')).toBe(
      '/ui/kv?tenant=acme&namespace=prod&consistency=stale&path=app%2F');
  });
});

describe('the Sessions routes', () => {
  // UI-6 Sessions, Locks and Watches task list, Phase 1 (decision 12): the list, and a session by its ID.
  it('read and write the Sessions page and a session', () => {
    const id = '4f3c2a10-0000-4000-8000-000000000001';
    expect(routeOf('/ui/sessions', '/ui/')).toEqual({ view: 'sessions' });
    expect(routeOf(`/ui/sessions/${id}`, '/ui/')).toEqual({ view: 'sessionInfo', id });
    expect(routeOf('/ui/sessions/a/b', '/ui/')).toEqual({ view: 'unknown' });
    expect(routeOf('/ui/sessions/', '/ui/')).toEqual({ view: 'unknown' });
    expect(hrefOf({ view: 'sessions' }, '/ui/', DEFAULT_SCOPE, 'stale')).toBe('/ui/sessions?consistency=stale');
    expect(hrefOf({ view: 'sessionInfo', id }, '/ui/')).toBe(`/ui/sessions/${id}`);
  });
});

describe('the Locks route', () => {
  // UI-6 Sessions, Locks and Watches task list, Phase 3 (decision 12).
  it('reads and writes the Locks page', () => {
    expect(routeOf('/ui/locks', '/ui/')).toEqual({ view: 'locks' });
    expect(hrefOf({ view: 'locks' }, '/ui/', DEFAULT_SCOPE, 'consistent')).toBe('/ui/locks?consistency=consistent');
  });
});

describe('the Watches route', () => {
  // UI-6 Sessions, Locks and Watches task list, Phase 4 (decision 12): the target and its name in the query.
  it('reads and writes the Watches page with its target', () => {
    expect(routeOf('/ui/watches', '/ui/')).toEqual({ view: 'watches' });
    expect(routeOf('/ui/watches', '/ui/', '?target=key&name=app%2Fconfig.json')).toEqual(
      { view: 'watches', target: 'key', name: 'app/config.json' });
    expect(routeOf('/ui/watches', '/ui/', '?target=sessions')).toEqual({ view: 'watches', target: 'sessions' });
    expect(routeOf('/ui/watches', '/ui/', '?target=nonsense&name=x')).toEqual({ view: 'watches' });
    expect(hrefOf({ view: 'watches', target: 'prefix', name: 'app/' }, '/ui/', { tenant: 'acme', namespace: 'prod' }))
      .toBe('/ui/watches?tenant=acme&namespace=prod&target=prefix&name=app%2F');
    expect(hrefOf({ view: 'watches' }, '/ui/')).toBe('/ui/watches');
  });
});

describe('the Govern routes', () => {
  // UI-6 Tenants, Namespaces and Audit task list, Phase 1 (decision 9).
  it('read and write the Tenants and Namespaces pages, a tenant and a namespace', () => {
    expect(routeOf('/ui/tenants', '/ui/')).toEqual({ view: 'tenants' });
    expect(routeOf('/ui/tenants/acme', '/ui/')).toEqual({ view: 'tenant', name: 'acme' });
    expect(routeOf('/ui/namespaces', '/ui/')).toEqual({ view: 'namespaces' });
    expect(routeOf('/ui/namespaces/acme/prod', '/ui/')).toEqual({ view: 'namespace', tenant: 'acme', name: 'prod' });
    for (const path of ['/ui/tenants/a/b', '/ui/namespaces/acme', '/ui/namespaces/acme/prod/x', '/ui/namespaces//prod']) {
      expect(routeOf(path, '/ui/'), path).toEqual({ view: 'unknown' });
    }
    expect(hrefOf({ view: 'tenants' }, '/ui/')).toBe('/ui/tenants');
    expect(hrefOf({ view: 'tenant', name: 'acme' }, '/ui/', DEFAULT_SCOPE, 'stale')).toBe('/ui/tenants/acme?consistency=stale');
    expect(hrefOf({ view: 'namespaces' }, '/ui/')).toBe('/ui/namespaces');
    expect(hrefOf({ view: 'namespace', tenant: 'acme', name: 'prod' }, '/ui/')).toBe('/ui/namespaces/acme/prod');
  });
});

describe('the Audit route', () => {
  // UI-6 Tenants, Namespaces and Audit task list, Phase 3: the filters in the query.
  it('reads and writes the Audit page with its filters', () => {
    expect(routeOf('/ui/audit', '/ui/')).toEqual({ view: 'audit', filter: {} });
    expect(routeOf('/ui/audit', '/ui/', '?accessor=a1&result=denied&route=%2Fv1%2Fkv&other=x&result2=y')).toEqual(
      { view: 'audit', filter: { accessor: 'a1', result: 'denied', route: '/v1/kv' } });
    expect(routeOf('/ui/audit', '/ui/', '?result=sometimes&accessor=')).toEqual({ view: 'audit', filter: {} });
    expect(hrefOf({ view: 'audit', filter: { requestId: 'req-1', tenant: 'acme' } }, '/ui/'))
      .toBe('/ui/audit?requestId=req-1&inTenant=acme');
  });
});
