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
import { type ReactNode, useState } from 'react';
import { useAccess } from '../access';
import type { QraftApi } from '../api/client';
import type { HeldKey, SessionEntry, Sessions } from '../api/schemas';
import { behaviourText, endReason, heldBy, lockDelayText, timeLeftText } from '../api/sessions';
import { useEventFeed } from '../eventFeed';
import { useLiveRead, useNow } from '../live';
import { RouteLink, useRouter } from '../routing';
import { Failure, formatInstant, PlainTable, ReadMeta } from './common';
import { EphemeralNote, EventsTable, FeedState } from './EventsTable';
import { CreateSession, SessionChanges } from './SessionChanges';

// The Sessions pages (UI/UX design section 7.2; UI-6 Sessions, Locks and Watches task list, Phases 1 and 2): every
// session, which belongs to no scope (decision 1), with the leader's timers (decision 2) and the keys each holds; and a
// session's page, with its held keys and recent events. Both follow the server live in the consistency chosen.

const GLOBAL = 'Sessions belong to no tenant or namespace: the scope selected does not apply to them.';
const FOLLOWER = 'This server does not lead, so it cannot say when sessions lapse: only the leader keeps their timers.';

/** A session's time left, by the interface's clock, or why it cannot be told. */
function timeLeft(session: SessionEntry, sessions: Sessions, now: number): string {
  if (session.TTL === '') return 'No TTL';
  if (!sessions.TtlKnown || session.ExpiresAt === null) return 'Known to the leader';
  return timeLeftText(Date.parse(session.ExpiresAt) - now);
}

