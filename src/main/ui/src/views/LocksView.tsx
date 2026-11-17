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
import { useAccess } from '../access';
import type { QraftApi } from '../api/client';
import type { HeldKey, LockDelay, QraftEvent } from '../api/schemas';
import { endReason, timeLeftText } from '../api/sessions';
import { useEventFeed } from '../eventFeed';
import { useLiveRead, useNow } from '../live';
import { RouteLink, useRouter } from '../routing';
import { Failure, formatInstant, PlainTable, ReadMeta } from './common';
import { EphemeralNote } from './EventsTable';

// The Locks page (UI/UX design section 7.2; UI-6 Sessions, Locks and Watches task list, Phase 3): the keys sessions
// hold across every scope and the lock delays still running (decision 4), what the server cannot tell of contention
// (decision 5), and the recent invalidations from the answering server's journal. It follows the server live.

const keyOf = (key: { Tenant: string; Namespace: string; Key: string }) => `${key.Tenant}/${key.Namespace}/${key.Key}`;

/** A key, linked to its entry in its own scope. */
function KeyLink({ held }: { held: { Tenant: string; Namespace: string; Key: string } }) {
  return (
    <RouteLink to={{ view: 'kv', path: held.Key }} scope={{ tenant: held.Tenant, namespace: held.Namespace }}>
      {held.Key}
    </RouteLink>
  );
}

export function LocksView({ api }: { api: QraftApi }) {
  const { consistency } = useRouter();
  const { may } = useAccess();
  const now = useNow();
  const [state, again] = useLiveRead((wait) => api.locks({}, { consistency, ...wait }), [api, consistency],
    (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Locks</Typography.Title>
      <Typography.Text>Locks are read across every tenant and namespace: the scope selected does not apply to them.</Typography.Text>
      <Typography.Text>
        Who waits for a lock is not visible: a process waits with a blocking read of its own, which the server cannot count.
      </Typography.Text>
      {state.status === 'failed' && <Failure title="The locks could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown?.filtered === true && <Typography.Text>The server left out the keys your token may not read.</Typography.Text>}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the locks…</Typography.Text>}
      {shown !== undefined && (
        <>
          <Typography.Title level={3}>Held keys</Typography.Title>
          <PlainTable<HeldKey>
            label="Held keys"
            rows={shown.data.Held}
            rowKey={keyOf}
            empty={shown.filtered ? 'No key is held that your token may read.' : 'No key is held.'}
            columns={[
              { title: 'Key', render: (held) => <KeyLink held={held} /> },
              { title: 'Scope', render: (held) => `${held.Tenant}/${held.Namespace}` },
              { title: 'Session', render: (held) => (
                <RouteLink to={{ view: 'sessionInfo', id: held.Session }}>{held.Session}</RouteLink>
              ) },
              { title: 'Lock index', render: (held) => String(held.LockIndex) },
              { title: 'Modified at index', render: (held) => String(held.ModifyIndex) },
            ]}
          />
          <Typography.Title level={3}>Lock delays</Typography.Title>
          <Typography.Text type="secondary">
            A key freed by a session that ended cannot be acquired again until its delay ends.
          </Typography.Text>
          <PlainTable<LockDelay>
            label="Lock delays"
            rows={shown.data.Delays}
            rowKey={keyOf}
            empty="No lock delay is running."
            columns={[
              { title: 'Key', render: (delay) => <KeyLink held={delay} /> },
              { title: 'Scope', render: (delay) => `${delay.Tenant}/${delay.Namespace}` },
              { title: 'Until', render: (delay) => formatInstant(delay.Until) },
              { title: 'Time left', render: (delay) => {
                const left = Date.parse(delay.Until) - now;
                return left >= 1000 ? timeLeftText(left) : 'Ended';
              } },
            ]}
          />
        </>
      )}
      <Typography.Title level={3}>Recent invalidations</Typography.Title>
      {may.operatorRead ? <Invalidations api={api} />
        : <Typography.Text>Recent invalidations are listed with operator:read.</Typography.Text>}
    </Space>
  );
}

/** The sessions the answering server's journal saw end, newest first, each with why. */
function Invalidations({ api }: { api: QraftApi }) {
  const [feed] = useEventFeed(api, { type: 'session.destroyed' }, []);
  const ended = [...feed.events].reverse();
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <EphemeralNote feed={feed} />
      <PlainTable<QraftEvent>
        label="Recent invalidations"
        rows={ended}
        rowKey={(event) => event.cursor}
        empty={feed.phase === 'reading' ? 'Reading the journal…' : 'The journal holds no session that ended.'}
        columns={[
          { title: 'Session', render: (event) => {
            const id = typeof event.event.id === 'string' ? event.event.id : '';
            return id === '' ? '—' : <RouteLink to={{ view: 'sessionInfo', id }}>{id}</RouteLink>;
          } },
          { title: 'Why', render: (event) => (typeof event.event.reason === 'string' ? endReason(event.event.reason) : '—') },
          { title: 'When', render: (event) => formatInstant(event.observedAt) },
        ]}
      />
    </Space>
  );
}
