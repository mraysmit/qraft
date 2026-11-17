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
import { useAccess } from '../access';
import type { QraftApi, SessionBody } from '../api/client';
import type { SessionEntry } from '../api/schemas';
import { durationMillis, lockDelayText } from '../api/sessions';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { Mutation } from './Mutation';

// A session's changes (UI-6 Sessions, Locks and Watches task list, Phase 2): a creation on a node the catalog has,
// checked before its review (decision 7); a renewal; and a destruction after a review of the keys it holds (decision
// 8). Each needs session:write on the session's node, asked through the authorize call.

/** The server's limits on a session's TTL and lock delay, in milliseconds (sessions list, Phase 3). */
const MIN_TTL = 10_000;
const MAX_TTL = 86_400_000;
const MAX_LOCK_DELAY = 60_000;

const button = { marginRight: 8 };

/** A length of time in seconds, as a review gives it: `60 s`. */
const secondsText = (millis: number) => `${Number((millis / 1000).toFixed(3))} s`;

/** Whether the token may write sessions on `node`; null until answered or while no node is named. Without access control, always. */
export function useSessionWrite(api: QraftApi, node: string): boolean | null {
  const access = useAccess();
  const [state] = useRead(async () => {
    if (node === '') return null;
    if (access.mode === 'none') return true;
    const answers = await api.authorize([{ resource: 'session', segment: node, access: 'write' }]);
    return answers.data[0]?.allow === true;
  }, [api, node, access.mode]);
  return state.status === 'ready' ? state.value : null;
}

/** What a draft breaks of the server's rules, before any review (decision 7). */
function problemsOf(draft: SessionBody): string[] {
  const problems: string[] = [];
  if (draft.Node === '') problems.push('Choose the node the session is bound to.');
  const ttl = draft.TTL.trim() === '' ? 0 : durationMillis(draft.TTL.trim());
  if (ttl === null || (ttl !== 0 && (ttl < MIN_TTL || ttl > MAX_TTL))) {
    problems.push('A TTL is a duration from 10s to 24h, such as 30s, or none.');
  }
  const delay = draft.LockDelay.trim() === '0' ? 0 : durationMillis(draft.LockDelay.trim());
  if (delay === null || delay > MAX_LOCK_DELAY) problems.push('A lock delay is a duration from 0s to 60s, such as 15s.');
  return problems;
}

/** What a behaviour does to the keys a session holds when it ends, with its lock delay. */
function endText(behaviour: SessionBody['Behavior'], lockDelayMillis: number, holding: string): string {
  if (behaviour === 'delete') return `${holding} deleted.`;
  return lockDelayMillis === 0 ? `${holding} released, keeping their values.`
    : `${holding} released, keeping their values, and none can be acquired again for ${secondsText(lockDelayMillis)}.`;
}

type CreatePanel = { kind: 'none' } | { kind: 'form' | 'review'; draft: SessionBody; problems: string[] };

/** The Sessions page's creation: a node from the catalog, the session's settings, a review, and one submission. */
export function CreateSession({ api }: { api: QraftApi }) {
  const [panel, setPanel] = useState<CreatePanel>({ kind: 'none' });
  const node = panel.kind === 'none' ? '' : panel.draft.Node;
  const writable = useSessionWrite(api, node);
  const close = () => setPanel({ kind: 'none' });
  if (panel.kind === 'none') {
    return (
      <div>
        <button type="button" style={button} onClick={() => setPanel({ kind: 'form', problems: [],
          draft: { Node: '', Name: '', TTL: '', LockDelay: '15s', Behavior: 'release' } })}>Create a session</button>
      </div>
    );
  }
  const { draft } = panel;
  if (panel.kind === 'review') {
    const ttl = draft.TTL.trim() === '' ? 0 : durationMillis(draft.TTL.trim()) ?? 0;
    const delay = draft.LockDelay.trim() === '0' ? 0 : durationMillis(draft.LockDelay.trim()) ?? 0;
    const body: SessionBody = { ...draft, TTL: draft.TTL.trim(), LockDelay: draft.LockDelay.trim() };
    return (
      <Mutation<{ ID: string }>
        title={`Create a session on ${draft.Node}`}
        identity={[['Node', draft.Node], ['Name', draft.Name === '' ? 'None' : draft.Name]]}
        review={<>
          <p>{ttl === 0 ? 'It has no TTL: it lasts until it is destroyed, or its node fails or leaves.'
            : `It lapses unless renewed within twice its TTL, ${secondsText(2 * ttl)}; the interface does not renew it.`}</p>
          <p>{endText(draft.Behavior, delay, 'When it ends, the keys it holds are')}</p>
          <p>It is created once: an unknown outcome is settled by reading the sessions, never by sending it again.</p>
        </>}
        action="Create"
        idempotent={false}
        submit={() => api.createSession(body)}
        committed={(read) => <p><RouteLink to={{ view: 'sessionInfo', id: read.data.ID }}>Open the session</RouteLink></p>}
        onClose={close}
      />
    );
  }
  const set = (changes: Partial<SessionBody>) => setPanel({ ...panel, draft: { ...draft, ...changes } });
  return (
    <Space direction="vertical">
      <Typography.Title level={3}>Create a session</Typography.Title>
      <NodeChoice api={api} node={draft.Node} onNode={(chosen) => set({ Node: chosen })} />
      {writable === false && (
        <Typography.Text type="danger">
          {`Your token may not create a session on ${draft.Node}: it needs session:write.`}
        </Typography.Text>
      )}
      <label>{'Name '}<input aria-label="Name" value={draft.Name} onChange={(event) => set({ Name: event.target.value })} /></label>
      <label>{'TTL '}<input aria-label="TTL" value={draft.TTL} placeholder="none, or 10s to 24h"
        onChange={(event) => set({ TTL: event.target.value })} /></label>
      <label>{'Lock delay '}<input aria-label="Lock delay" value={draft.LockDelay}
        onChange={(event) => set({ LockDelay: event.target.value })} /></label>
      <label>
        {'Behaviour '}
        <select aria-label="Behaviour" value={draft.Behavior}
          onChange={(event) => set({ Behavior: event.target.value === 'delete' ? 'delete' : 'release' })}>
          <option value="release">Release its keys</option>
          <option value="delete">Delete its keys</option>
        </select>
      </label>
      {panel.problems.map((problem) => <Typography.Text key={problem} type="danger">{problem}</Typography.Text>)}
      <div>
        <button type="button" style={button} onClick={() => {
          const problems = problemsOf(draft);
          setPanel({ ...panel, kind: problems.length === 0 && writable === true ? 'review' : 'form', problems });
        }}>Review</button>
        <button type="button" onClick={close}>Cancel</button>
      </div>
    </Space>
  );
}

