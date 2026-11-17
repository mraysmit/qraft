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

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { type Access, unrestricted } from '../access';
import { cursor } from '../api/fixtures';
import type { QraftApi, Read, ReadOptions } from '../api/client';
import { MAX_WATCH_ROWS, withRow, type WatchRow } from './watchLog';
import { BOOTSTRAP, entry, eventPage, fakeApi, kvEntry, manualScheduler, qraftEvent, read, SESSIONS, sessionEntry,
  unchanged, watchRecorder } from './testing';

// UI-6 Sessions, Locks and Watches task list, Phase 4: the Watches page (UI/UX design section 7.3; decision 10).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const NO_OPERATOR: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false,
  aclRead: false, aclWrite: false } };

function renderApp(api: QraftApi, access?: Access) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

/** The watch log's rows, newest first: what kind of row, its index, and what it says. */
const log = () => Array.from(document.querySelectorAll('table[aria-label="Watch log"] tbody tr'))
  .map((row) => Array.from(row.querySelectorAll('td')).slice(1).map((cell) => cell.textContent ?? ''));

/** A blocking read that answers each of `answers` in turn, by the index it waits past, and then waits. */
function answering<T>(first: Read<T>, after: Record<number, Read<T>>) {
  return (options: ReadOptions | undefined): Promise<Read<T>> => {
    if (options?.index === undefined) return Promise.resolve(first);
    const next = after[options.index];
    return next === undefined ? unchanged(options.signal) : Promise.resolve(next);
  };
}

const answer = async <T,>(value: T, index: number) => read(value, index);

