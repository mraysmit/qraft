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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { EventQuery } from '../api/client';
import { cursor } from '../api/fixtures';
import type { EventPage } from '../api/schemas';
import { BOOTSTRAP, eventPage, fakeApi, manualScheduler, qraftEvent, read, tabVisibility, watchRecorder } from './testing';

// UI-4 task list, Phase 2: the Events page, its journal labelled ephemeral (UI/UX design section 9.4), and the Events
// tabs (decision 7), following the watch with pause, resume and resynchronization (decisions 5 and 6).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/events'));

/** An API whose event pages are `pages` in turn, the last one again after them, recording each query. */
function journal(pages: EventPage[]) {
  const asked: EventQuery[] = [];
  const recorder = watchRecorder();
  const api = fakeApi({
    events: (query) => {
      asked.push(query);
      return read(pages[Math.min(asked.length - 1, pages.length - 1)] as EventPage, null);
    },
    watchEvents: recorder.watchEvents,
  });
  return { api, asked, watches: recorder.watches };
}

/** The type of each listed event, top to bottom. */
function listed(): string[] {
  return Array.from(document.querySelectorAll('tbody tr.ant-table-row')).map(
    (row) => row.querySelector('[data-event-type]')?.textContent ?? '',
  );
}

function renderApp(api: ReturnType<typeof fakeApi>, time = manualScheduler()) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={time.scheduler} />);
  return time;
}

