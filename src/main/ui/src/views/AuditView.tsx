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
import { type FormEvent, useState } from 'react';
import type { Audit, AuditRecord } from '../api/schemas';
import { AUDIT_FILTERS, type AuditQuery, type QraftApi, type Read } from '../api/client';
import { useEventFeed } from '../eventFeed';
import { RouteLink, useRouter } from '../routing';
import { useRead } from '../useRead';
import { Failure, formatBytes, formatInstant, ReadMeta } from './common';
import { EphemeralNote, EventsTable, FeedState } from './EventsTable';

// The Audit page (UI/UX design section 10.3; UI-6 Tenants, Namespaces and Audit task list, Phase 3, decisions 5 to 7):
// the answering server's audit file, read from its end, newest first, with the filters in the address. A page goes on
// from the byte offset the last read stopped at, which the server names. Without a file, the server's bounded journal
// is shown instead, labelled as such, and never as a complete record.

/** The filters the form offers, with their labels; the page's offset is not one, as applying starts from the newest. */
const FIELDS: readonly [Exclude<keyof AuditQuery, 'result' | 'from'>, string, string][] = [
  ['accessor', 'Principal', 'Accessor ID'],
  ['requestId', 'Request ID', 'Exact'],
  ['method', 'Method', 'GET, PUT, DELETE…'],
  ['route', 'Route', 'A prefix, such as /v1/kv'],
  // The top bar's scope selector is labelled Tenant and Namespace, so these are named for the record's scope.
  ['tenant', 'In tenant', 'Exact'],
  ['namespace', 'In namespace', 'Exact'],
  ['before', 'Before', 'An instant, such as 2026-11-17T10:00:00Z'],
];

const RESULTS: readonly [NonNullable<AuditQuery['result']>, string][] = [
  ['allowed', 'Allowed'],
  ['denied', 'Denied (403)'],
  ['failed', 'Failed (5xx)'],
];

const REQUEST = 'audit.http_request';
const LOST = 'audit.events_lost';
const UNREADABLE = 'audit.unreadable_line';

export function AuditView({ api, filter }: { api: QraftApi; filter: AuditQuery }) {
  const { navigate } = useRouter();
  const [state, again] = useRead(() => api.audit(filter), [api, auditKey(filter)]);
  const shown = state.status === 'ready' ? state.value : state.previous;
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Audit</Typography.Title>
      <Filters filter={filter} onApply={(next) => navigate({ view: 'audit', filter: next })} />
      {state.status === 'failed' && <Failure title="The audit records could not be read" error={state.error} onRetry={again} />}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the audit records…</Typography.Text>}
      {shown !== undefined && <ReadMeta read={shown} operator stale={state.status !== 'ready'} />}
      {shown !== undefined && (shown.data.Configured ? (
        <AuditFile read={shown} filter={filter}
          onOlder={(next) => navigate({ view: 'audit', filter: { ...filter, from: String(next) } })}
          onNewest={() => navigate({ view: 'audit', filter: withoutOffset(filter) })} />
      ) : <AuditJournal api={api} />)}
    </Space>
  );
}

/** One text for a set of filters, the same for the same filters: what the page's read and its form are keyed by. */
export function auditKey(filter: AuditQuery): string {
  return AUDIT_FILTERS.map((name) => filter[name] ?? '').join('\n');
}

function withoutOffset(filter: AuditQuery): AuditQuery {
  const rest = { ...filter };
  delete rest.from;
  return rest;
}

