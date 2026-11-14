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
import { BOOTSTRAP, entry, fakeApi } from './testing';

// UI-2 task list, Step 4: the Health Checks page across services (UI/UX design section 5.3).

function at(path: string) {
  window.history.pushState(null, '', path);
}

const ENTRIES = {
  web: [
    entry({ serviceId: 'web-1', health: 'CRITICAL' }, [{ checkId: 'http', status: 'CRITICAL', expired: true, output: 'timeout' }]),
    entry({ serviceId: 'web-2', nodeId: 'client-b' }, [{ checkId: 'http', status: 'PASSING', output: '200 OK' }]),
  ],
  db: [entry({ serviceId: 'db-1', serviceName: 'db', health: 'WARNING' }, [{ checkId: 'disk', status: 'WARNING', output: '85% full' }])],
};

// The table's data rows. A selector, not getAllByRole: computing every element's role and name over the page took most
// of the filter test's time, which passed 5 s on Jenkins build 72 and timed out.
function rows(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('tbody tr.ant-table-row'));
}

beforeEach(() => at('/ui/checks'));

describe('Health Checks', () => {
  it('opens from the navigation at its own address', async () => {
    at('/ui/');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, ENTRIES)} />);
    await screen.findByRole('heading', { name: 'Services' });

    fireEvent.click(within(screen.getByRole('navigation')).getByRole('link', { name: 'Health Checks' }));

    expect(await screen.findByRole('heading', { name: 'Health Checks' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/ui/checks');
  });

  it('lists every check in the scope, needing attention first, with its service, instance, node and result', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, ENTRIES)} />);

    await screen.findByRole('heading', { name: 'Health Checks' });
    const listed = await screen.findAllByRole('row');
    expect(listed.length).toBeGreaterThan(1);
    const [first, second, third] = rows();
    expect(within(first as HTMLElement).getByText('Critical')).toBeInTheDocument();
    expect(within(second as HTMLElement).getByText('Warning')).toBeInTheDocument();
    expect(within(third as HTMLElement).getByText('Passing')).toBeInTheDocument();
    const critical = within(first as HTMLElement);
    expect(critical.getByRole('link', { name: 'web' }).getAttribute('href')).toBe('/ui/services/web');
    expect(critical.getByText('web-1')).toBeInTheDocument();
    expect(critical.getByText('client-a')).toBeInTheDocument();
    expect(critical.getByText('Healthy')).toBeInTheDocument();
    expect(critical.getByText('http')).toBeInTheDocument();
    expect(critical.getByText('Observed 2026-11-14 03:16:00 UTC')).toBeInTheDocument();
    expect(critical.getByText('Accepted 2026-11-14 03:16:00 UTC')).toBeInTheDocument();
    expect(critical.getByText('Expired: no renewal by 2026-11-14 03:16:30 UTC')).toBeInTheDocument();
    expect(critical.getByText('timeout')).toBeInTheDocument();
    expect(screen.getByText('3 checks: 1 critical, 1 warning, 1 passing')).toBeInTheDocument();
  });

  it('filters by status, and by text over service, instance, node, check and output', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, ENTRIES)} />);
    await screen.findByText('85% full');

    fireEvent.click(screen.getByLabelText('Critical'));
    expect(rows()).toHaveLength(1);
    expect(within(rows()[0] as HTMLElement).getByText('web-1')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('All'));
    fireEvent.change(screen.getByLabelText('Filter'), { target: { value: 'client-b' } });
    expect(rows()).toHaveLength(1);
    expect(within(rows()[0] as HTMLElement).getByText('web-2')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Filter'), { target: { value: 'full' } });
    expect(within(rows()[0] as HTMLElement).getByText('db-1')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Filter'), { target: { value: 'nothing-like-it' } });
    expect(screen.getByText('No check matches the filters.')).toBeInTheDocument();
  });

  it('shows a check with no accepted result yet as unknown, neither passing nor failing', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, { web: [entry({}, [{ status: 'UNKNOWN' }])] })} />);

    const row = (await screen.findByText('web-1')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Unknown')).toBeInTheDocument();
  });

  it('says when no check has a result in the scope it read', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, {})} />);

    expect(await screen.findByText(/no check has an accepted result in the default tenant and namespace/i))
      .toBeInTheDocument();
  });

  it('reports a listing that failed, with its request ID', async () => {
    const api = fakeApi({
      checks: () => Promise.reject(new ApiError('/v1/health/state/any', 503, 'leader_unavailable', 'No leader', true, 'r-5', null)),
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    const failure = await screen.findByRole('alert', { name: 'Health checks could not be read' });
    expect(failure).toHaveTextContent('Request ID: r-5');
  });
});
