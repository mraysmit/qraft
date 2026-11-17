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
import type { QraftApi, Read, Scope } from '../api/client';
import { exactFlags, lineDiff, valueOf } from '../api/kv';
import type { KvEntry } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { type Outcome, Mutation } from './Mutation';

/** The server's limit on a request body, and so on a value (UI-6 Key/Value task list, decision 7). */
const MAX_VALUE_BYTES = 512 * 1024;

const STALE: Outcome = { kind: 'conflict', code: 'stale', message: 'the entry was changed after it was read' };
const EXISTS: Outcome = { kind: 'conflict', code: 'exists', message: 'a key of this name already exists' };

/** A stale compare-and-set answers false: the entry had moved on, and nothing was written (decision 5). */
const staleWhenFalse = (refused: Outcome) => (answer: Read<boolean>) => (answer.data ? null : refused);

const bytesOf = (text: string) => new TextEncoder().encode(text).length;
const bytesText = (count: number) => (count === 1 ? '1 byte' : `${count} bytes`);
const scopeLabel = (scope: Scope) => `${scope.tenant}/${scope.namespace}`;

/**
 * Whether the token may write `segment`, a key or a folder's prefix, in `scope`: asked once when the page opens
 * (decision 9); null until answered. Without access control, always.
 */
export function useKeyWrite(api: QraftApi, segment: string, scope: Scope): boolean | null {
  const access = useAccess();
  const [state] = useRead(async () => {
    if (access.mode === 'none') return true;
    const answers = await api.authorize([{ resource: 'key', segment, access: 'write', tenant: scope.tenant,
      namespace: scope.namespace }]);
    return answers.data[0]?.allow === true;
  }, [api, segment, scope.tenant, scope.namespace, access.mode]);
  return state.status === 'ready' ? state.value : null;
}

/** What a draft breaks of the server's rules, before any review: its size and flags, and a new key's name. */
function problemsOf(value: string, flags: string, name?: string): string[] {
  const problems: string[] = [];
  if (name !== undefined && (name === '' || name.endsWith('/'))) problems.push('A key name is not empty and does not end in /.');
  const size = bytesOf(value);
  if (size > MAX_VALUE_BYTES) problems.push(`The value is ${size} bytes, over the server limit of ${MAX_VALUE_BYTES}.`);
  if (!/^\d+$/.test(flags) || !exactFlags(Number(flags))) {
    problems.push(`Flags are a whole number from 0 to ${Number.MAX_SAFE_INTEGER}.`);
  }
  return problems;
}

const button = { marginRight: 8 };

/** The value and flags being written, with what is wrong with them. */
function Draft({ value, flags, onValue, onFlags, problems }: {
  value: string;
  flags: string;
  onValue: (value: string) => void;
  onFlags: (flags: string) => void;
  problems: string[];
}) {
  return (
    <>
      <textarea aria-label="New value" value={value} onChange={(event) => onValue(event.target.value)} rows={8}
        style={{ width: '100%', fontFamily: 'monospace' }} />
      <Typography.Text type="secondary">{`At most ${MAX_VALUE_BYTES} bytes; ${bytesText(bytesOf(value))} now.`}</Typography.Text>
      <label>
        {'Flags '}
        <input aria-label="Flags" value={flags} onChange={(event) => onFlags(event.target.value)} />
      </label>
      {problems.map((problem) => <Typography.Text key={problem} type="danger">{problem}</Typography.Text>)}
    </>
  );
}

/**
 * What a refused compare-and-set found (decision 5): the index the write expected and the one now held, and the line
 * difference from the value now held to the one sent.
 */
function StaleWrite({ api, keyName, scope, expected, sent }: {
  api: QraftApi;
  keyName: string;
  scope: Scope;
  expected: number;
  sent: string | null;
}) {
  const [state] = useRead(() => api.kvEntry(keyName, scope), [api, keyName, scope.tenant, scope.namespace]);
  if (state.status === 'loading') return <p>Reading the entry as it is now…</p>;
  if (state.status === 'failed') return <p>The entry as it is now could not be read.</p>;
  const current = state.value.data;
  if (current === null) return <p>{`Expected index ${expected}; the key no longer exists.`}</p>;
  const now = valueOf(current.Value);
  return (
    <>
      <p>{expected === 0 ? `Expected no key; one exists, at index ${current.ModifyIndex}.`
        : `Expected index ${expected}, now at ${current.ModifyIndex}.`}</p>
      {sent !== null && now.kind === 'text' && (
        <>
          <p>From the value now held to the one sent:</p>
          <pre aria-label="Difference" style={{ margin: 0, padding: 12, background: '#fafafa', whiteSpace: 'pre-wrap' }}>
            {lineDiff(now.text, sent).map((line) => `${{ same: ' ', removed: '-', added: '+' }[line.kind]} ${line.text}`)
              .join('\n')}
          </pre>
        </>
      )}
    </>
  );
}

