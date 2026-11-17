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

import { Space, Typography } from 'antd';
import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { useAccess } from '../access';
import type { Consistency, QraftApi, Read, Scope } from '../api/client';
import { valueOf } from '../api/kv';
import type { HealthEntry, KvEntry, Sessions } from '../api/schemas';
import { type Missed, useEventFeed } from '../eventFeed';
import { type LiveWait, useLive, useLiveRead } from '../live';
import { useRouter, type WatchTarget } from '../routing';
import { formatInstant, healthCountsText, PlainTable, plural, scopeText } from './common';
import { changesOf, withRow, type WatchRow } from './watchLog';

// The Watches page (UI/UX design section 7.3; UI-6 Sessions, Locks and Watches task list, decision 10): one watch, in
// this tab alone, on a key, the keys under a prefix, a service's health, the sessions or the event stream. Each is a
// blocking query on its index, or the event watch, and its log lists every delivery with its index, never a value, and
// every resynchronization: an index that went back, or events missed, after which the state was read afresh.

const TARGETS: [WatchTarget, string][] = [
  ['key', 'A key'],
  ['prefix', 'The keys under a prefix'],
  ['service', "A service's health"],
  ['sessions', 'The sessions'],
  ['events', 'The event stream'],
];

const KINDS: Record<WatchRow['kind'], string> = {
  start: 'Started',
  delivery: 'Delivered',
  resynchronized: 'Resynchronized',
  failure: 'Failed',
};

const MISSED: Record<Missed, string> = {
  history_gap: 'Events were missed (history_gap): the journal was read again from its start, and the watch resumed '
    + 'after its last event.',
  other_boot: 'The server restarted (other_boot): the journal was read again from its start, and the watch resumed '
    + 'after its last event.',
  overflow: 'The watch fell behind (overflow): it resumed after the last event it delivered.',
};

const PAUSED = 'Live updates are paused: the watch delivers nothing until they resume.';

type Add = (kind: WatchRow['kind'], text: string, index: number | null) => void;

/** What a watch says it watches, in its first row. */
function startText(target: WatchTarget, name: string, scope: Scope, consistency: Consistency): string {
  const at = `at ${consistency} consistency`;
  switch (target) {
    case 'key':
      return `Watching the key ${name} in ${scopeText(scope)}, ${at}.`;
    case 'prefix':
      return `Watching the keys under ${name === '' ? 'the top' : name} in ${scopeText(scope)}, ${at}.`;
    case 'service':
      return `Watching the service ${name} in ${scopeText(scope)}, ${at}.`;
    case 'sessions':
      return `Watching the sessions, which belong to no scope, ${at}.`;
    case 'events':
      return "Watching the answering server's event journal; the consistency chosen does not apply to it.";
  }
}

export function WatchesView({ api, target, name }: { api: QraftApi; target?: WatchTarget | undefined; name?: string | undefined }) {
  const { scope, consistency, navigate } = useRouter();
  const { paused } = useLive();
  const [kind, setKind] = useState<WatchTarget>(target ?? 'key');
  const [text, setText] = useState(name ?? '');
  const [problem, setProblem] = useState<string | null>(null);
  const named = kind === 'key' || kind === 'prefix' || kind === 'service';

  const start = () => {
    if (kind === 'key' && text === '') return setProblem('Name the key to watch.');
    if (kind === 'service' && text.trim() === '') return setProblem('Name the service to watch.');
    setProblem(null);
    navigate(named ? { view: 'watches', target: kind, name: kind === 'service' ? text.trim() : text }
      : { view: 'watches', target: kind });
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Watches</Typography.Title>
      <Typography.Text>
        A watch runs in this tab alone: the server keeps no registration of it, and it ends when the tab closes or another
        watch starts.
      </Typography.Text>
      <Space wrap>
        <label>
          {'Watch '}
          <select aria-label="Watch" value={kind} onChange={(event) => {
            setKind(TARGETS.find(([value]) => value === event.target.value)?.[0] ?? 'key');
            setProblem(null);
          }}>
            {TARGETS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        {named && (
          <label>
            {kind === 'prefix' ? 'Prefix ' : 'Name '}
            <input aria-label="Watched name" value={text} onChange={(event) => setText(event.target.value)} />
          </label>
        )}
        <button type="button" onClick={start}>Start watching</button>
      </Space>
      {problem !== null && <Typography.Text type="danger">{problem}</Typography.Text>}
      {target === undefined && <Typography.Text>Choose what to watch, and start the watch.</Typography.Text>}
      {target !== undefined && paused && <Typography.Text type="warning">{PAUSED}</Typography.Text>}
      {target !== undefined && (
        <Watch key={`${target}:${name ?? ''}:${scope.tenant}/${scope.namespace}:${consistency}`} api={api}
          target={target} name={name ?? ''} scope={scope} consistency={consistency} />
      )}
    </Space>
  );
}

/** One watch and its log, from its start until the page leaves it. */
function Watch({ api, target, name, scope, consistency }: {
  api: QraftApi;
  target: WatchTarget;
  name: string;
  scope: Scope;
  consistency: Consistency;
}) {
  const { may } = useAccess();
  const { scheduler } = useLive();
  const [rows, setRows] = useState<WatchRow[]>([]);
  const count = useRef(0);
  const add = useCallback<Add>((kind, text, index) => {
    count.current += 1;
    const row: WatchRow = { id: count.current, at: scheduler.now?.() ?? Date.now(), index, kind, text };
    setRows((logged) => withRow(logged, row));
  }, [scheduler]);
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    add('start', startText(target, name, scope, consistency), null);
  }, [add, target, name, scope, consistency]);

  let watch: JSX.Element | null;
  switch (target) {
    case 'key':
      watch = <IndexedWatch<KvEntry | null> add={add} deps={[api, name, scope.tenant, scope.namespace, consistency]}
        load={(wait) => api.kvEntry(name, scope, { consistency, ...wait })} describe={keyText} />;
      break;
    case 'prefix':
      watch = <IndexedWatch<string[]> add={add} deps={[api, name, scope.tenant, scope.namespace, consistency]}
        load={(wait) => api.kvKeys(name, scope, { consistency, ...wait })}
        describe={(before, after) => `${plural(after.length, 'key')} under ${name === '' ? 'the top' : name}${
          changesOf(before, after, 'added', 'removed')}.`} />;
      break;
    case 'service':
      watch = <IndexedWatch<HealthEntry[]> add={add} deps={[api, name, scope.tenant, scope.namespace, consistency]}
        load={(wait) => api.serviceHealth(name, scope, { consistency, ...wait })} describe={serviceText} />;
      break;
    case 'sessions':
      watch = <IndexedWatch<Sessions> add={add} deps={[api, consistency]}
        load={(wait) => api.sessions({ consistency, ...wait })}
        describe={(before, after) => `${plural(after.Sessions.length, 'session')}${changesOf(
          before?.Sessions.map((session) => session.ID) ?? null, after.Sessions.map((session) => session.ID),
          'created', 'ended')}.`} />;
      break;
    case 'events':
      watch = may.operatorRead ? <EventsWatch api={api} add={add} />
        : <Typography.Text>The event stream is watched with operator:read.</Typography.Text>;
      break;
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {watch}
      <PlainTable<WatchRow>
        label="Watch log"
        rows={rows}
        rowKey={(row) => String(row.id)}
        empty="Nothing logged yet."
        columns={[
          { title: 'When', render: (row) => formatInstant(new Date(row.at).toISOString()) },
          { title: 'What happened', render: (row) => KINDS[row.kind] },
          { title: 'Index', render: (row) => (row.index === null ? '—' : String(row.index)) },
          { title: 'Detail', render: (row) => row.text },
        ]}
      />
      <Typography.Text type="secondary">The log keeps its last 500 rows, newest first.</Typography.Text>
    </Space>
  );
}

