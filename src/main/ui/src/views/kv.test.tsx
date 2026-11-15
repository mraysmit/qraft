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
import { BOOTSTRAP, eventPage, fakeApi, kvEntry, manualScheduler, qraftEvent, read } from './testing';

// UI-6 Key/Value task list, Phase 1: the browser and an entry's page (UI/UX design section 7.1).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

function renderApp(api: ReturnType<typeof fakeApi>, access?: Access) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

/** The text of each row's first cell in the keys table, top to bottom. */
const rows = () => Array.from(document.querySelectorAll('table[aria-label="Keys"] tbody tr'))
  .map((row) => row.querySelector('td')?.textContent ?? '');

const crumbs = () => Array.from(document.querySelectorAll('nav[aria-label="Folders"] a'))
  .map((link) => [link.textContent, link.getAttribute('href')]);

const field = (label: string) => document.querySelector(`[data-field="${label}"]`)?.textContent ?? null;

const NO_OPERATOR: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false,
  aclRead: false, aclWrite: false } };

describe('the Key/Value browser', () => {
  it('is in navigation after Health Checks, and opens at the top folder', async () => {
    at('/ui/nowhere');
    renderApp(fakeApi());

    fireEvent.click(await screen.findByText('Key/Value', { selector: 'nav[aria-label="Primary"] a' }));

    expect(await screen.findByText('app/', { selector: 'table a' })).toHaveAttribute('href', '/ui/kv?path=app%2F');
    expect(rows()).toEqual(['app/', 'readme']);
    expect(screen.getByText('readme', { selector: 'table a' })).toHaveAttribute('href', '/ui/kv?path=readme');
  });

  it("lists a folder's folders and keys, with breadcrumbs, and never a value", async () => {
    at('/ui/kv?path=app%2F');
    renderApp(fakeApi());

    await screen.findByText('db/', { selector: 'table a' });
    expect(rows()).toEqual(['db/', 'config', 'name']);
    expect(crumbs()).toEqual([['Key/Value', '/ui/kv'], ['app/', '/ui/kv?path=app%2F']]);
    expect(document.body.textContent).not.toContain('shop');
  });

  it('reads the folder in the selected scope and consistency, and says what it read', async () => {
    at('/ui/kv?tenant=acme&namespace=prod&consistency=stale&path=app%2F');
    const asked: { prefix: string; scope: Scope | undefined; options: ReadOptions | undefined }[] = [];
    renderApp(fakeApi({
      kvLevel: (prefix, scope, options) => {
        asked.push({ prefix, scope, options });
        return read(['app/config'], 31, { consistency: 'stale' });
      },
    }));

    await screen.findByText('config', { selector: 'table a' });
    expect(asked[0]).toMatchObject({ prefix: 'app/', scope: { tenant: 'acme', namespace: 'prod' },
      options: { consistency: 'stale' } });
    expect(screen.getByText(/Applied index 31/)).toBeInTheDocument();
    expect(screen.getByText('Keys in tenant acme, namespace prod.')).toBeInTheDocument();
  });

  it('says a folder is empty, and that the server left out keys the token may not read', async () => {
    at('/ui/kv?path=app%2F');
    renderApp(fakeApi({ kvLevel: () => read([], 31, { filtered: true }) }));

    expect(await screen.findByText('No key is stored under app/ that your token may read.')).toBeInTheDocument();
    expect(screen.getByText('The server left out the keys your token may not read.')).toBeInTheDocument();
  });

  it('says the top folder holds no key', async () => {
    at('/ui/kv');
    renderApp(fakeApi({ kvLevel: () => read([], 31) }));

    expect(await screen.findByText('No key is stored in the default tenant and namespace.')).toBeInTheDocument();
    expect(screen.queryByText('The server left out the keys your token may not read.')).toBeNull();
  });
});

describe("an entry's page", () => {
  it('shows the value, laid out when it is JSON, with its size, flags, indexes and session', async () => {
    at('/ui/kv?path=app%2Fconfig');
    renderApp(fakeApi());

    expect(await screen.findByText('app/config', { selector: 'h2' })).toBeInTheDocument();
    expect(document.querySelector('pre[aria-label="Value"]')?.textContent).toBe('{\n  "replicas": 3\n}');
    expect(screen.getByText('Laid out as JSON; stored as written.')).toBeInTheDocument();
    expect(field('Scope')).toBe('default/default');
    expect(field('Size')).toBe('14 bytes');
    expect(field('Flags')).toBe('7');
    expect(field('Created at index')).toBe('20');
    expect(field('Modified at index')).toBe('24');
    expect(field('Lock index')).toBe('0');
    expect(field('Session')).toBe('None holds it');
    expect(crumbs()).toEqual([['Key/Value', '/ui/kv'], ['app/', '/ui/kv?path=app%2F']]);
  });

  it('shows a text value as it is, and names the session that holds it', async () => {
    at('/ui/kv?path=app%2Fname');
    renderApp(fakeApi({ kvEntry: () => read(kvEntry('app/name', 'shop', { Session: 'sess-1', LockIndex: 2 }), 31) }));

    expect(await screen.findByText('shop', { selector: 'pre[aria-label="Value"]' })).toBeInTheDocument();
    expect(screen.queryByText('Laid out as JSON; stored as written.')).toBeNull();
    expect(field('Session')).toBe('Held by sess-1');
    expect(field('Lock index')).toBe('2');
  });

  it('shows a binary value by its size and base64, and says flags it cannot show exactly', async () => {
    at('/ui/kv?path=bin');
    const base64 = btoa(String.fromCharCode(0xff, 0xfe, 0x00));
    renderApp(fakeApi({ kvEntry: () => read({ ...kvEntry('bin', ''), Value: base64,
      Flags: JSON.parse('18446744073709551615') as number }, 31) }));

    expect(await screen.findByText('Binary, not UTF-8 text: shown in base64.')).toBeInTheDocument();
    expect(document.querySelector('pre[aria-label="Value"]')?.textContent).toBe(base64);
    expect(field('Size')).toBe('3 bytes');
    expect(field('Flags')).toBe('Above 9007199254740991, which the browser cannot show exactly');
  });

  it('says a key that does not exist is not there, with its folder', async () => {
    at('/ui/kv?path=app%2Fgone');
    renderApp(fakeApi());

    expect(await screen.findByText('No key app/gone is stored in the default tenant and namespace.')).toBeInTheDocument();
    expect(crumbs()).toEqual([['Key/Value', '/ui/kv'], ['app/', '/ui/kv?path=app%2F']]);
  });

  it("lists the key's recent events from the answering server's journal", async () => {
    at('/ui/kv?path=app%2Fconfig');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({
      events: (query) => {
        asked.push(query);
        return read(eventPage([qraftEvent(1, { type: 'key.set', event: { key: 'app/config', created: true } })]), null);
      },
    }));

    await waitFor(() => expect(document.querySelector('[data-event-type]')?.textContent).toBe('key.set'));
    expect(asked[0]).toEqual({ limit: 1000, tenant: 'default', namespace: 'default', key: 'app/config' });
  });

  it('reads no event without operator:read, and says so', async () => {
    at('/ui/kv?path=app%2Fconfig');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([]), null);
    } }), NO_OPERATOR);

    expect(await screen.findByText("The key's events are listed with operator:read.")).toBeInTheDocument();
    expect(asked).toEqual([]);
  });
});