export function SessionsView({ api }: { api: QraftApi }) {
  const { consistency } = useRouter();
  const now = useNow();
  const [node, setNode] = useState('');
  const [state, again] = useLiveRead((wait) => api.sessions({ consistency, ...wait }), [api, consistency],
    (read) => read.index);
  const [locks] = useLiveRead((wait) => api.locks({}, { consistency, ...wait }), [api, consistency],
    (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const heldShown = locks.status === 'ready' ? locks.value : locks.previous;
  const held = heldShown === undefined ? null : heldBy(heldShown.data.Held);
  const wanted = node.trim();
  const rows = shown?.data.Sessions.filter((session) => wanted === '' || session.Node === wanted) ?? [];
  let empty = shown?.filtered === true ? 'No session exists that your token may read.' : 'No session exists.';
  if (wanted !== '') empty = `No session is bound to node ${wanted}.`;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Sessions</Typography.Title>
      <Typography.Text>{GLOBAL}</Typography.Text>
      {state.status === 'failed' && <Failure title="The sessions could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown?.data.TtlKnown === false && <Typography.Text type="warning">{FOLLOWER}</Typography.Text>}
      {shown?.filtered === true && (
        <Typography.Text>The server left out the sessions your token may not read.</Typography.Text>
      )}
      <CreateSession api={api} />
      <label>
        {'Node '}
        <input aria-label="Node" value={node} onChange={(event) => setNode(event.target.value)} />
      </label>
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the sessions…</Typography.Text>}
      {shown !== undefined && (
        <PlainTable<SessionEntry>
          label="Sessions"
          rows={rows}
          rowKey={(session) => session.ID}
          empty={empty}
          columns={[
            { title: 'ID', render: (session) => <RouteLink to={{ view: 'sessionInfo', id: session.ID }}>{session.ID}</RouteLink> },
            { title: 'Name', render: (session) => session.Name },
            { title: 'Node', render: (session) => session.Node },
            { title: 'Behaviour', render: (session) => behaviourText(session.Behavior) },
            { title: 'TTL', render: (session) => (session.TTL === '' ? 'None' : session.TTL) },
            { title: 'Lock delay', render: (session) => lockDelayText(session.LockDelay) },
            { title: 'Time left', render: (session) => timeLeft(session, shown.data, now) },
            { title: 'Keys held', render: (session) => (held === null ? '…' : String(held.get(session.ID) ?? 0)) },
            { title: 'Created at index', render: (session) => String(session.CreateIndex) },
          ]}
        />
      )}
    </Space>
  );
}

/** When a session lapses, by the leader's timer and the interface's clock, or why that cannot be told. */
function lapses(session: SessionEntry, sessions: Sessions, now: number): string {
  if (session.TTL === '') return 'Never: it has no TTL';
  if (!sessions.TtlKnown || session.ExpiresAt === null) return 'Known to the leader only';
  const left = Date.parse(session.ExpiresAt) - now;
  return `${formatInstant(session.ExpiresAt)}, ${left >= 1000 ? `in ${timeLeftText(left)} unless renewed` : 'due to lapse now'}`;
}

function renewed(session: SessionEntry, sessions: Sessions): string {
  if (session.TTL === '') return 'Never: it has no TTL';
  if (!sessions.TtlKnown) return 'Known to the leader only';
  return session.RenewedAt === null ? 'Not renewed since this leader was elected' : formatInstant(session.RenewedAt);
}

export function SessionView({ api, id }: { api: QraftApi; id: string }) {
  const { consistency } = useRouter();
  const { may } = useAccess();
  const now = useNow();
  const [state, again] = useLiveRead((wait) => api.sessions({ consistency, ...wait }), [api, consistency],
    (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const session = shown?.data.Sessions.find((candidate) => candidate.ID === id);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{`Session ${session === undefined || session.Name === '' ? id : session.Name}`}</Typography.Title>
      <RouteLink to={{ view: 'sessions' }}>All sessions</RouteLink>
      {state.status === 'failed' && <Failure title="The session could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the session…</Typography.Text>}
      {shown !== undefined && session === undefined && (
        <Typography.Text>{`No session ${id} exists: it has ended, or never existed.`}</Typography.Text>
      )}
      {shown !== undefined && session !== undefined && (
        <>
          <SessionFields fields={[
            ['ID', session.ID],
            ['Name', session.Name === '' ? 'None' : session.Name],
            ['Node', session.Node],
            ['Behaviour', behaviourText(session.Behavior)],
            ['TTL', session.TTL === '' ? 'None' : session.TTL],
            ['Lock delay', lockDelayText(session.LockDelay)],
            ['Created at index', String(session.CreateIndex)],
            ['Last renewed', renewed(session, shown.data)],
            ['Lapses', lapses(session, shown.data, now)],
          ]} />
          {!shown.data.TtlKnown && <Typography.Text type="warning">{FOLLOWER}</Typography.Text>}
          <SessionChanges api={api} session={session} />
          <Typography.Title level={3}>Held keys</Typography.Title>
          <HeldKeys api={api} id={id} />
        </>
      )}
      <Typography.Title level={3}>Recent events</Typography.Title>
      {may.operatorRead ? <SessionEvents api={api} id={id} gone={shown !== undefined && session === undefined} />
        : <Typography.Text>The session's events are listed with operator:read.</Typography.Text>}
    </Space>
  );
}

function SessionFields({ fields }: { fields: [string, ReactNode][] }) {
  return (
    <dl style={{ display: 'grid', gridTemplateColumns: 'max-content auto', columnGap: 16, rowGap: 4 }}>
      {fields.map(([label, value]) => (
        <div key={label} style={{ display: 'contents' }}>
          <dt style={{ fontWeight: 600 }}>{label}</dt>
          <dd style={{ margin: 0 }} data-field={label}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** The keys a session holds, each linked to its entry in its own scope (decision 1). */
function HeldKeys({ api, id }: { api: QraftApi; id: string }) {
  const { consistency } = useRouter();
  const [state, again] = useLiveRead((wait) => api.locks({ session: id }, { consistency, ...wait }),
    [api, id, consistency], (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  if (state.status === 'failed' && shown === undefined) {
    return <Failure title="The keys it holds could not be read" error={state.error} onRetry={again} />;
  }
  if (shown === undefined) return <Typography.Text>Reading the keys it holds…</Typography.Text>;
  return (
    <PlainTable<HeldKey>
      label="Held keys"
      rows={shown.data.Held}
      rowKey={(held) => `${held.Tenant}/${held.Namespace}/${held.Key}`}
      empty={shown.filtered ? 'It holds no key your token may read.' : 'It holds no key.'}
      columns={[
        { title: 'Key', render: (held) => (
          <RouteLink to={{ view: 'kv', path: held.Key }} scope={{ tenant: held.Tenant, namespace: held.Namespace }}>
            {held.Key}
          </RouteLink>
        ) },
        { title: 'Scope', render: (held) => `${held.Tenant}/${held.Namespace}` },
        { title: 'Lock index', render: (held) => String(held.LockIndex) },
        { title: 'Modified at index', render: (held) => String(held.ModifyIndex) },
      ]}
    />
  );
}

/** The session's events, as the answering server's journal holds them (decision 6), and why it ended once gone. */
function SessionEvents({ api, id, gone }: { api: QraftApi; id: string; gone: boolean }) {
  const [feed] = useEventFeed(api, { session: id }, [id]);
  const end = [...feed.events].reverse().find((event) => event.type === 'session.destroyed' && event.event.id === id);
  const reason = typeof end?.event.reason === 'string' ? end.event.reason : null;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {gone && reason !== null && <Typography.Text>{endReason(reason)}</Typography.Text>}
      <EphemeralNote feed={feed} />
      <FeedState feed={feed} />
      <EventsTable
        events={feed.events}
        paged
        empty={feed.phase === 'reading' ? 'Reading the journal…' : `The journal holds no event of session ${id}.`}
      />
    </Space>
  );
}