function keyText(_before: KvEntry | null, entry: KvEntry | null): string {
  if (entry === null) return 'The key does not exist.';
  const size = valueOf(entry.Value).size;
  const holder = entry.Session === undefined ? 'no session holds it' : `held by session ${entry.Session}`;
  return `Modified at index ${entry.ModifyIndex}; ${size === 1 ? '1 byte' : `${size} bytes`}; ${holder}.`;
}

function serviceText(_before: HealthEntry[] | null, entries: HealthEntry[]): string {
  const counts: Partial<Record<Lowercase<HealthEntry['service']['health']>, number>> = {};
  for (const item of entries) {
    const health = item.service.health.toLowerCase() as Lowercase<HealthEntry['service']['health']>;
    counts[health] = (counts[health] ?? 0) + 1;
  }
  const summary = healthCountsText(counts);
  return `${plural(entries.length, 'instance')}${summary === '' ? '' : `: ${summary}`}.`;
}

/**
 * A blocking query followed live, logging a delivery when its index moves and a resynchronization when it goes back,
 * after which the state is described afresh rather than as a change.
 */
function IndexedWatch<T>({ load, deps, describe, add }: {
  load: (wait: LiveWait) => Promise<Read<T>>;
  deps: DependencyList;
  describe: (before: T | null, after: T) => string;
  add: Add;
}) {
  const [state] = useLiveRead(load, deps, (read) => read.index);
  const last = useRef<Read<T> | null>(null);
  useEffect(() => {
    if (state.status === 'failed') {
      const reason = state.error instanceof Error ? state.error.message : String(state.error);
      add('failure', `The read failed: ${reason}. It is tried again.`, null);
      return;
    }
    if (state.status !== 'ready' || state.value === last.current) return;
    const before = last.current;
    const now = state.value;
    last.current = now;
    if (state.resynchronized && before !== null) {
      add('resynchronized', `The index went back from ${before.index} to ${now.index}: the state was read afresh, `
        + 'and the watch resumed from the new index.', now.index);
      add('delivery', describe(null, now.data), now.index);
      return;
    }
    if (before !== null && before.index === now.index) return;
    add('delivery', describe(before?.data ?? null, now.data), now.index);
  }, [state, add]);
  return null;
}

/** The answering server's event stream: the journal as read, then each event once, and every resynchronization. */
function EventsWatch({ api, add }: { api: QraftApi; add: Add }) {
  const [feed] = useEventFeed(api, {}, []);
  const seen = useRef<Set<string> | null>(null);
  const phase = useRef(feed.phase);
  useEffect(() => {
    const was = phase.current;
    phase.current = feed.phase;
    if (feed.phase === 'reading' && was !== 'reading' && feed.missed !== null) add('resynchronized', MISSED[feed.missed], null);
    if (feed.phase === 'retrying' && was !== 'retrying') {
      add('failure', 'The watch failed, and is tried again from its last event.', null);
    }
    if (feed.phase === 'reading') return;
    if (seen.current === null) {
      seen.current = new Set(feed.events.map((event) => event.cursor));
      add('delivery', `Read the journal: ${plural(feed.events.length, 'event')}.`, null);
      return;
    }
    for (const event of feed.events) {
      if (seen.current.has(event.cursor)) continue;
      seen.current.add(event.cursor);
      add('delivery', event.type, event.committedIndex ?? null);
    }
  }, [feed, add]);
  return null;
}