describe('Events', () => {
  it('reads the whole journal, newest first, says it is ephemeral, then follows the watch from its last cursor', async () => {
    const { api, asked, watches } = journal([
      eventPage([qraftEvent(1, { type: 'service.registered' }), qraftEvent(2, { type: 'health.changed' })], 'more'),
      eventPage([qraftEvent(3, { type: 'service.deregistered' })]),
    ]);

    renderApp(api);

    await waitFor(() => expect(listed()).toEqual(['service.deregistered', 'health.changed', 'service.registered']));
    expect(asked).toEqual([{ limit: 1000 }, { limit: 1000, after: cursor(2) }]);
    expect(screen.getByText(/server-1 keeps only its last 4096 events since it started/)).toBeInTheDocument();
    expect(watches.map((watch) => watch.query)).toEqual([{ after: cursor(3) }]);

    act(() => watches[0]?.handlers.onEvent(qraftEvent(4, { type: 'key.set', category: 'domain' })));

    expect(listed()[0]).toBe('key.set');
    expect(screen.getByText(`Following from cursor ${cursor(4)}`)).toBeInTheDocument();
  });

  it('narrows the loaded events by severity, scope, term, correlation ID and least committed index', async () => {
    const { api } = journal([
      eventPage([
        qraftEvent(1, { type: 'a.info', severity: 'info', tenant: 'acme' }),
        qraftEvent(2, { type: 'b.warning', severity: 'warning', term: 4, correlationId: 'req-9' }),
        qraftEvent(3, { type: 'c.error', severity: 'error', tenant: 'acme' }),
      ]),
    ]);
    renderApp(api);
    await waitFor(() => expect(listed()).toHaveLength(3));

    fireEvent.change(screen.getByLabelText('Event tenant'), { target: { value: 'acme' } });
    expect(listed()).toEqual(['c.error', 'a.info']);
    fireEvent.change(screen.getByLabelText('Event tenant'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Least committed index'), { target: { value: '2' } });
    expect(listed()).toEqual(['c.error', 'b.warning']);
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '4' } });
    expect(listed()).toEqual(['b.warning']);
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText('Correlation ID'), { target: { value: 'req-9' } });
    expect(listed()).toEqual(['b.warning']);
    expect(screen.getByText('1 of 3 events shown')).toBeInTheDocument();
  });

  it('says events were missed on a history gap, and reads the journal again from its start', async () => {
    const { api, asked, watches } = journal([
      eventPage([qraftEvent(1, { type: 'old.event' })]),
      eventPage([qraftEvent(9, { type: 'new.event' })]),
    ]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    act(() => watches[0]?.handlers.onEnd({ reason: 'history_gap', resumeAfter: cursor(8) }));

    expect(await screen.findByText(/dropped events before the interface read them/)).toBeInTheDocument();
    await waitFor(() => expect(listed()).toEqual(['new.event']));
    expect(asked.at(-1)).toEqual({ limit: 1000 });
    expect(watches[1]?.query).toEqual({ after: cursor(9) });
  });

  it('resumes from where an overflowed watch stopped', async () => {
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)]), eventPage([qraftEvent(6, { type: 'late.event' })])]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    act(() => watches[0]?.handlers.onEnd({ reason: 'overflow', resumeAfter: cursor(5) }));

    expect(await screen.findByText(/fell behind/)).toBeInTheDocument();
    await waitFor(() => expect(watches).toHaveLength(2));
    expect(asked.at(-1)).toEqual({ limit: 1000, after: cursor(5) });
    expect(listed()).toEqual(['late.event', 'service.registered']);
  });

  it('closes the watch while paused, and on resuming reads from its cursor before following again', async () => {
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)]), eventPage([], 'complete', 1)]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    fireEvent.click(screen.getByText('Pause live updates'));

    expect(watches[0]?.closed).toBe(true);
    expect(screen.getByText(`Paused at cursor ${cursor(1)}`)).toBeInTheDocument();
    fireEvent.click(screen.getByText('Resume live updates'));
    await waitFor(() => expect(watches).toHaveLength(2));
    expect(asked.at(-1)).toEqual({ limit: 1000, after: cursor(1) });
    expect(watches[1]?.query).toEqual({ after: cursor(1) });
  });

  it('closes the watch when the page is hidden, and when it is shown from the cache reads from its cursor and follows', async () => {
    // UI-6 Tenants, Namespaces and Audit task list, Phase 4: a page in the browser's back/forward cache must not hold
    // its watch's connection, of the six a browser opens to the server.
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)]), eventPage([], 'complete', 1)]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }));
    });

    expect(watches[0]?.closed).toBe(true);
    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
    });
    await waitFor(() => expect(watches).toHaveLength(2));
    expect(asked.at(-1)).toEqual({ limit: 1000, after: cursor(1) });
    expect(watches[1]?.query).toEqual({ after: cursor(1) });
  });

  it('closes the watch when the tab is hidden, and when it is shown reads from its cursor and follows', async () => {
    // Open-items task list, decision 1: a hidden tab must not hold its watch's connection either.
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)]), eventPage([], 'complete', 1)]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    act(() => tabVisibility('hidden'));

    expect(watches[0]?.closed).toBe(true);
    act(() => tabVisibility('visible'));
    await waitFor(() => expect(watches).toHaveLength(2));
    expect(asked.at(-1)).toEqual({ limit: 1000, after: cursor(1) });
    expect(watches[1]?.query).toEqual({ after: cursor(1) });
  });

  it('tries a failed watch again after a few seconds, from its cursor', async () => {
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)]), eventPage([], 'complete', 1)]);
    const time = renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    act(() => watches[0]?.handlers.onError(new Error('connection reset')));

    expect(await screen.findByText(/The watch stopped: connection reset/)).toBeInTheDocument();
    act(() => time.fire());
    await waitFor(() => expect(watches).toHaveLength(2));
    expect(asked.at(-1)).toEqual({ limit: 1000, after: cursor(1) });
  });

  it('reads the category chosen, and follows only it', async () => {
    const { api, asked, watches } = journal([eventPage([qraftEvent(1)])]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));

    fireEvent.mouseDown(document.querySelector('input[aria-label="Category"]') as HTMLElement);
    const options = await screen.findAllByTitle('Domain');
    fireEvent.click(options.find((element) => element.classList.contains('ant-select-item-option')) as HTMLElement);

    await waitFor(() => expect(watches).toHaveLength(2));
    expect(watches[0]?.closed).toBe(true);
    expect(asked.at(-1)).toEqual({ limit: 1000, category: 'domain' });
    expect(watches[1]?.query).toEqual({ after: cursor(1), category: 'domain' });
  });

  it("opens in the category and with the correlation ID its address names, as a mutation's audit link gives them", async () => {
    // UI-5 task list, decision 6.
    window.history.pushState(null, '', '/ui/events?category=audit&correlation=req-42');
    const audited = qraftEvent(2, { category: 'audit', correlationId: 'req-42', type: 'audit.request' });
    const other = qraftEvent(3, { category: 'audit', correlationId: 'req-43', type: 'audit.request' });
    const { api, asked } = journal([eventPage([audited, other])]);
    renderApp(api);

    await waitFor(() => expect(asked.at(-1)).toEqual({ limit: 1000, category: 'audit' }));
    expect((document.querySelector('input[aria-label="Correlation ID"]') as HTMLInputElement).value).toBe('req-42');
    expect(await screen.findByText('1 of 2 events shown')).toBeInTheDocument();
  });
});

