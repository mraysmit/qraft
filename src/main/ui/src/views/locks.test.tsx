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
import type { AuthorizationCheck, EventQuery, QraftApi, ReadOptions, Scope } from '../api/client';
import { BOOTSTRAP, eventPage, fakeApi, heldKey, kvEntry, manualScheduler, qraftEvent, read, SESSION_A, SESSION_B,
  SESSIONS } from './testing';

// UI-6 Sessions, Locks and Watches task list, Phase 3: the Locks page (decisions 4 and 5), and a key acquired and
// released through a session on its entry's page (decision 9).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const TOKEN: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false, aclRead: false,
  aclWrite: false } };

function renderApp(api: QraftApi, access?: Access) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

const rows = (label: string) => Array.from(document.querySelectorAll(`table[aria-label="${label}"] tbody tr`))
  .map((row) => Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent ?? ''));

const press = (text: string) => fireEvent.click(screen.getByText(text, { selector: 'button, button span' }));
const pressed = async (text: string) => fireEvent.click(await screen.findByText(text, { selector: 'button, button span' }));
const outcome = () => screen.findByLabelText('Outcome');

/** An authorize answer allowing what `allowed` says, and nothing else. */
function allowing(allowed: (check: AuthorizationCheck) => boolean) {
  const asked: AuthorizationCheck[] = [];
  return {
    asked,
    authorize: (checks: AuthorizationCheck[]) => {
      asked.push(...checks);
      return read(checks.map((check) => ({ ...check, allow: allowed(check) })), null);
    },
  };
}

const keyWrite = (check: AuthorizationCheck) => check.resource === 'key' && check.access === 'write';
const sessionWriteOn = (node: string) => (check: AuthorizationCheck) =>
  check.resource === 'session' && check.access === 'write' && check.segment === node;

const LOCKS = () => read({
  Held: [heldKey(), heldKey({ Tenant: 'default', Namespace: 'default', Key: 'jobs/a', Session: SESSION_B, LockIndex: 1,
    ModifyIndex: 50 })],
  Delays: [{ Tenant: 'default', Namespace: 'default', Key: 'jobs/b', Until: '2026-11-17T12:00:12Z' }],
}, 51);

describe('the Locks page', () => {
  it('is in navigation after Sessions', async () => {
    at('/ui/nowhere');
    renderApp(fakeApi());

    const links = Array.from(document.querySelectorAll('nav[aria-label="Primary"] a')).map((link) => link.textContent);
    expect(links.indexOf('Locks')).toBe(links.indexOf('Sessions') + 1);
    fireEvent.click(await screen.findByText('Locks', { selector: 'nav[aria-label="Primary"] a' }));
    expect(await screen.findByText('app/leader', { selector: 'table a' })).toBeInTheDocument();
  });

  it('lists the held keys across scopes with their sessions, and the lock delays still running', async () => {
    at('/ui/locks');
    renderApp(fakeApi({ locks: LOCKS }));

    await screen.findByText('jobs/a', { selector: 'table a' });
    expect(rows('Held keys')).toEqual([
      ['app/leader', 'acme/prod', SESSION_A, '2', '43'],
      ['jobs/a', 'default/default', SESSION_B, '1', '50'],
    ]);
    expect(screen.getByText('app/leader', { selector: 'table a' }))
      .toHaveAttribute('href', '/ui/kv?tenant=acme&namespace=prod&path=app%2Fleader');
    expect(screen.getByText(SESSION_A, { selector: 'table a' })).toHaveAttribute('href', `/ui/sessions/${SESSION_A}`);
    expect(rows('Lock delays')).toEqual([['jobs/b', 'default/default', '2026-11-17 12:00:12 UTC', '12 s']]);
  });

  it('says the scope selected does not apply, that waiters are not visible, and reads in the consistency chosen', async () => {
    at('/ui/locks?tenant=acme&namespace=prod&consistency=stale');
    const asked: (ReadOptions | undefined)[] = [];
    renderApp(fakeApi({ locks: (_filter, options) => {
      asked.push(options);
      return LOCKS();
    } }));

    await screen.findByText('jobs/a', { selector: 'table a' });
    expect(screen.getByText('Locks are read across every tenant and namespace: the scope selected does not apply to them.'))
      .toBeInTheDocument();
    expect(screen.getByText('Who waits for a lock is not visible: a process waits with a blocking read of its own, which '
      + 'the server cannot count.')).toBeInTheDocument();
    expect(asked[0]).toMatchObject({ consistency: 'stale' });
  });

  it('says when no key is held, and when the server left some out', async () => {
    at('/ui/locks');
    renderApp(fakeApi({ locks: () => read({ Held: [], Delays: [] }, 51, { filtered: true }) }));

    expect(await screen.findByText('No key is held that your token may read.')).toBeInTheDocument();
    expect(screen.getByText('No lock delay is running.')).toBeInTheDocument();
    expect(screen.getByText('The server left out the keys your token may not read.')).toBeInTheDocument();
  });

  it('lists the recent invalidations with operator:read, and says why each session ended', async () => {
    at('/ui/locks');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([qraftEvent(3, { type: 'session.destroyed', tenant: undefined, namespace: undefined,
        event: { id: SESSION_B, reason: 'node' } })]), null);
    } }));

    await waitFor(() => expect(rows('Recent invalidations')).toEqual([
      [SESSION_B, 'Its node failed or left the catalog.', '2026-11-15 09:00:01 UTC'],
    ]));
    expect(asked[0]).toEqual({ limit: 1000, type: 'session.destroyed' });
  });

  it('lists no invalidation without operator:read, and says so', async () => {
    at('/ui/locks');
    const asked: EventQuery[] = [];
    renderApp(fakeApi({ events: (query) => {
      asked.push(query);
      return read(eventPage([]), null);
    } }), TOKEN);

    await screen.findByText('app/leader', { selector: 'table a' });
    await waitFor(() => expect(screen.getByText('Recent invalidations are listed with operator:read.')).toBeInTheDocument());
    expect(asked).toEqual([]);
  });
});