describe('the Watches page', () => {
  it('is in navigation after Locks, and says a watch is the tab\'s own', async () => {
    at('/ui/nowhere');
    renderApp(fakeApi());

    const links = Array.from(document.querySelectorAll('nav[aria-label="Primary"] a')).map((link) => link.textContent);
    expect(links.indexOf('Watches')).toBe(links.indexOf('Locks') + 1);
    fireEvent.click(await screen.findByText('Watches', { selector: 'nav[aria-label="Primary"] a' }));
    expect(await screen.findByText('A watch runs in this tab alone: the server keeps no registration of it, and it ends '
      + 'when the tab closes or another watch starts.')).toBeInTheDocument();
  });

  it('starts a watch on a key, puts it in the address, and logs its first delivery without the value', async () => {
    at('/ui/watches');
    renderApp(fakeApi());

    fireEvent.change(await screen.findByLabelText('Watch'), { target: { value: 'key' } });
    fireEvent.change(screen.getByLabelText('Watched name'), { target: { value: 'app/name' } });
    fireEvent.click(screen.getByText('Start watching', { selector: 'button' }));

    expect(window.location.pathname + window.location.search).toBe('/ui/watches?target=key&name=app%2Fname');
    await waitFor(() => expect(log()).toEqual([
      ['Delivered', '30', 'Modified at index 24; 4 bytes; no session holds it.'],
      ['Started', '—', 'Watching the key app/name in the default tenant and namespace, at default consistency.'],
    ]));
    expect(document.body.textContent).not.toContain('shop');
  });

  it("logs each change of a key with its index, and an index that went back as a resynchronization", async () => {
    at('/ui/watches?target=key&name=app%2Fname&consistency=stale');
    const asked: (ReadOptions | undefined)[] = [];
    const held = read(kvEntry('app/name', 'shop', { ModifyIndex: 31, Session: 's-1', LockIndex: 1 }), 31);
    const reads = answering(await answer(kvEntry('app/name', 'shop'), 30), { 30: await held, 31: await read(null, 12) });
    renderApp({ ...fakeApi(), kvEntry: (_key, _scope, options) => {
      asked.push(options);
      return reads(options);
    } });

    await waitFor(() => expect(log().map((row) => row[0])).toEqual(['Delivered', 'Resynchronized', 'Delivered',
      'Delivered', 'Started']));
    expect(log()[0]).toEqual(['Delivered', '12', 'The key does not exist.']);
    expect(log()[1]).toEqual(['Resynchronized', '12', 'The index went back from 31 to 12: the state was read afresh, '
      + 'and the watch resumed from the new index.']);
    expect(log()[2]).toEqual(['Delivered', '31', 'Modified at index 31; 4 bytes; held by session s-1.']);
    expect(asked[0]).toMatchObject({ consistency: 'stale' });
    expect(asked[1]).toMatchObject({ index: 30, wait: '30s' });
  });

  it('watches a prefix, and says which keys came and went', async () => {
    at('/ui/watches?target=prefix&name=app%2F');
    const reads = answering(await answer(['app/a', 'app/b'], 30), { 30: await read(['app/b', 'app/c'], 31) });
    renderApp({ ...fakeApi(), kvKeys: (_prefix, _scope, options) => reads(options) });

    await waitFor(() => expect(log()[0]).toEqual(['Delivered', '31', '2 keys under app/; added app/c; removed app/a.']));
    expect(log()[1]).toEqual(['Delivered', '30', '2 keys under app/.']);
  });

  it("watches a service's health in the selected scope", async () => {
    at('/ui/watches?target=service&name=web&tenant=acme&namespace=prod');
    renderApp({ ...fakeApi(), serviceHealth: () => read([entry(), entry({ serviceId: 'web-2', health: 'CRITICAL' })], 40) });

    await waitFor(() => expect(log()[0]).toEqual(['Delivered', '40', '2 instances: 1 critical, 1 passing.']));
    expect(log()[1]?.[2]).toBe('Watching the service web in tenant acme, namespace prod, at default consistency.');
  });

  it('watches the sessions, and says which came and went', async () => {
    at('/ui/watches?target=sessions');
    const later = { Sessions: [SESSIONS().Sessions[1]!, sessionEntry({ ID: 'new-session' })], TtlKnown: true };
    const reads = answering(await answer(SESSIONS(), 44), { 44: await read(later, 45) });
    renderApp({ ...fakeApi(), sessions: (options) => reads(options) });

    await waitFor(() => expect(log()[0]?.[2]).toBe(
      '2 sessions; created new-session; ended 4f3c2a10-0000-4000-8000-000000000001.'));
    expect(log()[2]?.[2]).toBe('Watching the sessions, which belong to no scope, at default consistency.');
  });

  it("watches the event stream, logging each event once and a history gap as a resynchronization", async () => {
    at('/ui/watches?target=events');
    const recorder = watchRecorder();
    let pages = 0;
    renderApp({ ...fakeApi(), watchEvents: recorder.watchEvents, events: () => {
      pages += 1;
      return read(eventPage(pages === 1 ? [qraftEvent(1)] : [qraftEvent(1), qraftEvent(2), qraftEvent(3)]), null);
    } });

    await waitFor(() => expect(recorder.watches).toHaveLength(1));
    act(() => recorder.watches[0]!.handlers.onEvent(qraftEvent(2, { type: 'key.set', event: { key: 'a', created: true } })));
    act(() => recorder.watches[0]!.handlers.onEnd({ reason: 'history_gap', resumeAfter: cursor(2) }));

    await waitFor(() => expect(log().map((row) => row[0])).toEqual(['Delivered', 'Resynchronized', 'Delivered',
      'Delivered', 'Started']));
    expect(log()[0]).toEqual(['Delivered', '3', 'service.registered']);
    expect(log()[1]?.[2]).toBe('Events were missed (history_gap): the journal was read again from its start, and the '
      + 'watch resumed after its last event.');
    expect(log()[2]).toEqual(['Delivered', '2', 'key.set']);
    expect(log()[3]).toEqual(['Delivered', '—', 'Read the journal: 1 event.']);
  });

  it('logs an overflow and another boot as resynchronizations of their own', async () => {
    at('/ui/watches?target=events');
    const recorder = watchRecorder();
    renderApp({ ...fakeApi(), watchEvents: recorder.watchEvents, events: () => read(eventPage([qraftEvent(1)]), null) });

    await waitFor(() => expect(recorder.watches).toHaveLength(1));
    act(() => recorder.watches[0]!.handlers.onEnd({ reason: 'overflow', resumeAfter: cursor(1) }));
    await waitFor(() => expect(recorder.watches).toHaveLength(2));
    act(() => recorder.watches[1]!.handlers.onEnd({ reason: 'other_boot', resumeAfter: cursor(1) }));
    await waitFor(() => expect(recorder.watches).toHaveLength(3));

    expect(log().filter((row) => row[0] === 'Resynchronized').map((row) => row[2])).toEqual([
      'The server restarted (other_boot): the journal was read again from its start, and the watch resumed after its '
        + 'last event.',
      'The watch fell behind (overflow): it resumed after the last event it delivered.',
    ]);
  });

  it('watches no event without operator:read, and says so', async () => {
    at('/ui/watches?target=events');
    const recorder = watchRecorder();
    renderApp({ ...fakeApi(), watchEvents: recorder.watchEvents }, NO_OPERATOR);

    expect(await screen.findByText('The event stream is watched with operator:read.')).toBeInTheDocument();
    expect(recorder.watches).toHaveLength(0);
  });

  it('says a watch delivers nothing while live updates are paused', async () => {
    at('/ui/watches?target=key&name=app%2Fname');
    renderApp(fakeApi());

    await waitFor(() => expect(log()).toHaveLength(2));
    fireEvent.click(screen.getByText('Pause live updates', { selector: 'button' }));
    expect(await screen.findByText('Live updates are paused: the watch delivers nothing until they resume.'))
      .toBeInTheDocument();
  });

  it('asks for a name before watching a key or a service', async () => {
    at('/ui/watches');
    renderApp(fakeApi());

    fireEvent.change(await screen.findByLabelText('Watch'), { target: { value: 'service' } });
    fireEvent.click(screen.getByText('Start watching', { selector: 'button' }));

    expect(screen.getByText('Name the service to watch.')).toBeInTheDocument();
    expect(window.location.search).toBe('');
  });
});

describe('the watch log', () => {
  it('keeps the newest rows first, and at most its limit', () => {
    let rows: WatchRow[] = [];
    for (let at = 0; at < MAX_WATCH_ROWS + 3; at += 1) {
      rows = withRow(rows, { id: at, at, index: at, kind: 'delivery', text: String(at) });
    }
    expect(rows).toHaveLength(MAX_WATCH_ROWS);
    expect(rows[0]?.text).toBe(String(MAX_WATCH_ROWS + 2));
    expect(rows.at(-1)?.text).toBe('3');
  });
});