/** The catalog's nodes to bind a session to: a session needs one that is registered. */
function NodeChoice({ api, node, onNode }: { api: QraftApi; node: string; onNode: (node: string) => void }) {
  const [nodes] = useRead(() => api.nodes(), [api]);
  const names = nodes.status === 'ready' ? nodes.value.data.map((known) => known.name).sort() : [];
  return (
    <label>
      {'Bound to node '}
      <select aria-label="Bound to node" value={node} onChange={(event) => onNode(event.target.value)}>
        <option value="">{nodes.status === 'loading' ? 'Reading the nodes…' : 'Choose a node'}</option>
        {names.map((name) => <option key={name} value={name}>{name}</option>)}
      </select>
      {nodes.status === 'failed' && <Typography.Text type="danger"> The nodes could not be read.</Typography.Text>}
    </label>
  );
}

type SessionPanel = { kind: 'none' | 'renew' | 'destroy' };

/** A session's renewal, when it has a TTL, and its destruction, behind session:write on its node. */
export function SessionChanges({ api, session }: { api: QraftApi; session: SessionEntry }) {
  const writable = useSessionWrite(api, session.Node);
  const [panel, setPanel] = useState<SessionPanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  if (writable === null) return null;
  if (!writable) {
    return (
      <Typography.Text>{`Your token may not change this session: it needs session:write on node ${session.Node}.`}</Typography.Text>
    );
  }
  const identity: [string, string][] = [['Session', session.ID], ['Node', session.Node]];
  if (panel.kind === 'renew') {
    const ttl = durationMillis(session.TTL) ?? 0;
    return (
      <Mutation<unknown>
        title={`Renew session ${session.ID}`}
        identity={identity}
        review={<p>{`It lapses twice its TTL, ${secondsText(2 * ttl)}, after the leader takes the renewal.`}</p>}
        action="Renew"
        idempotent
        submit={() => api.renewSession(session.ID)}
        detail={(outcome) => outcome.kind === 'not-found'
          ? <p>The session has ended: a renewal cannot bring it back.</p> : null}
        onClose={close}
      />
    );
  }
  if (panel.kind === 'destroy') {
    return (
      <Mutation<boolean>
        title={`Destroy session ${session.ID}`}
        identity={identity}
        review={<DestroyReview api={api} session={session} />}
        action="Destroy"
        idempotent
        submit={() => api.destroySession(session.ID)}
        onClose={close}
      />
    );
  }
  return (
    <div>
      {session.TTL !== '' && (
        <button type="button" style={button} onClick={() => setPanel({ kind: 'renew' })}>Renew</button>
      )}
      <button type="button" style={button} onClick={() => setPanel({ kind: 'destroy' })}>Destroy</button>
    </div>
  );
}

/** What a destruction does (decision 8): the keys the session holds, and what its behaviour does to them. */
function DestroyReview({ api, session }: { api: QraftApi; session: SessionEntry }) {
  const [locks] = useRead(() => api.locks({ session: session.ID }), [api, session.ID]);
  let holding: string;
  if (locks.status === 'loading') holding = 'Reading the keys it holds…';
  else if (locks.status === 'failed') holding = 'The keys it holds could not be read.';
  else {
    const held = locks.value.data.Held;
    holding = held.length === 0 ? 'It holds no key.'
      : `It holds ${held.length === 1 ? '1 key' : `${held.length} keys`}: `
        + `${held.map((key) => `${key.Key} in ${key.Tenant}/${key.Namespace}`).join(', ')}.`;
    if (locks.value.filtered) holding += ' The server left out keys your token may not read.';
  }
  return (
    <>
      <p>{holding}</p>
      <p>{endText(session.Behavior, session.LockDelay / 1_000_000, 'They are')}</p>
      <p>{`A process that held them through this session loses them at once; its lock delay is ${lockDelayText(session.LockDelay)}.`}</p>
    </>
  );
}
