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

import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { ApiError } from '../api/client';
import { BOOTSTRAP, fakeApi, followerMembers, members, read, storage } from './testing';

// UI-3 task list, Phase 2: the full status strip, server rows, Cluster Overview, Raft Members and Storage & Snapshots
// (UI/UX design sections 4.2, 5.2, 9.1 to 9.3). Text and label queries only: whole-page role queries timed out on
// Jenkins in build 72.

function at(path: string) {
  window.history.pushState(null, '', path);
}

function rowOf(text: string): HTMLElement {
  const cell = screen.getByText(text, { selector: 'td, td *' });
  return cell.closest('tr') as HTMLElement;
}

const UNREACHABLE = members({
  quorum: { voters: 3, quorumSize: 2, reachableVoters: 2, failureTolerance: 0, healthy: false },
  servers: members().servers.map((server) =>
    server.name === 'server-3' ? { ...server, lag: 7, reachable: false, healthy: false, lastContactMillis: 12000 } : server,
  ),
});

beforeEach(() => at('/ui/'));

describe('the full status strip', () => {
  it('gives the quorum, the failure tolerance and the largest lag from the leader', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    const strip = screen.getByLabelText('Cluster status');
    expect(await within(strip).findByText('Quorum: 3 of 3 voters reachable')).toBeInTheDocument();
    expect(within(strip).getByText('Failure tolerance: 1')).toBeInTheDocument();
    expect(within(strip).getByText('Replication lag: 0')).toBeInTheDocument();
  });

  it('keeps a server the leader cannot reach in a persistent warning, with the lag it has', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(UNREACHABLE) })} />);

    const strip = screen.getByLabelText('Cluster status');
    expect(await within(strip).findByText('Quorum: 2 of 3 voters reachable')).toBeInTheDocument();
    expect(within(strip).getByText('Replication lag: 7')).toBeInTheDocument();
    expect(within(strip).getByLabelText('Servers unreachable')).toHaveTextContent('server-3');
  });

  it('says on a follower that only the leader knows the quorum and the lag', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(followerMembers()) })} />);

    expect(await screen.findByText('Quorum: known to the leader, server-1')).toBeInTheDocument();
  });

  it('still shows the answering node when the membership cannot be read', async () => {
    const denied = new ApiError('/v1/operator/raft/members', 403, 'permission_denied', 'Permission denied', false, 'r-1', null);
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => Promise.reject(denied) })} />);

    expect(await screen.findByText('Quorum: not read (Permission denied)')).toBeInTheDocument();
    expect(screen.getByText('Answering node: server-1')).toBeInTheDocument();
  });
});

describe('server rows in Nodes and Clients', () => {
  it('lists each server with its role, reachability, match index and lag, above the clients', async () => {
    at('/ui/nodes');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(UNREACHABLE) })} />);

    expect(await screen.findByText('Servers', { selector: 'h3' })).toBeInTheDocument();
    const leader = within(rowOf('server-1'));
    expect(leader.getByText('Leader')).toBeInTheDocument();
    expect(leader.getByText('Reachable')).toBeInTheDocument();
    const behind = within(rowOf('server-3'));
    expect(behind.getByText('Follower')).toBeInTheDocument();
    expect(behind.getByText('Unreachable')).toBeInTheDocument();
    expect(behind.getByText('7')).toBeInTheDocument();
    expect(screen.getByText('client-a')).toBeInTheDocument();
  });

  it('says on a follower that only the leader knows the servers progress', async () => {
    at('/ui/nodes');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(followerMembers()) })} />);

    const row = within((await screen.findByText('server-3', { selector: 'td, td *' })).closest('tr') as HTMLElement);
    expect(row.getAllByText('Known to the leader').length).toBeGreaterThan(0);
  });
});

