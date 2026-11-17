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
import { ApiError, type AuthorizationCheck, type QraftApi, type SessionBody } from '../api/client';
import { BOOTSTRAP, fakeApi, manualScheduler, node, read, SESSION_A, SESSION_B } from './testing';

// UI-6 Sessions, Locks and Watches task list, Phase 2: creating, renewing and destroying sessions through the mutation
// workflow, each behind session:write on the session's node (decisions 7 and 8).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const TOKEN: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false, aclRead: false,
  aclWrite: false } };

function renderApp(api: QraftApi, access: Access = TOKEN) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

/** An authorize answer allowing `session:write` on the nodes given, and nothing else. */
function writing(...nodes: string[]) {
  const asked: AuthorizationCheck[] = [];
  const authorize = (checks: AuthorizationCheck[]) => {
    asked.push(...checks);
    return read(checks.map((check) => ({ ...check, allow: check.resource === 'session' && check.access === 'write'
      && nodes.includes(check.segment ?? '') })), null);
  };
  return { asked, authorize };
}

const NODES = () => read([node({ name: 'node-a' }), node({ name: 'node-b' })]);
const NEW_SESSION = '7e570000-0000-4000-8000-000000000003';

const press = (text: string) => fireEvent.click(screen.getByText(text, { selector: 'button, button span' }));
const pressed = async (text: string) => fireEvent.click(await screen.findByText(text, { selector: 'button, button span' }));
const type = (label: string, text: string) => fireEvent.change(screen.getByLabelText(label), { target: { value: text } });
const outcome = () => screen.findByLabelText('Outcome');

describe('creating a session', () => {
  it('needs session:write on the node chosen', async () => {
    at('/ui/sessions');
    const access = writing('node-a');
    renderApp(fakeApi({ nodes: NODES, authorize: access.authorize }));

    await pressed('Create a session');
    await screen.findByText('node-b', { selector: 'option' });
    type('Bound to node', 'node-b');
    expect(await screen.findByText('Your token may not create a session on node-b: it needs session:write.'))
      .toBeInTheDocument();
    press('Review');
    expect(screen.queryByLabelText('Create a session on node-b')).toBeNull();
    expect(access.asked).toContainEqual({ resource: 'session', segment: 'node-b', access: 'write' });
  });

  it('reviews the session, creates it once, and links to it', async () => {
    at('/ui/sessions');
    const created: SessionBody[] = [];
    renderApp(fakeApi({ nodes: NODES, authorize: writing('node-a').authorize, createSession: (body) => {
      created.push(body);
      return read({ ID: NEW_SESSION }, null, { requestId: 'req-7' });
    } }));

    await pressed('Create a session');
    await screen.findByText('node-a', { selector: 'option' });
    type('Bound to node', 'node-a');
    type('Name', 'job');
    type('TTL', '30s');
    type('Lock delay', '5s');
    type('Behaviour', 'delete');
    await waitFor(() => expect(screen.queryByText(/Your token may not create/)).toBeNull());
    press('Review');

    const review = await screen.findByLabelText('Create a session on node-a');
    expect(review.textContent).toContain('It lapses unless renewed within twice its TTL, 60 s; the interface does not renew it.');
    expect(review.textContent).toContain('When it ends, the keys it holds are deleted.');
    press('Create');
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(created).toEqual([{ Node: 'node-a', Name: 'job', TTL: '30s', LockDelay: '5s', Behavior: 'delete' }]);
    expect(screen.getByText('Open the session', { selector: 'a' })).toHaveAttribute('href', `/ui/sessions/${NEW_SESSION}`);
  });

  it('never creates again by itself after an unknown outcome', async () => {
    at('/ui/sessions');
    let sent = 0;
    renderApp(fakeApi({ nodes: NODES, authorize: writing('node-a').authorize, createSession: () => {
      sent += 1;
      return Promise.reject(new ApiError('/v1/session/create', 0, 'outcome_unknown', 'No answer came', false, null, null));
    } }));

    await pressed('Create a session');
    await screen.findByText('node-a', { selector: 'option' });
    type('Bound to node', 'node-a');
    await waitFor(() => expect(screen.queryByText(/Your token may not create/)).toBeNull());
    press('Review');
    await pressed('Create');

    expect((await outcome()).textContent).toContain('Outcome unknown');
    expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
    expect(sent).toBe(1);
  });

  it('refuses what the server would refuse, before the review', async () => {
    at('/ui/sessions');
    renderApp(fakeApi({ nodes: NODES, authorize: writing('node-a').authorize }));

    await pressed('Create a session');
    type('TTL', '5s');
    type('Lock delay', '2m');
    press('Review');

    expect(screen.getByText('Choose the node the session is bound to.')).toBeInTheDocument();
    expect(screen.getByText('A TTL is a duration from 10s to 24h, such as 30s, or none.')).toBeInTheDocument();
    expect(screen.getByText('A lock delay is a duration from 0s to 60s, such as 15s.')).toBeInTheDocument();
    expect(screen.queryByText('Create', { selector: 'button, button span' })).toBeNull();
  });
});

