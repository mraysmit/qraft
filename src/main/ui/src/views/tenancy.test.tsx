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

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { type Access, unrestricted } from '../access';
import type { EventQuery, ReadOptions, Scope } from '../api/client';
import { BOOTSTRAP, eventPage, fakeApi, heldKey, manualScheduler, qraftEvent, read, SESSION_A } from './testing';

// UI-6 Tenants, Namespaces and Audit task list, Phase 1: the grouped navigation (decision 8), and the Tenants and
// Namespaces pages (UI/UX design section 10.1).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const NO_OPERATOR: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false,
  aclRead: true, aclWrite: false } };

function renderApp(api: ReturnType<typeof fakeApi>, access?: Access) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

const rows = (label: string) => Array.from(document.querySelectorAll(`table[aria-label="${label}"] tbody tr`))
  .map((row) => Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent ?? ''));

const field = (label: string) => document.querySelector(`[data-field="${label}"]`)?.textContent ?? null;

/** The navigation bar as read: each group's name, then its links. */
const bar = () => Array.from(document.querySelectorAll('nav[aria-label="Primary"] [data-nav-group], nav[aria-label="Primary"] a'))
  .map((item) => (item.tagName === 'A' ? item.textContent : `[${item.textContent}]`));

describe('the navigation', () => {
  it("groups the links as the design's section 4.3 does, Govern before Access", async () => {
    renderApp(fakeApi());

    await screen.findByText('Services', { selector: 'nav[aria-label="Primary"] a' });
    expect(bar()).toEqual(['[Discover]', 'Services', 'Nodes and Clients', 'Health Checks',
      '[Coordinate]', 'Key/Value', 'Sessions', 'Locks', 'Watches',
      '[Operate]', 'Cluster Overview', 'Raft Members', 'Storage & Snapshots', 'Events', 'Configuration',
      '[Govern]', 'Tenants', 'Namespaces', 'Audit',
      '[Access]', 'Access']);
  });

  it('leaves out a group the token has no link in', async () => {
    renderApp(fakeApi(), NO_OPERATOR);

    await screen.findByText('Services', { selector: 'nav[aria-label="Primary"] a' });
    expect(bar()).toEqual(['[Discover]', 'Services', 'Nodes and Clients', 'Health Checks',
      '[Coordinate]', 'Key/Value', 'Sessions', 'Locks', 'Watches', '[Access]', 'Access']);
  });
});

describe('the Tenants page', () => {
  it('lists each tenant with its description, its namespaces and what they hold', async () => {
    at('/ui/tenants');
    renderApp(fakeApi());

    await screen.findByText('acme', { selector: 'table a' });
    expect(rows('Tenants')).toEqual([
      ['acme', 'Acme Ltd', '2', '2', '3', '3', '1'],
      ['default', '', '1', '1', '1', '4', '0'],
    ]);
    expect(screen.getByText('acme', { selector: 'table a' })).toHaveAttribute('href', '/ui/tenants/acme');
  });

  it('reads in the consistency chosen, and is not offered without operator:read', async () => {
    at('/ui/tenants?consistency=stale');
    const asked: (ReadOptions | undefined)[] = [];
    renderApp(fakeApi({ scopes: (options) => {
      asked.push(options);
      return read({ Tenants: [] }, 40);
    } }));

    expect(await screen.findByText('No tenant exists.')).toBeInTheDocument();
    expect(asked[0]).toMatchObject({ consistency: 'stale' });
  });

  it('says what the token lacks without operator:read', async () => {
    at('/ui/tenants');
    renderApp(fakeApi(), NO_OPERATOR);

    expect(await screen.findByText('Your token may not read Tenants: it needs operator:read.')).toBeInTheDocument();
  });
});

describe("a tenant's page", () => {
  it('shows its fields, its namespaces with what they hold, and its recent events', async () => {
    at('/ui/tenants/acme');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([qraftEvent(1, { type: 'tenant.created', tenant: 'acme', event: { name: 'acme' } })]), null);
    } }));

    expect(await screen.findByText('Tenant acme', { selector: 'h2' })).toBeInTheDocument();
    expect(field('Description')).toBe('Acme Ltd');
    expect(field('Created at index')).toBe('4');
    expect(rows('Namespaces')).toEqual([
      ['default', '', '0', '0', '0', '0'],
      ['prod', 'production', '2', '3', '3', '1'],
    ]);
    expect(screen.getByText('prod', { selector: 'table a' })).toHaveAttribute('href', '/ui/namespaces/acme/prod');
    await waitFor(() => expect(document.querySelector('[data-event-type]')?.textContent).toBe('tenant.created'));
    expect(asked[0]).toEqual({ limit: 1000, tenant: 'acme' });
  });

  it('says a tenant that does not exist is not there', async () => {
    at('/ui/tenants/gone');
    renderApp(fakeApi());

    expect(await screen.findByText('No tenant gone exists.')).toBeInTheDocument();
  });
});

describe('the Namespaces page', () => {
  it('lists every namespace of every tenant with what it holds', async () => {
    at('/ui/namespaces');
    renderApp(fakeApi());

    await screen.findByText('prod', { selector: 'table a' });
    expect(rows('Namespaces')).toEqual([
      ['default', 'acme', '', '0', '0', '0', '0'],
      ['prod', 'acme', 'production', '2', '3', '3', '1'],
      ['default', 'default', '', '1', '1', '4', '0'],
    ]);
  });
});

describe("a namespace's page", () => {
  it('shows what it holds, its services and held keys linked in its scope, and its recent events', async () => {
    at('/ui/namespaces/acme/prod');
    const summaries: (Scope | undefined)[] = [];
    const asked: EventQuery[] = [];
    renderApp(fakeApi({
      servicesSummary: (scope) => {
        summaries.push(scope);
        return read([{ name: 'web', tenantId: 'acme', namespace: 'prod', tags: [], instanceCount: 2,
          health: { unknown: 0, passing: 2, warning: 0, critical: 0, maintenance: 0 }, datacenters: [], regions: [],
          modifyIndex: 9 }]);
      },
      locks: () => read({ Held: [heldKey(), heldKey({ Tenant: 'default', Namespace: 'default', Key: 'other' })],
        Delays: [] }, 43),
      events: (query) => {
        asked.push(query);
        return read(eventPage([]), null);
      },
    }));

    expect(await screen.findByText('Namespace acme/prod', { selector: 'h2' })).toBeInTheDocument();
    expect(field('Description')).toBe('production');
    expect(field('Keys')).toBe('3');
    await waitFor(() => expect(rows('Services')).toEqual([['web', '2']]));
    expect(screen.getByText('web', { selector: 'table a' })).toHaveAttribute('href',
      '/ui/services/web?tenant=acme&namespace=prod');
    expect(summaries[0]).toEqual({ tenant: 'acme', namespace: 'prod' });
    await waitFor(() => expect(rows('Held keys')).toEqual([['app/leader', SESSION_A]]));
    expect(screen.getByText('app/leader', { selector: 'table a' })).toHaveAttribute('href',
      '/ui/kv?tenant=acme&namespace=prod&path=app%2Fleader');
    await waitFor(() => expect(asked[0]).toEqual({ limit: 1000, tenant: 'acme', namespace: 'prod' }));
  });

  it('opens from the Namespaces page', async () => {
    at('/ui/namespaces');
    renderApp(fakeApi());

    fireEvent.click(await screen.findByText('prod', { selector: 'table a' }));
    expect(await screen.findByText('Namespace acme/prod', { selector: 'h2' })).toBeInTheDocument();
  });
});
