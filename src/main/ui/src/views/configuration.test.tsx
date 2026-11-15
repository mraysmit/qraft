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
import { App } from '../App';
import { ApiError } from '../api/client';
import { BOOTSTRAP, fakeApi, manualScheduler } from './testing';

// UI-4 task list, Phase 2: the Configuration page (UI/UX design section 9.6; decision 3).

beforeEach(() => window.history.pushState(null, '', '/ui/configuration'));

/** Each listed setting's cells, as text, top to bottom. */
function rows(): string[][] {
  return Array.from(document.querySelectorAll('tbody tr.ant-table-row')).map((row) =>
    Array.from(row.querySelectorAll('td')).map((cell) => cell.textContent ?? ''),
  );
}

describe('Configuration', () => {
  it('lists each setting with its value, its source and that it takes a restart, a secret withheld', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} scheduler={manualScheduler().scheduler} />);

    expect(await screen.findByText('server.http.port')).toBeInTheDocument();
    expect(rows()).toEqual([
      ['server.id', 'server-1', 'Configured', 'Restart required'],
      ['server.http.port', '8080', 'Default', 'Restart required'],
      ['server.raft.nodes', 'server-1=10.0.0.1:9080', 'Configured', 'Restart required'],
      ['server.raft.storage.fsync', 'true', 'Default', 'Restart required'],
      ['server.operator.token', 'Redacted', 'Configured', 'Restart required'],
      ['server.acl.defaultPolicy', 'Not set', 'Default', 'Restart required'],
    ]);
    expect(
      screen.getByText('Read by server-1 from /etc/qraft/server.json; the server was given it at 2026-11-15 08:59:58 UTC.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Every setting here is static/)).toBeInTheDocument();
    expect(document.querySelector('[aria-label="Static configuration"] a')?.getAttribute('href')).toBe('/ui/members');
  });

  it('narrows the settings by key and by source', async () => {
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} scheduler={manualScheduler().scheduler} />);
    await screen.findByText('server.http.port');

    fireEvent.change(screen.getByLabelText('Filter settings'), { target: { value: 'raft' } });
    expect(rows().map((row) => row[0])).toEqual(['server.raft.nodes', 'server.raft.storage.fsync']);
    fireEvent.click(screen.getByText('Configured', { selector: '.ant-radio-button-wrapper span' }));
    expect(rows().map((row) => row[0])).toEqual(['server.raft.nodes']);
  });

  it('says why the configuration could not be read', async () => {
    const refused = new ApiError('/v1/operator/configuration', 403, 'permission_denied', 'Permission denied', false, 'r-7', null);
    render(
      <App
        bootstrap={BOOTSTRAP}
        api={fakeApi({ configuration: () => Promise.reject(refused) })}
        scheduler={manualScheduler().scheduler}
      />,
    );

    expect(await screen.findByText('Configuration could not be read')).toBeInTheDocument();
    expect(screen.getByText('Code: permission_denied')).toBeInTheDocument();
  });
});