describe("a session's changes", () => {
  it('are offered only with session:write on its node', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const none = writing();
    renderApp(fakeApi({ authorize: none.authorize }));

    expect(await screen.findByText('Your token may not change this session: it needs session:write on node node-a.'))
      .toBeInTheDocument();
    expect(screen.queryByText('Renew', { selector: 'button' })).toBeNull();
    expect(screen.queryByText('Destroy', { selector: 'button' })).toBeNull();
    expect(none.asked).toEqual([{ resource: 'session', segment: 'node-a', access: 'write' }]);
  });

  it('renew a session with a TTL', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const renewed: string[] = [];
    renderApp(fakeApi({ authorize: writing('node-a').authorize, renewSession: (id) => {
      renewed.push(id);
      return read([], null);
    } }));

    await pressed('Renew');
    expect((await screen.findByLabelText(`Renew session ${SESSION_A}`)).textContent)
      .toContain('It lapses twice its TTL, 60 s, after the leader takes the renewal.');
    press('Renew');
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(renewed).toEqual([SESSION_A]);
  });

  it('say a renewal of a session that has ended found it gone, and that one a server could not make may be retried', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    let answers = [
      new ApiError(`/v1/session/renew/${SESSION_A}`, 503, 'leader_unavailable', 'No leader is known', true, 'req-1', null),
      new ApiError(`/v1/session/renew/${SESSION_A}`, 404, 'session_not_found', `No session ${SESSION_A} exists to renew`,
        false, 'req-2', null),
    ];
    renderApp(fakeApi({ authorize: writing('node-a').authorize, renewSession: () => {
      const [next, ...rest] = answers;
      answers = rest;
      return Promise.reject(next);
    } }));

    await pressed('Renew');
    press('Renew');
    expect((await outcome()).textContent).toContain('Retrying may succeed.');
    await pressed('Submit again');
    await waitFor(async () => expect((await outcome()).textContent).toContain('Not found'));
    expect(screen.getByText('The session has ended: a renewal cannot bring it back.')).toBeInTheDocument();
  });

  it('destroy a session after a review of the keys it holds and what its behaviour does to them', async () => {
    at(`/ui/sessions/${SESSION_A}`);
    const destroyed: string[] = [];
    renderApp(fakeApi({ authorize: writing('node-a').authorize, destroySession: (id) => {
      destroyed.push(id);
      return read(true, null);
    } }));

    await pressed('Destroy');
    const review = await screen.findByLabelText(`Destroy session ${SESSION_A}`);
    await waitFor(() => expect(review.textContent).toContain('It holds 1 key: app/leader in acme/prod.'));
    expect(review.textContent).toContain('They are released, keeping their values, and none can be acquired again for 15 s.');
    fireEvent.click(review.querySelector('button.ant-btn-primary')!);
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(destroyed).toEqual([SESSION_A]);
  });

  it('offer no renewal for a session without a TTL', async () => {
    at(`/ui/sessions/${SESSION_B}`);
    renderApp(fakeApi({ authorize: writing('node-b').authorize }));

    await screen.findByText('Destroy', { selector: 'button' });
    expect(screen.queryByText('Renew', { selector: 'button' })).toBeNull();
  });
});
