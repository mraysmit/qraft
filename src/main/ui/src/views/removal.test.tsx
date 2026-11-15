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

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Root } from '../Root';
import { ApiError, type AuthorizationCheck, type QraftApi } from '../api/client';
import { BOOTSTRAP, fakeApi, memorySession, members, read } from './testing';

// UI-5 task list, Phase 3 and decision 8: a server removed from Raft Members, to a token with operator:write, after a
// review of the quorum before and after (UI/UX design section 9.2).

beforeEach(() => window.history.pushState(null, '', '/ui/members'));

const granting = (...granted: string[]) => (checks: AuthorizationCheck[]) =>
  read(checks.map((check) => ({ ...check, allow: granted.includes(`${check.resource}:${check.access}`) })), null);

function removing(overrides: Partial<QraftApi> = {}) {
  const removed: [string, string | undefined][] = [];
  const api = fakeApi({
    authorize: granting('operator:read', 'operator:write'),
    removeServer: (serverId, operatorToken) => {
      removed.push([serverId, operatorToken]);
      return read({ status: 'REMOVED', message: `${serverId} removed` }, 70);
    },
    ...overrides,
  });
  return { api, removed };
}

const button = (text: string) => screen.getByText(text, { selector: 'button, button span' });

describe('server removal', () => {
  it('reviews the quorum before and after, and removes the server by its ID', async () => {
    const { api, removed } = removing();
    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    fireEvent.click(await screen.findByText('Remove server-3', { selector: 'button, button span' }));

    const review = (await screen.findByLabelText('Remove server-3')).textContent;
    expect(review).toContain('Server IDserver-3-id');
    expect(review).toContain('Addressserver-3:9080');
    expect(review).toContain('Voters: 3 → 2');
    expect(review).toContain('Quorum: 2 of 3 → 2 of 2');
    expect(review).toContain('Failure tolerance after: 0');
    expect(review).toContain('The server is reachable.');
    expect(review).toContain('A removed server still running must be stopped, and not restarted with its old data.');
    fireEvent.click(button('Remove'));

    expect(await screen.findByText(/Committed and applied at index 70/)).toBeInTheDocument();
    expect(removed).toEqual([['server-3-id', undefined]]);
  });

  it('warns that removing the leader starts an election, and reports a refusal with its reason', async () => {
    const { api } = removing({ removeServer: () => Promise.reject(new ApiError('/v1/operator/raft/peer', 409, 'refused',
      'Removal refused: a configuration change is in progress', false, 'req-8', null)) });
    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    fireEvent.click(await screen.findByText('Remove server-1', { selector: 'button, button span' }));
    expect((await screen.findByLabelText('Remove server-1')).textContent).toContain(
      'This server is the leader: removing it starts an election.');
    fireEvent.click(button('Remove'));

    expect(await screen.findByText("Refused to protect the cluster's integrity (refused): Removal refused: a configuration "
      + 'change is in progress')).toBeInTheDocument();
  });

  it('offers no removal of the last voter', async () => {
    const single = members({ quorum: { voters: 1, quorumSize: 1, reachableVoters: 1, failureTolerance: 0, healthy: true },
      servers: [members().servers[0] as ReturnType<typeof members>['servers'][number]] });
    render(<Root bootstrap={BOOTSTRAP} api={removing({ members: () => read(single) }).api} session={memorySession('held')} />);

    expect(await screen.findByText('The last voter cannot be removed.')).toBeInTheDocument();
    expect(screen.queryByText('Remove server-1', { selector: 'button, button span' })).toBeNull();
  });

  it('offers no removal without operator:write', async () => {
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ authorize: granting('operator:read') })} session={memorySession('held')} />);

    expect(await screen.findByText('Removing a server needs operator:write.')).toBeInTheDocument();
    expect(screen.queryByText('Remove server-2', { selector: 'button, button span' })).toBeNull();
  });

  it('asks for the operator token for the one removal on a server without access control, and keeps it for none other', async () => {
    const { api, removed } = removing();
    render(<Root bootstrap={{ ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' }} api={api}
      session={memorySession()} />);

    fireEvent.click(await screen.findByText('Remove server-3', { selector: 'button, button span' }));
    fireEvent.change(await screen.findByLabelText('Operator token'), { target: { value: 'typed-operator-token' } });
    fireEvent.click(button('Remove'));
    await screen.findByText(/Committed and applied at index 70/);
    fireEvent.click(button('Close'));

    fireEvent.click(await screen.findByText('Remove server-2', { selector: 'button, button span' }));
    expect((await screen.findByLabelText('Operator token') as HTMLInputElement).value).toBe('');
    expect(removed).toEqual([['server-3-id', 'typed-operator-token']]);
  });
});