type EntryPanel =
  | { kind: 'none' }
  | { kind: 'edit' | 'review'; base: KvEntry; value: string; flags: string; problems: string[] }
  | { kind: 'delete'; base: KvEntry };

/**
 * An entry's changes (decisions 5, 6, 9 and 10): its value and flags edited, and its deletion, each a compare-and-set
 * at the index the editor opened with. A live update never replaces the draft: the editor says the entry changed.
 */
export function EntryChanges({ api, scope, entry, writable }: {
  api: QraftApi;
  scope: Scope;
  entry: KvEntry;
  /** Whether the token may write the key, asked once for the page (`useKeyWrite`); null until answered. */
  writable: boolean | null;
}) {
  const keyName = entry.Key;
  const [panel, setPanel] = useState<EntryPanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  if (writable === null) return null;
  if (!writable) return <Typography.Text>Your token may not change this key: it needs key:write.</Typography.Text>;
  const identity: [string, string][] = [['Key', keyName], ['Scope', scopeLabel(scope)]];
  const changed = panel.kind !== 'none' && entry.ModifyIndex !== panel.base.ModifyIndex
    ? <Typography.Text type="warning">
      {`This entry changed at index ${entry.ModifyIndex} after you opened it: saving will be refused as stale.`}
    </Typography.Text>
    : null;

  if (panel.kind === 'edit') {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Title level={3}>{`Edit ${keyName}`}</Typography.Title>
        {changed}
        <Draft value={panel.value} flags={panel.flags} problems={panel.problems}
          onValue={(value) => setPanel({ ...panel, value })} onFlags={(flags) => setPanel({ ...panel, flags })} />
        <div>
          <button type="button" style={button} onClick={() => {
            const problems = problemsOf(panel.value, panel.flags);
            setPanel({ ...panel, kind: problems.length === 0 ? 'review' : 'edit', problems });
          }}>Review</button>
          <button type="button" onClick={close}>Cancel</button>
        </div>
      </Space>
    );
  }
  if (panel.kind === 'review') {
    const { base, value, flags } = panel;
    return (
      <Mutation<boolean>
        title={`Update ${keyName}`}
        identity={[...identity, ['Expected index', String(base.ModifyIndex)]]}
        review={<>
          <p>{`Value: ${bytesText(valueOf(base.Value).size)} → ${bytesText(bytesOf(value))}`}</p>
          <p>{`Flags: ${base.Flags} → ${Number(flags)}`}</p>
          <p>{`Saved only if the entry is still at index ${base.ModifyIndex}, as read.`}</p>
          {base.Session !== undefined && <p>{`It is held by session ${base.Session}: saving keeps the lock.`}</p>}
          {changed}
        </>}
        action="Save"
        idempotent
        submit={() => api.kvPut(keyName, scope, value, { flags: Number(flags), cas: base.ModifyIndex })}
        judge={staleWhenFalse(STALE)}
        detail={() => <StaleWrite api={api} keyName={keyName} scope={scope} expected={base.ModifyIndex} sent={value} />}
        refresh={() => currentState(api, keyName, scope)}
        onClose={close}
      />
    );
  }
  if (panel.kind === 'delete') {
    const { base } = panel;
    return (
      <Mutation<boolean>
        title={`Delete ${keyName}`}
        identity={[...identity, ['Expected index', String(base.ModifyIndex)]]}
        review={<>
          <p>{`The key and its value are removed, only if the entry is still at index ${base.ModifyIndex}, as read.`}</p>
          {base.Session !== undefined && <p>{`It is held by session ${base.Session}: deleting the key ends the lock.`}</p>}
          {changed}
        </>}
        action="Delete"
        idempotent
        submit={() => api.kvDelete(keyName, scope, base.ModifyIndex)}
        judge={staleWhenFalse(STALE)}
        detail={() => <StaleWrite api={api} keyName={keyName} scope={scope} expected={base.ModifyIndex} sent={null} />}
        refresh={() => currentState(api, keyName, scope)}
        onClose={close}
      />
    );
  }

  const value = valueOf(entry.Value);
  let editing: ReactNode;
  if (value.kind === 'binary') {
    editing = <Typography.Text>This value is binary, which the interface does not edit.</Typography.Text>;
  } else if (!exactFlags(entry.Flags)) {
    editing = <Typography.Text>These flags cannot be written back exactly, so the interface does not edit this entry.</Typography.Text>;
  } else {
    editing = (
      <button type="button" style={button} onClick={() => setPanel({ kind: 'edit', base: entry, value: value.text,
        flags: String(entry.Flags), problems: [] })}>Edit</button>
    );
  }
  return (
    <div>
      {editing}
      <button type="button" style={button} onClick={() => setPanel({ kind: 'delete', base: entry })}>Delete</button>
    </div>
  );
}

