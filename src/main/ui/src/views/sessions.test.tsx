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
import type { EventQuery, ReadOptions } from '../api/client';
import type { LocksFilter } from '../api/sessions';
import { BOOTSTRAP, eventPage, fakeApi, heldKey, manualScheduler, qraftEvent, read, SESSION_A, SESSION_B,
  sessionEntry } from './testing';

// UI-6 Sessions, Locks and Watches task list, Phase 1: the Sessions page and a session's page (UI/UX design section 7.2).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const NO_OPERATOR: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false,
  aclRead: false, aclWrite: false } };

function renderApp(api: ReturnType<typeof fakeApi>, access?: Access, clock = manualScheduler()) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={clock.scheduler} access={access} />);
  return clock;
}

/** The cells of each row of the table labelled `label`, top to bottom. */
const rows = (label: string) => Array.from(document.querySelectorAll(`table[aria-label="${label}"] tbody tr`))
  .map((row) => Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent ?? ''));

const field = (label: string) => document.querySelector(`[data-field="${label}"]`)?.textContent ?? null;

describe('the Sessions page', () => {
  it('is in navigation after Key/Value', async () => {
    at('/ui/nowhere');
    renderApp(fakeApi());

    const links = Array.from(document.querySelectorAll('nav[aria-label="Primary"] a')).map((link) => link.textContent);
    expect(links.indexOf('Sessions')).toBe(links.indexOf('Key/Value') + 1);
    fireEvent.click(await screen.findByText('Sessions', { selector: 'nav[aria-label="Primary"] a' }));
    expect(await screen.findByText(SESSION_A, { selector: 'table a' })).toHaveAttribute('href', `/ui/sessions/${SESSION_A}`);
  });

  it('lists each session with its node, behaviour, TTL, lock delay, time left and the keys it holds', async () => {
    at('/ui/sessions');
    renderApp(fakeApi());

    await screen.findByText(SESSION_A, { selector: 'table a' });
    // The keys held come from the locks read, which may answer after the sessions read.
    await waitFor(() => expect(rows('Sessions')).toEqual([
      [SESSION_A, 'worker', 'node-a', 'Release its keys', '30s', '15 s', '25 s', '1', '40'],
      [SESSION_B, '', 'node-b', 'Delete its keys', 'None', 'None', 'No TTL', '0', '41'],
    ]));
  });

  it('counts the time left down on the interface clock', async () => {
    at('/ui/sessions');
    const clock = renderApp(fakeApi());

    await screen.findByText('25 s');
    act(() => {
      clock.advance(5_000);
      clock.fire();
    });
    expect(await screen.findByText('20 s')).toBeInTheDocument();
  });

  it('says sessions are not scoped, and reads them in the consistency chosen', async () => {
    at('/ui/sessions?tenant=acme&namespace=prod&consistency=stale');
    const asked: (ReadOptions | undefined)[] = [];
    renderApp(fakeApi({ sessions: (options) => {
      asked.push(options);
      return read({ Sessions: [sessionEntry()], TtlKnown: true }, 44, { consistency: 'stale' });
    } }));

    await screen.findByText(SESSION_A, { selector: 'table a' });
    expect(screen.getByText('Sessions belong to no tenant or namespace: the scope selected does not apply to them.'))
      .toBeInTheDocument();
    expect(asked[0]).toMatchObject({ consistency: 'stale' });
    expect(screen.getByText(/Applied index 44/)).toBeInTheDocument();
  });

  it('says a server that does not lead cannot tell when sessions lapse', async () => {
    at('/ui/sessions');
    renderApp(fakeApi({ sessions: () => read({ Sessions: [sessionEntry({ ExpiresAt: null })], TtlKnown: false }, 44) }));

    expect(await screen.findByText('This server does not lead, so it cannot say when sessions lapse: only the leader '
      + 'keeps their timers.')).toBeInTheDocument();
    expect(rows('Sessions')[0]?.[6]).toBe('Known to the leader');
  });

  it("filters by the session's node", async () => {
    at('/ui/sessions');
    renderApp(fakeApi());

    await screen.findByText(SESSION_A, { selector: 'table a' });
    fireEvent.change(screen.getByLabelText('Node'), { target: { value: 'node-b' } });
    expect(rows('Sessions').map((row) => row[0])).toEqual([SESSION_B]);
  });

  it('says when there is none, and when the server left some out', async () => {
    at('/ui/sessions');
    renderApp(fakeApi({ sessions: () => read({ Sessions: [], TtlKnown: true }, 44, { filtered: true }) }));

    expect(await screen.findByText('No session exists that your token may read.')).toBeInTheDocument();
    expect(screen.getByText('The server left out the sessions your token may not read.')).toBeInTheDocument();
  });
});

describe("a session's page", () => {
  it('shows its fields, when it lapses, and the keys it holds, each linked in its scope', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const asked: (LocksFilter | undefined)[] = [];
    renderApp(fakeApi({ locks: (filter) => {
      asked.push(filter);
      return read({ Held: [heldKey()], Delays: [] }, 43);
    } }));

    expect(await screen.findByText('Session worker', { selector: 'h2' })).toBeInTheDocument();
    expect(field('ID')).toBe(SESSION_A);
    expect(field('Node')).toBe('node-a');
    expect(field('Behaviour')).toBe('Release its keys');
    expect(field('TTL')).toBe('30s');
    expect(field('Lock delay')).toBe('15 s');
    expect(field('Created at index')).toBe('40');
    expect(field('Last renewed')).toBe('Not renewed since this leader was elected');
    expect(field('Lapses')).toBe('2026-11-17 12:00:25 UTC, in 25 s unless renewed');
    await waitFor(() => expect(rows('Held keys')).toEqual([['app/leader', 'acme/prod', '2', '43']]));
    expect(screen.getByText('app/leader', { selector: 'table a' }))
      .toHaveAttribute('href', '/ui/kv?tenant=acme&namespace=prod&path=app%2Fleader');
    expect(asked[0]).toEqual({ session: SESSION_A });
  });

  it("lists the session's recent events from the answering server's journal", async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([qraftEvent(1, { type: 'session.created', tenant: undefined, namespace: undefined,
        event: { id: SESSION_A, node: 'node-a' } })]), null);
    } }));

    await waitFor(() => expect(document.querySelector('[data-event-type]')?.textContent).toBe('session.created'));
    expect(asked[0]).toEqual({ limit: 1000, session: SESSION_A });
  });

  it('reads no event without operator:read, and says so', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([]), null);
    } }), NO_OPERATOR);

    await screen.findByText('Session worker', { selector: 'h2' });
    // Ant Design's Space keys its items by position, so the line is mounted again as the fields above it appear.
    await waitFor(() => expect(screen.getByText("The session's events are listed with operator:read.")).toBeInTheDocument());
    expect(asked).toEqual([]);
  });

  it('says a session that no longer exists has ended, and why, when the journal holds its end', async () => {
    const gone = '0dd00000-0000-4000-8000-000000000009';
    at(`/ui/sessions/${gone}`);
    renderApp(fakeApi({ events: () => read(eventPage([qraftEvent(5, { type: 'session.destroyed', tenant: undefined,
      namespace: undefined, event: { id: gone, reason: 'ttl' } })]), null) }));

    expect(await screen.findByText(`No session ${gone} exists: it has ended, or never existed.`)).toBeInTheDocument();
    expect(await screen.findByText('Its TTL lapsed without a renewal.')).toBeInTheDocument();
  });
});