describe('exporting events', () => {
  // UI-5 task list, decision 10: the events the page holds, as filtered, saved as JSON; nothing is sent to the server.
  const created: Blob[] = [];
  const saved: string[] = [];
  beforeEach(() => {
    created.length = 0;
    saved.length = 0;
    // jsdom has no object URLs, and follows no download: both are recorded instead.
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: (blob: Blob) => {
      created.push(blob);
      return 'blob:events';
    } });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => undefined });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function record(this: HTMLAnchorElement) {
      saved.push(this.download);
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it('saves the events shown, newest first, as a JSON file named for the server and the time', async () => {
    window.history.pushState(null, '', '/ui/events?correlation=req-42');
    const audited = qraftEvent(2, { correlationId: 'req-42' });
    const other = qraftEvent(3, { correlationId: 'req-43' });
    const { api, watches } = journal([eventPage([audited, other])]);
    renderApp(api);
    await waitFor(() => expect(watches).toHaveLength(1));
    await screen.findByText('1 of 2 events shown');

    fireEvent.click(screen.getByText('Export the events shown', { selector: 'button, button span' }));

    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatch(/^qraft-events-server-1-\d{8}T\d{6}Z\.json$/);
    // jsdom's Blob has no text(); a FileReader reads it.
    const text = await new Promise<string>((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.readAsText(created[0] as Blob);
    });
    const exported = JSON.parse(text) as { cursor: string }[];
    expect(exported.map((event) => event.cursor)).toEqual([cursor(2)]);
  });
});

describe('the Events tabs', () => {
  it('lists a service events in the read scope on its Events tab', async () => {
    at('/ui/services/web?tenant=acme&namespace=prod');
    const { api, asked } = journal([eventPage([qraftEvent(1, { type: 'health.changed' })])]);
    renderApp(api);
    fireEvent.click(await screen.findByText('Events', { selector: '.ant-tabs-tab-btn' }));

    await waitFor(() => expect(listed()).toEqual(['health.changed']));
    expect(asked[0]).toEqual({ limit: 1000, tenant: 'acme', namespace: 'prod', service: 'web' });
  });

  it('shows the last ten system events on Cluster Overview, newest first', async () => {
    at('/ui/cluster');
    const events = Array.from({ length: 12 }, (_, at) =>
      qraftEvent(at + 1, { type: `raft.event_${at + 1}`, category: 'system' }),
    );
    const { api, asked } = journal([eventPage(events)]);
    renderApp(api);

    await waitFor(() => expect(listed()).toHaveLength(10));
    expect(listed()[0]).toBe('raft.event_12');
    expect(listed()[9]).toBe('raft.event_3');
    expect(asked[0]).toEqual({ limit: 1000, category: 'system' });
    expect(screen.getByText('All events', { selector: 'a' }).getAttribute('href')).toBe('/ui/events');
  });
});
