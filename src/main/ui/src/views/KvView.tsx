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
import type { ReactNode } from 'react';
import { useAccess } from '../access';
import type { QraftApi, Scope } from '../api/client';
import { exactFlags, levelOf, type LevelItem, valueOf } from '../api/kv';
import type { KvEntry } from '../api/schemas';
import { useEventFeed } from '../eventFeed';
import { useLiveRead } from '../live';
import { RouteLink, useRouter } from '../routing';
import { Failure, PlainTable, ReadMeta, scopeText } from './common';
import { EphemeralNote, EventsTable, FeedState } from './EventsTable';
import { EntryChanges, FolderChanges } from './KvChanges';

/**
 * The Key/Value pages (UI/UX design section 7.1; UI-6 Key/Value task list): a folder, empty or ending in `/`, lists
 * one level of its folders and keys; a key shows its entry. Both follow the server live, in the selected scope and
 * consistency. A value is shown only on its entry's page (decision 4). Each offers its changes with `key:write`
 * (Phase 2).
 */
export function KvView({ api, scope, path }: { api: QraftApi; scope: Scope; path: string }) {
  return path === '' || path.endsWith('/')
    ? <Folder api={api} scope={scope} prefix={path} />
    : <Entry api={api} scope={scope} keyName={path} />;
}

/** The folders above `path` and, for a folder, the folder itself: each a link, the top first. */
function Breadcrumbs({ path }: { path: string }) {
  const folders: string[] = [];
  for (let slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
    folders.push(path.slice(0, slash + 1));
  }
  return (
    <nav aria-label="Folders">
      <Space split="/" wrap>
        <RouteLink to={{ view: 'kv', path: '' }}>Key/Value</RouteLink>
        {folders.map((folder, at) => (
          <RouteLink key={folder} to={{ view: 'kv', path: folder }}>
            {folder.slice(at === 0 ? 0 : (folders[at - 1] ?? '').length)}
          </RouteLink>
        ))}
      </Space>
    </nav>
  );
}

function Folder({ api, scope, prefix }: { api: QraftApi; scope: Scope; prefix: string }) {
  const { consistency } = useRouter();
  const [state, again] = useLiveRead(
    (wait) => api.kvLevel(prefix, scope, { consistency, ...wait }),
    [api, prefix, scope.tenant, scope.namespace, consistency],
    (read) => read.index,
  );
  const shown = state.status === 'ready' ? state.value : state.previous;
  const where = prefix === '' ? `in ${scopeText(scope)}` : `under ${prefix}`;
  const empty = shown?.filtered === true ? `No key is stored ${where} that your token may read.`
    : `No key is stored ${where}.`;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Key/Value</Typography.Title>
      <Breadcrumbs path={prefix} />
      <Typography.Text>{`Keys in ${scopeText(scope)}.`}</Typography.Text>
      {state.status === 'failed' && <Failure title="The keys could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown?.filtered === true && (
        <Typography.Text>The server left out the keys your token may not read.</Typography.Text>
      )}
      <FolderChanges api={api} scope={scope} prefix={prefix} />
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the keys…</Typography.Text>}
      {shown !== undefined && (
        <PlainTable<LevelItem>
          label="Keys"
          rows={levelOf(prefix, shown.data)}
          rowKey={(item) => item.path}
          empty={empty}
          columns={[
            { title: 'Name', render: (item) => <RouteLink to={{ view: 'kv', path: item.path }}>{item.name}</RouteLink> },
            { title: 'Kind', render: (item) => (item.folder ? 'Folder' : 'Key') },
          ]}
        />
      )}
    </Space>
  );
}

function Entry({ api, scope, keyName }: { api: QraftApi; scope: Scope; keyName: string }) {
  const { consistency } = useRouter();
  const { may } = useAccess();
  const [state, again] = useLiveRead(
    (wait) => api.kvEntry(keyName, scope, { consistency, ...wait }),
    [api, keyName, scope.tenant, scope.namespace, consistency],
    (read) => read.index,
  );
  const shown = state.status === 'ready' ? state.value : state.previous;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{keyName}</Typography.Title>
      <Breadcrumbs path={keyName} />
      {state.status === 'failed' && <Failure title="The key could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the key…</Typography.Text>}
      {shown !== undefined && shown.data === null && (
        <Typography.Text>{`No key ${keyName} is stored in ${scopeText(scope)}.`}</Typography.Text>
      )}
      {shown !== undefined && shown.data !== null && <EntryFields entry={shown.data} scope={scope} />}
      {shown !== undefined && shown.data !== null && <EntryChanges api={api} scope={scope} entry={shown.data} />}
      <Typography.Title level={3}>Recent events</Typography.Title>
      {may.operatorRead ? <KeyEvents api={api} keyName={keyName} scope={scope} />
        : <Typography.Text>The key's events are listed with operator:read.</Typography.Text>}
    </Space>
  );
}

function EntryFields({ entry, scope }: { entry: KvEntry; scope: Scope }) {
  const value = valueOf(entry.Value);
  const fields: [string, ReactNode][] = [
    ['Scope', `${scope.tenant}/${scope.namespace}`],
    ['Size', value.size === 1 ? '1 byte' : `${value.size} bytes`],
    ['Flags', exactFlags(entry.Flags) ? String(entry.Flags)
      : `Above ${Number.MAX_SAFE_INTEGER}, which the browser cannot show exactly`],
    ['Created at index', String(entry.CreateIndex)],
    ['Modified at index', String(entry.ModifyIndex)],
    ['Lock index', String(entry.LockIndex)],
    ['Session', entry.Session === undefined ? 'None holds it' : `Held by ${entry.Session}`],
  ];
  const pre = { margin: 0, padding: 12, background: '#fafafa', whiteSpace: 'pre-wrap' as const, wordBreak: 'break-all' as const };
  return (
    <>
      {value.kind === 'binary' && <Typography.Text>Binary, not UTF-8 text: shown in base64.</Typography.Text>}
      {value.kind === 'text' && value.json !== null && <Typography.Text>Laid out as JSON; stored as written.</Typography.Text>}
      <pre aria-label="Value" style={pre}>{value.kind === 'binary' ? value.base64 : value.json ?? value.text}</pre>
      <dl style={{ display: 'grid', gridTemplateColumns: 'max-content auto', columnGap: 16, rowGap: 4 }}>
        {fields.map(([label, shown]) => (
          <div key={label} style={{ display: 'contents' }}>
            <dt style={{ fontWeight: 600 }}>{label}</dt>
            <dd style={{ margin: 0 }} data-field={label}>{shown}</dd>
          </div>
        ))}
      </dl>
    </>
  );
}

/** The key's events in its scope, as the answering server's journal holds them (decision 8), followed live. */
function KeyEvents({ api, keyName, scope }: { api: QraftApi; keyName: string; scope: Scope }) {
  const [feed] = useEventFeed(api, { tenant: scope.tenant, namespace: scope.namespace, key: keyName },
    [keyName, scope.tenant, scope.namespace]);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <EphemeralNote feed={feed} />
      <FeedState feed={feed} />
      <EventsTable
        events={feed.events}
        paged
        empty={feed.phase === 'reading' ? 'Reading the journal…' : `The journal holds no event of ${keyName} in ${scopeText(scope)}.`}
      />
    </Space>
  );
}
