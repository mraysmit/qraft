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
import { useState } from 'react';
import type { QraftApi, Read, Scope } from '../api/client';
import { exactFlags, valueOf } from '../api/kv';
import type { KvEntry, SessionEntry } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { formatInstant } from './common';
import { type Outcome, Mutation } from './Mutation';
import { useSessionWrite } from './SessionChanges';

// A key acquired and released through a session on its entry's page (UI-6 Sessions, Locks and Watches task list,
// decision 9). Acquiring needs key:write on the key; releasing also needs session:write on the holding session's node,
// and is made only with the session that holds the key, never another, and never by a plain write.

const NOT_ACQUIRED: Outcome = { kind: 'conflict', code: 'not_acquired',
  message: 'another session holds the key, or its lock delay has not ended' };
const NOT_RELEASED: Outcome = { kind: 'conflict', code: 'not_released', message: 'the session no longer holds the key' };

const refusedWhenFalse = (refused: Outcome) => (answer: Read<boolean>) => (answer.data ? null : refused);
const button = { marginRight: 8 };

type Panel = { kind: 'none' } | { kind: 'choose'; session: string } | { kind: 'acquire'; session: string } | { kind: 'release' };

export function LockChanges({ api, scope, entry, writable }: {
  api: QraftApi;
  scope: Scope;
  entry: KvEntry;
  /** Whether the token may write the key, asked once for the page; null until answered. */
  writable: boolean | null;
}) {
  const keyName = entry.Key;
  const [sessions] = useRead(() => api.sessions(), [api, entry.Session ?? '']);
  const holder = entry.Session;
  const holderNode = holder === undefined || sessions.status !== 'ready' ? ''
    : sessions.value.data.Sessions.find((session) => session.ID === holder)?.Node ?? '';
  const releasable = useSessionWrite(api, holderNode);
  const [panel, setPanel] = useState<Panel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  if (writable !== true) return null;
  const value = valueOf(entry.Value);
  if (value.kind === 'binary' || !exactFlags(entry.Flags)) {
    return <Typography.Text>This value cannot be written back exactly, so the interface does not acquire or release this key.</Typography.Text>;
  }
  const at = (session: string) => ({ flags: entry.Flags, session });
  const identity: [string, string][] = [['Key', keyName], ['Scope', `${scope.tenant}/${scope.namespace}`]];

  if (panel.kind === 'acquire') {
    return (
      <Mutation<boolean>
        title={`Acquire ${keyName}`}
        identity={[...identity, ['Session', panel.session]]}
        review={<>
          <p>{`Acquired for session ${panel.session}, if no other session holds it and no lock delay runs.`}</p>
          <p>{`Its value is written again as read at index ${entry.ModifyIndex}; a change made since is written over, `
            + 'as an acquisition takes no compare-and-set.'}</p>
        </>}
        action="Acquire"
        idempotent
        submit={() => api.kvAcquire(keyName, scope, value.text, at(panel.session))}
        judge={refusedWhenFalse(NOT_ACQUIRED)}
        detail={() => <AcquireRefusal api={api} keyName={keyName} scope={scope} />}
        onClose={close}
      />
    );
  }
  if (panel.kind === 'release' && holder !== undefined) {
    return (
      <Mutation<boolean>
        title={`Release ${keyName}`}
        identity={[...identity, ['Session', holder]]}
        review={<p>{`Released by session ${holder}, which holds it; its value is written again as read at index ${
          entry.ModifyIndex}. No lock delay starts, as one does when a session ends.`}</p>}
        action="Release"
        idempotent
        submit={() => api.kvRelease(keyName, scope, value.text, at(holder))}
        judge={refusedWhenFalse(NOT_RELEASED)}
        onClose={close}
      />
    );
  }
  if (panel.kind === 'choose') {
    return (
      <ChooseSession keyName={keyName} sessions={sessions.status === 'ready' ? sessions.value.data.Sessions : null}
        chosen={panel.session} onChoose={(session) => setPanel({ kind: 'choose', session })}
        onReview={() => setPanel({ kind: 'acquire', session: panel.session })} onCancel={close} />
    );
  }
  if (holder === undefined) {
    return (
      <div>
        <button type="button" style={button} onClick={() => setPanel({ kind: 'choose', session: '' })}>Acquire</button>
      </div>
    );
  }
  if (sessions.status !== 'ready') return null;
  if (holderNode === '') {
    return <Typography.Text>Releasing it needs session:write on the holding session's node, which your token cannot read.</Typography.Text>;
  }
  if (releasable === null) return null;
  if (!releasable) {
    return <Typography.Text>{`Releasing it needs session:write on node ${holderNode}, the holding session's node.`}</Typography.Text>;
  }
  return (
    <div>
      <button type="button" style={button} onClick={() => setPanel({ kind: 'release' })}>Release</button>
    </div>
  );
}

/** The session to acquire a key with, from those the token may read; null while they are read. */
function ChooseSession({ keyName, sessions, chosen, onChoose, onReview, onCancel }: {
  keyName: string;
  sessions: SessionEntry[] | null;
  chosen: string;
  onChoose: (session: string) => void;
  onReview: () => void;
  onCancel: () => void;
}) {
  return (
    <Space direction="vertical">
      <Typography.Title level={3}>{`Acquire ${keyName}`}</Typography.Title>
      <label>
        {'Acquire with session '}
        <select aria-label="Acquire with session" value={chosen} onChange={(event) => onChoose(event.target.value)}>
          <option value="">{sessions === null ? 'Reading the sessions…' : 'Choose a session'}</option>
          {(sessions ?? []).map((session) => (
            <option key={session.ID} value={session.ID}>
              {`${session.ID} (${session.Name === '' ? 'no name' : session.Name}, ${session.Node})`}
            </option>
          ))}
        </select>
      </label>
      <RouteLink to={{ view: 'sessions' }}>Create a session first</RouteLink>
      <div>
        <button type="button" style={button} disabled={chosen === ''} onClick={onReview}>Review</button>
        <button type="button" onClick={onCancel}>Cancel</button>
      </div>
    </Space>
  );
}

/** Why an acquisition was refused (decision 9): the session that holds the key now, or the lock delay that runs. */
function AcquireRefusal({ api, keyName, scope }: { api: QraftApi; keyName: string; scope: Scope }) {
  const [state] = useRead(async () => {
    const [entry, locks] = await Promise.all([api.kvEntry(keyName, scope), api.locks()]);
    return { entry: entry.data, delay: locks.data.Delays.find((delay) => delay.Key === keyName
      && delay.Tenant === scope.tenant && delay.Namespace === scope.namespace) };
  }, [api, keyName, scope.tenant, scope.namespace]);
  if (state.status === 'loading') return <p>Reading the key and its lock as they are now…</p>;
  if (state.status === 'failed') return <p>The key and its lock as they are now could not be read.</p>;
  const { entry, delay } = state.value;
  return (
    <>
      {entry?.Session !== undefined && <p>{`Session ${entry.Session} holds it.`}</p>}
      {delay !== undefined && <p>{`Its lock delay runs until ${formatInstant(delay.Until)}.`}</p>}
      {entry?.Session === undefined && delay === undefined && <p>Neither is the case now: it may be acquired.</p>}
    </>
  );
}
