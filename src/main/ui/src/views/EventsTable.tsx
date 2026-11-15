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

import { Alert, Empty, Table, Tag, Typography } from 'antd';
import type { EventSeverity, QraftEvent } from '../api/schemas';
import type { EventFeed } from '../eventFeed';
import { formatInstant } from './common';

// Each severity has its own label and mark, so colour is never the only signal (UI/UX design section 3.6).
const SEVERITY: Record<EventSeverity, { label: string; mark: string; color: string }> = {
  info: { label: 'Info', mark: 'i', color: 'default' },
  warning: { label: 'Warning', mark: '!', color: 'gold' },
  error: { label: 'Error', mark: '✕', color: 'red' },
};

export function SeverityLabel({ severity }: { severity: EventSeverity }) {
  const { label, mark, color } = SEVERITY[severity];
  return (
    <Tag color={color}>
      <span aria-hidden="true">{mark} </span>
      <span>{label}</span>
    </Tag>
  );
}

/** An event's payload in words: each field as `name=value`. */
function details(event: QraftEvent): string {
  const fields = Object.entries(event.event).map(
    ([name, value]) => `${name}=${typeof value === 'string' ? value : JSON.stringify(value)}`,
  );
  return fields.length === 0 ? '—' : fields.join(', ');
}

/** Events, newest first: when, how severe, what, on which server, in which term, at which index, and in which scope. */
export function EventsTable({ events, empty, paged }: { events: QraftEvent[]; empty: string; paged?: boolean }) {
  return (
    <Table<QraftEvent>
      rowKey={(event) => event.cursor}
      dataSource={[...events].reverse()}
      pagination={paged === true ? { pageSize: 100, showSizeChanger: false } : false}
      size="small"
      locale={{ emptyText: <Empty description={empty} /> }}
      columns={[
        { title: 'Observed', render: (_, event) => formatInstant(event.observedAt) },
        { title: 'Severity', render: (_, event) => <SeverityLabel severity={event.severity} /> },
        { title: 'Category', render: (_, event) => event.category },
        { title: 'Type', render: (_, event) => <span data-event-type="">{event.type}</span> },
        { title: 'Server', render: (_, event) => event.node },
        { title: 'Term', render: (_, event) => event.term },
        { title: 'Committed index', render: (_, event) => event.committedIndex ?? '—' },
        {
          title: 'Scope',
          render: (_, event) => (event.tenant === undefined ? '—' : `${event.tenant} / ${event.namespace ?? '—'}`),
        },
        { title: 'Details', render: (_, event) => details(event) },
      ]}
    />
  );
}

const MISSED = {
  history_gap:
    'Events were missed: the journal dropped events before the interface read them, so it was read again from its start.',
  other_boot: "The server restarted: its journal began again, so it was read again from its start.",
  overflow: 'The watch fell behind the server and was resumed from the last event it delivered; no event was missed.',
};

/**
 * Where a feed is: reading, following or paused at its cursor, or stopped and trying again; and, persistently, why it
 * read the journal again (UI/UX design section 9.4: explicit history-gap and overflow states).
 */
export function FeedState({ feed }: { feed: EventFeed }) {
  let where: string;
  switch (feed.phase) {
    case 'reading':
      where = 'Reading the journal…';
      break;
    case 'following':
      where = `Following from cursor ${feed.cursor ?? 'the journal start'}`;
      break;
    case 'paused':
      where = `Paused at cursor ${feed.cursor ?? 'the journal start'}`;
      break;
    case 'retrying':
      where = `Stopped at cursor ${feed.cursor ?? 'the journal start'}`;
      break;
  }
  const reason = feed.error instanceof Error ? feed.error.message : String(feed.error);
  return (
    <>
      <Typography.Text type="secondary">{where}</Typography.Text>
      {feed.phase === 'retrying' && (
        <Alert
          type="warning"
          showIcon
          aria-label="Events not followed"
          message="Events not followed"
          description={`The watch stopped: ${reason}. It is tried again in a few seconds, from its cursor.`}
        />
      )}
      {feed.missed !== null && (
        <Alert
          type={feed.missed === 'overflow' ? 'info' : 'warning'}
          showIcon
          aria-label="Events missed"
          message={feed.missed === 'overflow' ? 'Watch resumed' : 'Journal read again'}
          description={MISSED[feed.missed]}
        />
      )}
    </>
  );
}

/** The label the journal always carries (UI/UX design section 9.4): it is ephemeral and one server's. */
export function EphemeralNote({ feed }: { feed: EventFeed }) {
  const server = feed.answeredBy ?? 'The answering server';
  const capacity = feed.capacity === null ? 'a bounded number of' : String(feed.capacity);
  return (
    <Alert
      type="info"
      showIcon
      aria-label="Ephemeral journal"
      message="Ephemeral journal"
      description={`${server} keeps only its last ${capacity} events since it started, and loses them when it stops. This is not a durable or complete history, and every other server keeps its own.`}
    />
  );
}