/** The entry as it is now, in words, for an unknown outcome. */
async function currentState(api: QraftApi, keyName: string, scope: Scope): Promise<string> {
  const current = (await api.kvEntry(keyName, scope)).data;
  return current === null ? `No key ${keyName} exists now.`
    : `The entry is at index ${current.ModifyIndex} now, its value ${bytesText(valueOf(current.Value).size)}.`;
}

type FolderPanel =
  | { kind: 'none' }
  | { kind: 'create' | 'review'; name: string; value: string; flags: string; problems: string[] }
  | { kind: 'delete' };

/**
 * A folder's changes (decision 9): a key created in it, only if none of its name exists, and, below the top, every
 * key under it deleted after a review that counts them.
 */
export function FolderChanges({ api, scope, prefix }: { api: QraftApi; scope: Scope; prefix: string }) {
  const writable = useKeyWrite(api, prefix, scope);
  const [panel, setPanel] = useState<FolderPanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  if (writable === null) return null;
  if (!writable) {
    return (
      <Typography.Text>
        {prefix === '' ? 'Your token may not write keys here: it needs key:write.'
          : `Your token may not write under ${prefix}: it needs key:write.`}
      </Typography.Text>
    );
  }

  if (panel.kind === 'create') {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Title level={3}>{prefix === '' ? 'Create a key' : `Create a key under ${prefix}`}</Typography.Title>
        <label>
          {`Name ${prefix}`}
          <input aria-label="Name" value={panel.name} onChange={(event) => setPanel({ ...panel, name: event.target.value })} />
        </label>
        <Draft value={panel.value} flags={panel.flags} problems={panel.problems}
          onValue={(value) => setPanel({ ...panel, value })} onFlags={(flags) => setPanel({ ...panel, flags })} />
        <div>
          <button type="button" style={button} onClick={() => {
            const problems = problemsOf(panel.value, panel.flags, panel.name);
            setPanel({ ...panel, kind: problems.length === 0 ? 'review' : 'create', problems });
          }}>Review</button>
          <button type="button" onClick={close}>Cancel</button>
        </div>
      </Space>
    );
  }
  if (panel.kind === 'review') {
    const keyName = prefix + panel.name;
    const { value, flags } = panel;
    return (
      <Mutation<boolean>
        title={`Create ${keyName}`}
        identity={[['Key', keyName], ['Scope', scopeLabel(scope)]]}
        review={<>
          <p>{`Value: ${bytesText(bytesOf(value))}; flags ${Number(flags)}.`}</p>
          <p>Created only if no key of this name exists.</p>
        </>}
        action="Create"
        idempotent
        submit={() => api.kvPut(keyName, scope, value, { flags: Number(flags), cas: 0 })}
        judge={staleWhenFalse(EXISTS)}
        detail={() => <StaleWrite api={api} keyName={keyName} scope={scope} expected={0} sent={value} />}
        refresh={() => currentState(api, keyName, scope)}
        committed={() => <p><RouteLink to={{ view: 'kv', path: keyName }}>{`Open ${keyName}`}</RouteLink></p>}
        onClose={close}
      />
    );
  }
  if (panel.kind === 'delete') return <DeleteFolder api={api} scope={scope} prefix={prefix} onClose={close} />;
  return (
    <div>
      <button type="button" style={button} onClick={() => setPanel({ kind: 'create', name: '', value: '', flags: '0',
        problems: [] })}>Create a key</button>
      {prefix !== '' && (
        <button type="button" style={button} onClick={() => setPanel({ kind: 'delete' })}>Delete this folder</button>
      )}
    </div>
  );
}

function DeleteFolder({ api, scope, prefix, onClose }: { api: QraftApi; scope: Scope; prefix: string; onClose: () => void }) {
  const [keys] = useRead(() => api.kvKeys(prefix, scope), [api, prefix, scope.tenant, scope.namespace]);
  let counted: string;
  if (keys.status === 'loading') counted = `Counting the keys under ${prefix}…`;
  else if (keys.status === 'failed') counted = `The keys under ${prefix} could not be counted.`;
  else {
    const count = keys.value.data.length;
    counted = `${count === 1 ? '1 key' : `${count} keys`} under ${prefix} are deleted, and any key written under it before `
      + 'the deletion is applied.';
  }
  return (
    <Mutation<boolean>
      title={`Delete the folder ${prefix}`}
      identity={[['Folder', prefix], ['Scope', scopeLabel(scope)]]}
      review={<>
        <p>{counted}</p>
        <p>The server refuses it unless your token may write every key under the folder.</p>
      </>}
      action="Delete"
      idempotent
      submit={() => api.kvDeleteTree(prefix, scope)}
      onClose={onClose}
    />
  );
}
