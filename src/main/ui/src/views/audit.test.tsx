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
import type { AuditQuery, EventQuery } from '../api/client';
import type { Audit } from '../api/schemas';
import { BOOTSTRAP, eventPage, fakeApi, manualScheduler, qraftEvent, read } from './testing';

// UI-6 Tenants, Namespaces and Audit task list, Phase 3: the Audit page (UI/UX design section 10.3; decisions 5 to 7).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const NO_OPERATOR: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false,
  aclRead: false, aclWrite: false } };

function renderApp(api: ReturnType<typeof fakeApi>, access?: Access) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

const rows = () => Array.from(document.querySelectorAll('table[aria-label="Audit records"] tbody tr'))
  .map((row) => Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent ?? ''));

const request = (fields: Partial<Audit['Records'][number]>) => ({ Type: 'audit.http_request', Timestamp: '2026-11-17T10:00:05Z',
  Node: 's1', AccessorId: 'a2', Method: 'GET', Route: '/v1/acl/tokens', Status: 200, Tenant: 'default',
  Namespace: 'default', RequestId: 'r5', ...fields });

const FILE: Audit = { Configured: true, File: '/data/qraft-audit.json', Size: 2048, Scanned: 2048, Complete: true,
  Records: [
    request({}),
    request({ Timestamp: '2026-11-17T10:00:04Z', AccessorId: 'a1', Method: 'DELETE', Route: '/v1/tenant', Status: 500,
      Tenant: 'acme', RequestId: 'r4' }),
    { Type: 'audit.unreadable_line' },
    request({ Timestamp: '2026-11-17T10:00:02Z', Method: 'PUT', Route: '/v1/acl/token', Status: 403, RequestId: 'r2' }),
    { Type: 'audit.events_lost' },
    request({ Timestamp: '2026-11-17T10:00:00Z', AccessorId: 'a1', Route: '/v1/kv', Tenant: null, Namespace: null,
      RequestId: 'r1' }),
  ] };

describe('the Audit page', () => {
  it('is in navigation after Namespaces', async () => {
    renderApp(fakeApi());

    const links = Array.from(document.querySelectorAll('nav[aria-label="Primary"] a')).map((link) => link.textContent);
    await waitFor(() => expect(links.indexOf('Audit')).toBe(links.indexOf('Namespaces') + 1));
  });

  it('lists the records newest first, with the lost stretch and the unreadable line as what they are', async () => {
    at('/ui/audit');
    renderApp(fakeApi({ audit: () => read(FILE, null) }));

    await waitFor(() => expect(rows()).toEqual([
      ['2026-11-17 10:00:05 UTC', 'a2', 'default/default', 'r5', 'GET /v1/acl/tokens', '200', 'Allowed'],
      ['2026-11-17 10:00:04 UTC', 'a1', 'acme/default', 'r4', 'DELETE /v1/tenant', '500', 'Failed'],
      ['A line here could not be read.'],
      ['2026-11-17 10:00:02 UTC', 'a2', 'default/default', 'r2', 'PUT /v1/acl/token', '403', 'Denied'],
      ['Records were lost here: the audit file fell behind the journal.'],
      ['2026-11-17 10:00:00 UTC', 'a1', 'Not recorded', 'r1', 'GET /v1/kv', '200', 'Allowed'],
    ]));
    expect(screen.getByText('r5', { selector: 'table a' })).toHaveAttribute('href',
      '/ui/events?category=audit&correlation=r5');
  });

  it('states the durability and retention, what was read, and that there is no committed index', async () => {
    at('/ui/audit');
    renderApp(fakeApi({ audit: () => read({ ...FILE, Scanned: 1024, Complete: false }, null) }));

    expect(await screen.findByText('Audit records are kept best effort, on each server for the requests it answered, '
      + 'in /data/qraft-audit.json, until an operator removes or rotates the file. A record carries no committed index.'))
      .toBeInTheDocument();
    expect(screen.getByText('Read 1.0 kB of the file\'s 2.0 kB from its end; older records were not searched.'))
      .toBeInTheDocument();
  });

  it('reads the filters the address names, and puts the ones chosen in the address', async () => {
    at('/ui/audit?accessor=a1&result=denied');
    const asked: AuditQuery[] = [];
    renderApp(fakeApi({ audit: (query) => {
      asked.push(query);
      return read({ ...FILE, Records: [] }, null);
    } }));

    expect(await screen.findByText('No record matches.')).toBeInTheDocument();
    expect(asked[0]).toEqual({ accessor: 'a1', result: 'denied' });
    expect((screen.getByLabelText('Principal') as HTMLInputElement).value).toBe('a1');
    fireEvent.change(screen.getByLabelText('Principal'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Tenant'), { target: { value: 'acme' } });
    fireEvent.change(screen.getByLabelText('Result'), { target: { value: '' } });
    fireEvent.click(screen.getByText('Apply', { selector: 'button' }));
    expect(window.location.search).toBe('?inTenant=acme');
    await waitFor(() => expect(asked.at(-1)).toEqual({ tenant: 'acme' }));
  });

  it('reads older records before the oldest one shown', async () => {
    at('/ui/audit?accessor=a1');
    const asked: AuditQuery[] = [];
    renderApp(fakeApi({ audit: (query) => {
      asked.push(query);
      return read({ ...FILE, Complete: false }, null);
    } }));

    fireEvent.click(await screen.findByText('Older records', { selector: 'button' }));
    expect(window.location.search).toBe('?accessor=a1&before=2026-11-17T10%3A00%3A00Z');
    await waitFor(() => expect(asked.at(-1)).toEqual({ accessor: 'a1', before: '2026-11-17T10:00:00Z' }));
  });

  it('says a server writes no audit file, and lists its bounded journal instead, labelled as such', async () => {
    at('/ui/audit');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({
      audit: () => read({ Configured: false, File: null, Size: 0, Scanned: 0, Complete: true, Records: [] }, null),
      events: (query) => {
        asked.push(query);
        return read(eventPage([qraftEvent(1, { category: 'audit', type: 'audit.http_request',
          event: { accessorId: 'a1', method: 'GET', route: '/v1/kv', status: 200 } })]), null);
      },
    }));

    expect(await screen.findByText('This server writes no audit file: set server.audit.path to keep one. Below is its '
      + 'bounded journal, which holds only its most recent events and is lost when it restarts: it is not a complete '
      + 'record.')).toBeInTheDocument();
    await waitFor(() => expect(asked[0]).toEqual({ limit: 1000, category: 'audit' }));
    expect(document.querySelector('table[aria-label="Audit records"]')).toBeNull();
  });

  it('says what the token lacks without operator:read', async () => {
    at('/ui/audit');
    renderApp(fakeApi(), NO_OPERATOR);

    expect(await screen.findByText('Your token may not read Audit: it needs operator:read.')).toBeInTheDocument();
  });
});
