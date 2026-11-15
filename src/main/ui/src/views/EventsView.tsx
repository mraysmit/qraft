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

import { Input, Select, Space, Typography } from 'antd';
import { useState } from 'react';
import type { QraftApi } from '../api/client';
import type { EventCategory, EventSeverity, QraftEvent } from '../api/schemas';
import { useEventFeed } from '../eventFeed';
import { EphemeralNote, EventsTable, FeedState } from './EventsTable';

const CATEGORIES: { label: string; value: EventCategory | 'all' }[] = [
  { label: 'All categories', value: 'all' },
  { label: 'System', value: 'system' },
  { label: 'Domain', value: 'domain' },
  { label: 'Observation', value: 'observation' },
  { label: 'Audit', value: 'audit' },
];

const SEVERITIES: { label: string; value: EventSeverity }[] = [
  { label: 'Any severity', value: 'info' },
  { label: 'Warning or worse', value: 'warning' },
  { label: 'Error', value: 'error' },
];

const RANK: Record<EventSeverity, number> = { info: 0, warning: 1, error: 2 };

interface Filters {
  severity: EventSeverity;
  tenant: string;
  namespace: string;
  term: string;
  correlationId: string;
  leastIndex: string;
}

function matches(event: QraftEvent, filters: Filters): boolean {
  const term = filters.term.trim();
  const least = filters.leastIndex.trim();
  return (
    RANK[event.severity] >= RANK[filters.severity] &&
    (filters.tenant.trim() === '' || event.tenant === filters.tenant.trim()) &&
    (filters.namespace.trim() === '' || event.namespace === filters.namespace.trim()) &&
    (term === '' || String(event.term) === term) &&
    (filters.correlationId.trim() === '' || event.correlationId === filters.correlationId.trim()) &&
    (least === '' || (event.committedIndex !== undefined && event.committedIndex >= Number(least)))
  );
}

/**
 * Events, read-only (UI/UX design section 9.4; UI-4 task list, decision 6): the answering server's whole journal in
 * the category chosen, newest first, followed live, labelled ephemeral. The other filters narrow the events read,
 * which are the whole journal, so none is missed.
 */
export function EventsView({ api }: { api: QraftApi }) {
  const [category, setCategory] = useState<EventCategory | 'all'>('all');
  const [filters, setFilters] = useState<Filters>({
    severity: 'info',
    tenant: '',
    namespace: '',
    term: '',
    correlationId: '',
    leastIndex: '',
  });
  const [feed] = useEventFeed(api, category === 'all' ? {} : { category }, [category]);
  const shown = feed.events.filter((event) => matches(event, filters));
  const text = (key: keyof Filters, label: string, width = 140) => (
    <Input
      aria-label={label}
      placeholder={label}
      allowClear
      style={{ width }}
      value={filters[key]}
      onChange={(event) => setFilters({ ...filters, [key]: event.target.value })}
    />
  );

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Events</Typography.Title>
      <EphemeralNote feed={feed} />
      <Space wrap>
        <Select
          aria-label="Category"
          virtual={false}
          value={category}
          style={{ minWidth: 160 }}
          options={CATEGORIES}
          onChange={(chosen: EventCategory | 'all') => setCategory(chosen)}
        />
        <Select
          aria-label="Least severity"
          virtual={false}
          value={filters.severity}
          style={{ minWidth: 160 }}
          options={SEVERITIES}
          onChange={(severity: EventSeverity) => setFilters({ ...filters, severity })}
        />
        {text('tenant', 'Event tenant')}
        {text('namespace', 'Event namespace')}
        {text('term', 'Term', 100)}
        {text('correlationId', 'Correlation ID', 200)}
        {text('leastIndex', 'Least committed index', 180)}
      </Space>
      <FeedState feed={feed} />
      <Typography.Text>{`${shown.length} of ${feed.events.length} events shown`}</Typography.Text>
      <EventsTable
        events={shown}
        paged
        empty={feed.phase === 'reading' ? 'Reading the journal…' : 'No event in the journal matches.'}
      />
    </Space>
  );
}