describe('Cluster Overview', () => {
  it('opens from the navigation and sums up the cluster in words', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);
    await screen.findByText('Services', { selector: 'h2' });

    fireEvent.click(within(screen.getByLabelText('Primary')).getByText('Cluster Overview'));

    expect(await screen.findByText('Cluster Overview', { selector: 'h2' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/ui/cluster');
    expect(await screen.findByText('3 servers, 3 reachable, leader server-1')).toBeInTheDocument();
    expect(screen.getByText('Quorum of 2 of 3 voters; tolerates 1 failure')).toBeInTheDocument();
    expect(await screen.findByText('1 service, 1 instance in the default tenant and namespace')).toBeInTheDocument();
    expect(screen.getByText('1 client node')).toBeInTheDocument();
    expect(screen.getByText('1.5 MB; snapshot at index 30; 0 transitions queued')).toBeInTheDocument();
  });
});

describe('Raft Members', () => {
  it('lists each server with its progress, and the quorum', async () => {
    at('/ui/members');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(UNREACHABLE) })} />);

    expect(await screen.findByText('Raft Members', { selector: 'h2' })).toBeInTheDocument();
    expect(await screen.findByText('A quorum is 2 of 3 voters; 2 reachable; tolerates 0 failures')).toBeInTheDocument();
    const behind = within(rowOf('server-3'));
    expect(behind.getByText('Voter')).toBeInTheDocument();
    expect(behind.getByText('Unreachable')).toBeInTheDocument();
    expect(behind.getByText('Unhealthy')).toBeInTheDocument();
    expect(behind.getByText('12.0 s ago')).toBeInTheDocument();
    expect(behind.getByText('42')).toBeInTheDocument();
    expect(behind.getByText('43')).toBeInTheDocument();
    expect(within(rowOf('server-2')).getByText('120 ms ago')).toBeInTheDocument();
    expect(screen.getByText(/membership changes are not offered here/i)).toBeInTheDocument();
  });

  it('says on a follower who it follows and that the progress is known only to the leader', async () => {
    at('/ui/members');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ members: () => read(followerMembers()) })} />);

    const note = await screen.findByLabelText('Progress known to the leader');
    expect(note).toHaveTextContent('server-2 follows server-1');
    expect(within(rowOf('server-3')).getAllByText('Known to the leader').length).toBeGreaterThan(0);
  });
});

describe('Storage & Snapshots', () => {
  it('gives the storage, the snapshot, the retained boundary and the queue', async () => {
    at('/ui/storage');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    expect(await screen.findByText('Storage & Snapshots', { selector: 'h2' })).toBeInTheDocument();
    expect(await screen.findByText('1.5 MB')).toBeInTheDocument();
    expect(screen.getByText('Index 30, term 2, taken 90 s ago')).toBeInTheDocument();
    expect(screen.getByText('31')).toBeInTheDocument();
    expect(screen.getByText('Last 42, committed 42, applied 42')).toBeInTheDocument();
    expect(screen.getByText('0 queued')).toBeInTheDocument();
    expect(screen.getByText('No snapshot has failed since the server started.')).toBeInTheDocument();
    expect(screen.getByText('Not fenced')).toBeInTheDocument();
  });

  it('keeps fenced storage in a persistent banner on the page, and gives the last snapshot failure', async () => {
    at('/ui/storage');
    const fenced = storage({
      fenced: true,
      fenceReason: 'disk full',
      lastSnapshotFailure: { message: 'no space left', at: '2026-11-14T08:00:00Z' },
    });
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ storage: () => read(fenced) })} />);

    const banner = await within(screen.getByRole('main')).findByLabelText('Storage fenced');
    expect(banner).toHaveTextContent('disk full');
    expect(within(banner).queryByText('Close')).toBeNull();
    expect(await screen.findByText('Failed at 2026-11-14 08:00:00 UTC: no space left')).toBeInTheDocument();
  });

  it('says what it cannot know on a server with no storage directory or snapshot', async () => {
    at('/ui/storage');
    const bare = storage({ storageBytes: null, snapshotAgeSeconds: null, snapshotLastIndex: 0, snapshotLastTerm: 0 });
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ storage: () => read(bare) })} />);

    expect(await screen.findByText('Not known: the server has no storage directory')).toBeInTheDocument();
    expect(screen.getByText('No snapshot yet')).toBeInTheDocument();
  });
});
