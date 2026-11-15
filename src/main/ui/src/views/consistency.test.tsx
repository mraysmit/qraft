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
import type { Consistency, ReadOptions } from '../api/client';
import { BOOTSTRAP, entry, fakeApi, manualScheduler, read, summaryOf } from './testing';

// UI-4 task list, Phase 1: the consistency selector, the read line naming the consistency, the answering server and
// the index, and the top bar's pause control (UI/UX design sections 4.1 and 12).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

/** A services summary that records the consistency each read asked for and answers in it, from `server`. */
function summaryApi(asked: (Consistency | undefined)[], server = 'server-2', lastContactMillis = 0) {
  return fakeApi({
    servicesSummary: (_scope, options?: ReadOptions) => {
      asked.push(options?.consistency);
      return read([summaryOf('web', [entry()])], 12, {
        answeredBy: server,
        consistency: options?.consistency ?? 'default',
        lastContactMillis,
      });
    },
  });
}

async function choose(label: string, option: string) {
  fireEvent.mouseDown(document.querySelector(`input[aria-label="${label}"]`) as HTMLElement);
  const listed = await screen.findAllByTitle(option);
  const item = listed.find((element) => element.classList.contains('ant-select-item-option'));
  if (item === undefined) throw new Error(`no option ${option} in ${label}`);
  fireEvent.click(item);
}

describe('consistency', () => {
  it('reads the view in the consistency the address names and says who answered, how, and at what index', async () => {
    at('/ui/?consistency=consistent');
    const asked: (Consistency | undefined)[] = [];

    render(<App bootstrap={BOOTSTRAP} api={summaryApi(asked)} scheduler={manualScheduler().scheduler} />);

    expect(
      await screen.findByText('Answered by server-2 · Consistent read: confirmed by the leader · Applied index 12'),
    ).toBeInTheDocument();
    expect(asked).toEqual(['consistent']);
  });

  it('reads the view again in the consistency chosen, and keeps it in the address and the links', async () => {
    const asked: (Consistency | undefined)[] = [];
    render(<App bootstrap={BOOTSTRAP} api={summaryApi(asked)} scheduler={manualScheduler().scheduler} />);
    await screen.findByText(/Default consistency: this server's applied state/);

    await choose('Consistency', 'Stale');

    await waitFor(() => expect(asked).toEqual(['default', 'stale']));
    expect(window.location.search).toBe('?consistency=stale');
    expect(screen.getByText('Nodes and Clients', { selector: 'a' }).getAttribute('href')).toBe('/ui/nodes?consistency=stale');
  });

  it('labels a stale read with how long since the answering server heard from the leader', async () => {
    at('/ui/?consistency=stale');

    render(<App bootstrap={BOOTSTRAP} api={summaryApi([], 'server-3', 120)} scheduler={manualScheduler().scheduler} />);

    expect(
      await screen.findByText(
        "Answered by server-3 · Stale read: this server's applied state, 120 ms since it heard from the leader · Applied index 12",
      ),
    ).toBeInTheDocument();
  });

  it('says an operator view is the answering server own state, whatever the consistency', async () => {
    at('/ui/members?consistency=consistent');

    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} scheduler={manualScheduler().scheduler} />);

    expect(
      await screen.findByText("Answered by server-1 · This server's own state; the consistency chosen does not apply to it"),
    ).toBeInTheDocument();
  });

  it('pauses live updates from the top bar, and the read line says so', async () => {
    render(<App bootstrap={BOOTSTRAP} api={summaryApi([])} scheduler={manualScheduler().scheduler} />);
    await screen.findByText(/Applied index 12$/);

    fireEvent.click(screen.getByText('Pause live updates'));

    expect(await screen.findByText(/Applied index 12 · live updates paused$/)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Resume live updates'));
    expect(await screen.findByText(/Applied index 12$/)).toBeInTheDocument();
  });
});