/** The filters, as the address has them until they are applied; applying reads from the newest record again. */
function Filters({ filter, onApply }: { filter: AuditQuery; onApply: (next: AuditQuery) => void }) {
  const [values, setValues] = useState<AuditQuery>(withoutOffset(filter));
  const apply = (event: FormEvent) => {
    event.preventDefault();
    const next: AuditQuery = {};
    for (const [name] of FIELDS) {
      const value = values[name]?.trim();
      if (value) next[name] = value;
    }
    if (values.result !== undefined) next.result = values.result;
    onApply(next);
  };
  return (
    <form aria-label="Audit filters" onSubmit={apply}>
      <Space wrap align="end">
        {FIELDS.map(([name, label, hint]) => (
          <label key={name} style={{ display: 'grid' }}>
            {label}
            <input aria-label={label} placeholder={hint} value={values[name] ?? ''}
              onChange={(event) => setValues({ ...values, [name]: event.target.value })} />
          </label>
        ))}
        <label style={{ display: 'grid' }}>
          {'Result'}
          <select aria-label="Result" value={values.result ?? ''}
            onChange={(event) => {
              const result = RESULTS.find(([known]) => known === event.target.value)?.[0];
              const rest = { ...values };
              delete rest.result;
              setValues(result === undefined ? rest : { ...rest, result });
            }}>
            <option value="">Any</option>
            {RESULTS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <button type="submit">Apply</button>
      </Space>
    </form>
  );
}

/** How much of the file a read covered, from its end or from where the last page stopped. */
function scanned(audit: Audit, from: string | undefined): string {
  const read = formatBytes(audit.Scanned);
  if (from === undefined) {
    return audit.Complete ? `Read the whole file, ${formatBytes(audit.Size)}.`
      : `Read ${read} of the file's ${formatBytes(audit.Size)} from its end; older records were not searched.`;
  }
  return audit.Complete ? `Read ${read} back to the file's start, from where the last page stopped.`
    : `Read ${read} of the file's ${formatBytes(audit.Size)}, from where the last page stopped; older records were not searched.`;
}

function AuditFile({ read, filter, onOlder, onNewest }: {
  read: Read<Audit>;
  filter: AuditQuery;
  onOlder: (next: number) => void;
  onNewest: () => void;
}) {
  const audit = read.data;
  const next = audit.Next;
  return (
    <>
      <Typography.Text>
        {`Audit records are kept best effort, on each server for the requests it answered, in ${audit.File ?? 'its audit file'}, `
          + 'until an operator removes or rotates the file. A record carries no committed index.'}
      </Typography.Text>
      <Typography.Text type="secondary">{scanned(audit, filter.from)}</Typography.Text>
      <RecordsTable records={audit.Records} />
      <Space>
        {filter.from !== undefined && <button type="button" onClick={onNewest}>Newest records</button>}
        {next !== null && <button type="button" onClick={() => onOlder(next)}>Older records</button>}
      </Space>
    </>
  );
}

/** A request's decision, as the server's result filter has it: 403 denied, a 5xx failed, and anything else allowed. */
function decision(status: number): string {
  if (status === 403) return 'Denied';
  return status >= 500 ? 'Failed' : 'Allowed';
}

function scopeOf(record: AuditRecord): string {
  if (record.Tenant == null && record.Namespace == null) return 'Not recorded';
  return `${record.Tenant ?? '—'}/${record.Namespace ?? '—'}`;
}

const COLUMNS = ['Time', 'Principal', 'Scope', 'Request', 'Operation', 'Status', 'Decision'];

/** What a record that is not a request stands for, in one cell across the row. */
function standsFor(record: AuditRecord): string {
  switch (record.Type) {
    case LOST:
      return 'Records were lost here: the audit file fell behind the journal.';
    case UNREADABLE:
      return 'A line here could not be read.';
    default:
      return `A record of type ${record.Type}.`;
  }
}

/**
 * Each record with a key for its row. A file's records have no identity of their own and two lines may be the same, so
 * a key is the record's fields with the number of identical records before it.
 */
function rowKeys(records: readonly AuditRecord[]): [AuditRecord, string][] {
  const seen = new Map<string, number>();
  return records.map((record) => {
    const fields = JSON.stringify([record.Type, record.Timestamp, record.Node, record.RequestId, record.AccessorId,
      record.Method, record.Route, record.Status, record.Tenant, record.Namespace]);
    const before = seen.get(fields) ?? 0;
    seen.set(fields, before + 1);
    return [record, `${fields}#${before}`];
  });
}

/** A plain table: a lost stretch and an unreadable line take a row of their own, where they were in the file. */
function RecordsTable({ records }: { records: readonly AuditRecord[] }) {
  const cell = { padding: '8px 12px', borderBottom: '1px solid #f0f0f0', textAlign: 'left' as const, verticalAlign: 'top' as const };
  return (
    <table aria-label="Audit records" style={{ width: '100%', borderCollapse: 'collapse' }}>
      <thead>
        <tr>{COLUMNS.map((title) => <th key={title} style={{ ...cell, background: '#fafafa' }}>{title}</th>)}</tr>
      </thead>
      <tbody>
        {rowKeys(records).map(([record, key]) => (
          <tr key={key}>
            {record.Type === REQUEST ? (
              <>
                <td style={cell}>{record.Timestamp == null ? '—' : formatInstant(record.Timestamp)}</td>
                <td style={cell}>{record.AccessorId ?? '—'}</td>
                <td style={cell}>{scopeOf(record)}</td>
                <td style={cell}>{record.RequestId == null ? '—' : (
                  <RouteLink to={{ view: 'events', category: 'audit', correlation: record.RequestId }}>{record.RequestId}</RouteLink>
                )}</td>
                <td style={cell}>{`${record.Method ?? '—'} ${record.Route ?? '—'}`}</td>
                <td style={cell}>{record.Status == null ? '—' : String(record.Status)}</td>
                <td style={cell}>{record.Status == null ? '—' : decision(record.Status)}</td>
              </>
            ) : (
              <td colSpan={COLUMNS.length} style={{ ...cell, fontStyle: 'italic' }}>{standsFor(record)}</td>
            )}
          </tr>
        ))}
      </tbody>
      {records.length === 0 && (
        <tfoot>
          <tr><td colSpan={COLUMNS.length} style={cell}>No record matches.</td></tr>
        </tfoot>
      )}
    </table>
  );
}

/** Without an audit file: the answering server's bounded journal of audit events, never presented as complete. */
function AuditJournal({ api }: { api: QraftApi }) {
  const [feed] = useEventFeed(api, { category: 'audit' }, []);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text>
        {'This server writes no audit file: set server.audit.path to keep one. Below is its bounded journal, which holds '
          + 'only its most recent events and is lost when it restarts: it is not a complete record.'}
      </Typography.Text>
      <EphemeralNote feed={feed} />
      <FeedState feed={feed} />
      <EventsTable events={feed.events} paged
        empty={feed.phase === 'reading' ? 'Reading the journal…' : 'The journal holds no audit event.'} />
    </Space>
  );
}
