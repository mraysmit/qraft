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
import { ApiError, MalformedResponseError } from '../api/client';
import { BOOTSTRAP, entry, fakeApi, node, raftStatus, read } from './testing';

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

describe('status strip', () => {
  it('names the answering node, its role, the leader and term, readiness, and the security state in words', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    const strip = await screen.findByRole('region', { name: 'Cluster status' });
    expect(await within(strip).findByText('Answering node: server-1')).toBeInTheDocument();
    expect(within(strip).getByText('Role: Leader')).toBeInTheDocument();
    expect(within(strip).getByText('Leader: server-1')).toBeInTheDocument();
    expect(within(strip).getByText('Term: 3')).toBeInTheDocument();
    expect(within(strip).getByText('Readiness: Ready')).toBeInTheDocument();
    expect(within(strip).getByText('Security profile: production')).toBeInTheDocument();
    expect(within(strip).getByText('Access control: on')).toBeInTheDocument();
  });

  it('keeps a persistent warning for a server that is not ready, with its conditions', async () => {
    const api = fakeApi({ readiness: () => read({ ready: false, conditions: ['no_leader', 'draining'] }) });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    expect(await screen.findByText('Readiness: Not ready')).toBeInTheDocument();
    expect(screen.getByRole('alert', { name: 'Not ready' })).toHaveTextContent('no_leader, draining');
  });

  it('warns when no leader is known, when storage is fenced, and when the server was removed', async () => {
    const api = fakeApi({
      raftStatus: () => read(raftStatus({ state: 'FOLLOWER', leaderId: null, fenced: true, fenceReason: 'disk full', removed: true })),
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    expect(await screen.findByText('Leader: none known')).toBeInTheDocument();
    expect(screen.getByRole('alert', { name: 'No leader' })).toBeInTheDocument();
    expect(screen.getByRole('alert', { name: 'Storage fenced' })).toHaveTextContent('disk full');
    expect(screen.getByRole('alert', { name: 'Removed from the cluster' })).toBeInTheDocument();
  });

  it('shows a development server without access control a banner that cannot be dismissed', async () => {
    render(<App bootstrap={{ ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' }} api={fakeApi()} />);

    const banner = await screen.findByRole('alert', { name: 'No access control' });
    expect(banner).toHaveTextContent(/anyone who can reach this server/i);
    expect(within(banner).queryByRole('button')).toBeNull();
    expect(screen.getByText('Access control: off')).toBeInTheDocument();
  });

  it('reports a status read that failed with its code, request ID and leader hint', async () => {
    const api = fakeApi({
      raftStatus: () =>
        Promise.reject(new ApiError('/raft/status', 503, 'raft_unavailable', 'Raft state is unavailable', true, 'req-9', 'server-2')),
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    const failure = await screen.findByRole('alert', { name: 'Cluster status could not be read' });
    expect(failure).toHaveTextContent('Raft state is unavailable');
    expect(failure).toHaveTextContent('raft_unavailable');
    expect(failure).toHaveTextContent('Request ID: req-9');
    expect(failure).toHaveTextContent('Leader: server-2');
    expect(failure).toHaveTextContent('Retrying may succeed');
  });
});

describe('Services', () => {
  it('is the landing page, and lists each service with its health in words, instances, tags and scope', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument();
    const row = (await screen.findByRole('link', { name: 'web' })).closest('tr');
    expect(row).not.toBeNull();
    const cells = within(row as HTMLElement);
    expect(cells.getByText('Passing')).toBeInTheDocument();
    expect(cells.getByText('1')).toBeInTheDocument();
    expect(cells.getByText('http')).toBeInTheDocument();
    expect(cells.getByText('default / default')).toBeInTheDocument();
    expect(screen.getByText(/applied index 12/i)).toBeInTheDocument();
  });

  it('puts critical and warning services before passing ones, and takes the worst instance health', async () => {
    const api = fakeApi({}, {
      alpha: [entry({ serviceId: 'alpha-1', serviceName: 'alpha', health: 'PASSING' })],
      beta: [
        entry({ serviceId: 'beta-1', serviceName: 'beta', health: 'PASSING' }),
        entry({ serviceId: 'beta-2', serviceName: 'beta', health: 'CRITICAL' }),
      ],
      gamma: [entry({ serviceId: 'gamma-1', serviceName: 'gamma', health: 'WARNING' })],
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    await screen.findByRole('link', { name: 'alpha' });
    const order = screen.getAllByRole('link', { name: /^(alpha|beta|gamma)$/ }).map((link) => link.textContent);
    expect(order).toEqual(['beta', 'gamma', 'alpha']);
    expect(within(screen.getByRole('link', { name: 'beta' }).closest('tr') as HTMLElement).getByText('Critical'))
      .toBeInTheDocument();
  });

  it('shows a health with no accepted result as unknown, not as passing or failing', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, { web: [entry({ health: 'UNKNOWN' }, [])] })} />);

    const row = (await screen.findByRole('link', { name: 'web' })).closest('tr') as HTMLElement;
    expect(within(row).getByText('Unknown')).toBeInTheDocument();
  });

  it('says when no service is registered in the scope it read', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, {})} />);

    expect(await screen.findByText(/no service is registered in the default tenant and namespace/i)).toBeInTheDocument();
  });

  it('shows a malformed answer as a failure, not as partial data', async () => {
    const api = fakeApi({
      services: () => Promise.reject(new MalformedResponseError('/v1/catalog/services', 'req-4', 'web: expected array')),
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    const failure = await screen.findByRole('alert', { name: 'Services could not be read' });
    expect(failure).toHaveTextContent('not in the expected form');
    expect(failure).toHaveTextContent('Request ID: req-4');
    expect(screen.queryByRole('link', { name: 'web' })).toBeNull();
  });

  it('reads again when asked after a failure', async () => {
    let attempts = 0;
    const working = fakeApi();
    const api = fakeApi({
      services: () => {
        attempts += 1;
        return attempts === 1
          ? Promise.reject(new ApiError('/v1/catalog/services', 0, 'unreachable', 'The server could not be reached', true, null, null))
          : working.services();
      },
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);
    const failure = await screen.findByRole('alert', { name: 'Services could not be read' });
    fireEvent.click(within(failure).getByRole('button', { name: 'Read again' }));

    expect(await screen.findByRole('link', { name: 'web' })).toBeInTheDocument();
  });
});

describe('service detail', () => {
  it('opens from the Services list at its own address', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    fireEvent.click(await screen.findByRole('link', { name: 'web' }));

    expect(await screen.findByRole('heading', { name: 'Service: web' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/ui/services/web');
  });

  it('shows the overview with scope and aggregate health', async () => {
    at('/ui/services/web');

    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    const overview = await screen.findByRole('tabpanel');
    expect(within(overview).getByText('default / default')).toBeInTheDocument();
    expect(within(overview).getByText('Passing')).toBeInTheDocument();
    expect(within(overview).getByText('dc1 / eu-west')).toBeInTheDocument();
  });

  it('lists the instances with address, node, node status and health', async () => {
    at('/ui/services/web');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, { web: [entry({}, [{}], 'unreachable')] })} />);

    fireEvent.click(await screen.findByRole('tab', { name: 'Instances' }));

    const row = (await screen.findByText('web-1')).closest('tr') as HTMLElement;
    expect(within(row).getByText('10.0.0.5:8081')).toBeInTheDocument();
    expect(within(row).getByText('client-a')).toBeInTheDocument();
    expect(within(row).getByText('Unreachable')).toBeInTheDocument();
    expect(within(row).getByText('Passing')).toBeInTheDocument();
  });

  it('separates a check observation from its accepted, replicated result, and says when it expired', async () => {
    at('/ui/services/web');
    const api = fakeApi({}, { web: [entry({ health: 'CRITICAL' }, [{ status: 'CRITICAL', expired: true, output: 'timeout' }])] });
    render(<App bootstrap={BOOTSTRAP} api={api} />);

    fireEvent.click(await screen.findByRole('tab', { name: 'Health Checks' }));

    const row = (await screen.findByText('http')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Critical')).toBeInTheDocument();
    expect(within(row).getByText('Observed 2026-11-14 03:16:00 UTC')).toBeInTheDocument();
    expect(within(row).getByText('Accepted 2026-11-14 03:16:00 UTC')).toBeInTheDocument();
    expect(within(row).getByText('Expired: no renewal by 2026-11-14 03:16:30 UTC')).toBeInTheDocument();
    expect(within(row).getByText('timeout')).toBeInTheDocument();
  });

  it('shows tags, metadata and the indexes', async () => {
    at('/ui/services/web');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    fireEvent.click(await screen.findByRole('tab', { name: 'Metadata' }));

    const panel = await screen.findByRole('tabpanel', { name: 'Metadata' });
    expect(within(panel).getByText('owner=platform')).toBeInTheDocument();
    expect(within(panel).getByText('Created at index 4, modified at index 9')).toBeInTheDocument();
  });

  it('says when the service has no instance in the scope it read', async () => {
    at('/ui/services/gone');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    expect(await screen.findByText(/no instance of gone is registered in the default tenant and namespace/i))
      .toBeInTheDocument();
  });

  it('reads a name with a slash as one service', async () => {
    at('/ui/services/team%2Fpayments');
    const asked: string[] = [];
    const api = fakeApi({ serviceHealth: (name) => { asked.push(name); return read([]); } });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    expect(await screen.findByRole('heading', { name: 'Service: team/payments' })).toBeInTheDocument();
    expect(asked).toContain('team/payments');
  });
});

describe('Nodes and Clients', () => {
  it('lists each client with its status in words, version, contact times, and owned services and failing checks', async () => {
    at('/ui/nodes');
    const api = fakeApi({}, {
      web: [entry({}, [{ status: 'CRITICAL' }, { checkId: 'tcp', status: 'PASSING' }])],
      api: [entry({ serviceId: 'api-1', serviceName: 'api' }, [])],
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    expect(await screen.findByRole('heading', { name: 'Nodes and Clients' })).toBeInTheDocument();
    const row = (await screen.findByText('client-a')).closest('tr') as HTMLElement;
    const cells = within(row);
    expect(cells.getByText('Healthy')).toBeInTheDocument();
    expect(cells.getByText('1.0-SNAPSHOT')).toBeInTheDocument();
    expect(cells.getByText('dc1 / eu-west')).toBeInTheDocument();
    expect(cells.getByText('2026-11-14 03:16:01 UTC')).toBeInTheDocument();
    expect(cells.getByText('2 services')).toBeInTheDocument();
    expect(cells.getByText('1 failing')).toBeInTheDocument();
    expect(screen.getByText(/a node has no tenant or namespace/i)).toBeInTheDocument();
  });

  it('says a node that never sent a heartbeat has not, rather than leaving the cell blank', async () => {
    at('/ui/nodes');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ nodes: () => read([node({ status: 'registering', lastHeartbeat: null })]) })} />);

    const row = (await screen.findByText('client-a')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Registering')).toBeInTheDocument();
    expect(within(row).getByText('No heartbeat yet')).toBeInTheDocument();
  });

  it('says when no client node is registered', async () => {
    at('/ui/nodes');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({ nodes: () => read([]) })} />);

    expect(await screen.findByText(/no client node is registered/i)).toBeInTheDocument();
  });
});

describe('navigation', () => {
  it('moves between the views and follows the browser back button', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);
    await screen.findByRole('heading', { name: 'Services' });

    fireEvent.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Nodes and Clients' }));
    expect(await screen.findByRole('heading', { name: 'Nodes and Clients' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/ui/nodes');

    window.history.back();
    expect(await screen.findByRole('heading', { name: 'Services' })).toBeInTheDocument();
  });

  it('says an address it does not know is not a view, and links to Services', async () => {
    at('/ui/nowhere');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    expect(await screen.findByRole('heading', { name: 'No such view' })).toBeInTheDocument();
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Services' })).toBeInTheDocument();
  });
});