interface Locked {
  key: string;
  scope: Scope | undefined;
  value: string;
  at: { flags: number; session: string };
}

describe("a key's lock", () => {
  it('is acquired with key:write on a key no session holds, through a session chosen', async () => {
    at('/ui/kv?path=app%2Fconfig');
    const acquired: Locked[] = [];
    renderApp(fakeApi({ authorize: allowing(keyWrite).authorize, kvAcquire: (key, scope, value, written) => {
      acquired.push({ key, scope, value, at: written });
      return read(true, null);
    } }), TOKEN);

    await pressed('Acquire');
    await screen.findByText(`${SESSION_A} (worker, node-a)`, { selector: 'option' });
    fireEvent.change(screen.getByLabelText('Acquire with session'), { target: { value: SESSION_A } });
    press('Review');
    const review = await screen.findByLabelText('Acquire app/config');
    expect(review.textContent).toContain(`Acquired for session ${SESSION_A}, if no other session holds it and no lock delay runs.`);
    expect(review.textContent).toContain('Its value is written again as read at index 24');
    fireEvent.click(review.querySelector('button.ant-btn-primary')!);
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(acquired).toEqual([{ key: 'app/config', scope: { tenant: 'default', namespace: 'default' },
      value: '{"replicas":3}', at: { flags: 7, session: SESSION_A } }]);
  });

  it('shows a refused acquisition as the conflict it is, with the lock delay that runs', async () => {
    at('/ui/kv?path=app%2Fconfig');
    renderApp(fakeApi({
      authorize: allowing(keyWrite).authorize,
      kvAcquire: () => read(false, null),
      locks: () => read({ Held: [], Delays: [{ Tenant: 'default', Namespace: 'default', Key: 'app/config',
        Until: '2026-11-17T12:00:10Z' }] }, 51),
    }), TOKEN);

    await pressed('Acquire');
    await screen.findByText(`${SESSION_A} (worker, node-a)`, { selector: 'option' });
    fireEvent.change(screen.getByLabelText('Acquire with session'), { target: { value: SESSION_A } });
    press('Review');
    fireEvent.click((await screen.findByLabelText('Acquire app/config')).querySelector('button.ant-btn-primary')!);

    expect((await outcome()).textContent).toContain('Rejected: it conflicts with the current state');
    expect(await screen.findByText('Its lock delay runs until 2026-11-17 12:00:10 UTC.')).toBeInTheDocument();
  });

  it('is released only with the session that holds it, and session:write on its node', async () => {
    at('/ui/kv?path=app%2Fconfig');
    const released: Locked[] = [];
    const access = allowing((check) => keyWrite(check) || sessionWriteOn('node-a')(check));
    renderApp(fakeApi({
      authorize: access.authorize,
      kvEntry: () => read(kvEntry('app/config', '{"replicas":3}', { Flags: 7, Session: SESSION_A, LockIndex: 2 }), 30),
      kvRelease: (key, scope, value, written) => {
        released.push({ key, scope, value, at: written });
        return read(true, null);
      },
    }), TOKEN);

    await pressed('Release');
    const review = await screen.findByLabelText('Release app/config');
    expect(review.textContent).toContain(`Released by session ${SESSION_A}, which holds it`);
    fireEvent.click(review.querySelector('button.ant-btn-primary')!);
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(released).toEqual([{ key: 'app/config', scope: { tenant: 'default', namespace: 'default' },
      value: '{"replicas":3}', at: { flags: 7, session: SESSION_A } }]);
    expect(screen.queryByText('Acquire', { selector: 'button' })).toBeNull();
    expect(access.asked).toContainEqual({ resource: 'session', segment: 'node-a', access: 'write' });
  });

  it("is not released without session:write on the holding session's node", async () => {
    at('/ui/kv?path=app%2Fconfig');
    renderApp(fakeApi({
      authorize: allowing(keyWrite).authorize,
      kvEntry: () => read(kvEntry('app/config', '{"replicas":3}', { Session: SESSION_A, LockIndex: 2 }), 30),
    }), TOKEN);

    expect(await screen.findByText("Releasing it needs session:write on node node-a, the holding session's node."))
      .toBeInTheDocument();
    expect(screen.queryByText('Release', { selector: 'button' })).toBeNull();
  });

  it('is named in the review of an update or a deletion of a held key', async () => {
    at('/ui/kv?path=app%2Fconfig');
    renderApp(fakeApi({
      authorize: allowing(keyWrite).authorize,
      kvEntry: () => read(kvEntry('app/config', '{"replicas":3}', { Session: SESSION_A, LockIndex: 2 }), 30),
      sessions: () => read(SESSIONS(), 44),
    }), TOKEN);

    await pressed('Edit');
    press('Review');
    expect((await screen.findByLabelText('Update app/config')).textContent)
      .toContain(`It is held by session ${SESSION_A}: saving keeps the lock.`);
    press('Cancel');
    await pressed('Delete');
    expect((await screen.findByLabelText('Delete app/config')).textContent)
      .toContain(`It is held by session ${SESSION_A}: deleting the key ends the lock.`);
  });
});
