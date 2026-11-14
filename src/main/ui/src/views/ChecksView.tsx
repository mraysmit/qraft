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

import { Empty, Input, Radio, Space, Table, Typography } from 'antd';
import { useState } from 'react';
import type { QraftApi, Scope } from '../api/client';
import type { CheckEntry, Health } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import {
  Failure,
  formatInstant,
  HEALTH_ORDER,
  HealthLabel,
  healthCountsText,
  NodeStatusLabel,
  ReadIn,
  ReadMeta,
  scopeText,
} from './common';

const STATUS_FILTERS = ['All', 'Critical', 'Warning', 'Maintenance', 'Unknown', 'Passing'] as const;
type StatusFilter = (typeof STATUS_FILTERS)[number];

/**
 * Every accepted check in the selected scope, across services (UI/UX design section 5.3; UI-2 task list, Step 4),
 * needing attention first. The client's observation is shown apart from the servers' acceptance, an expired check is
 * said to be expired, and a check with no result yet is unknown, neither passing nor failing. The filters are the
 * page's own, over the one listing read.
 */
export function ChecksView({ api, scope }: { api: QraftApi; scope: Scope }) {
  const [state, again] = useRead(() => api.checks('any', scope), [api, scope.tenant, scope.namespace]);
  const [status, setStatus] = useState<StatusFilter>('All');
  const [text, setText] = useState('');
  const shown = state.status === 'ready' ? state.value : state.previous;
  const all = shown === undefined ? [] : [...shown.data].sort(byAttention);
  const needle = text.trim().toLowerCase();
  const listed = all.filter(
    (check) =>
      (status === 'All' || check.status === status.toUpperCase()) && (needle === '' || searchable(check).includes(needle)),
  );
  const counts: Partial<Record<Lowercase<Health>, number>> = {};
  for (const check of all) {
    const key = check.status.toLowerCase() as Lowercase<Health>;
    counts[key] = (counts[key] ?? 0) + 1;
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Health Checks</Typography.Title>
      <ReadIn scope={scope} />
      {state.status === 'failed' && <Failure title="Health checks could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta index={shown.index} stale={state.status !== 'ready'} />}
      {shown !== undefined && all.length > 0 && (
        <Typography.Text>{`${all.length} ${all.length === 1 ? 'check' : 'checks'}: ${healthCountsText(counts)}`}</Typography.Text>
      )}
      <Space wrap>
        <Radio.Group
          optionType="button"
          value={status}
          onChange={(event) => setStatus(event.target.value as StatusFilter)}
          options={STATUS_FILTERS.map((option) => ({ label: option, value: option }))}
        />
        <Input
          aria-label="Filter"
          placeholder="Service, instance, node, check or output"
          allowClear
          value={text}
          onChange={(event) => setText(event.target.value)}
          style={{ width: 320 }}
        />
      </Space>
      {state.status !== 'failed' && (
        <Table<CheckEntry>
          rowKey={(check) => `${check.serviceId}/${check.checkId}`}
          loading={state.status === 'loading'}
          dataSource={listed}
          pagination={false}
          locale={{
            emptyText: (
              <Empty
                description={
                  shown === undefined
                    ? 'Reading health checks…'
                    : all.length === 0
                      ? `No check has an accepted result in ${scopeText(scope)}.`
                      : 'No check matches the filters.'
                }
              />
            ),
          }}
          columns={[
            {
              title: 'Service',
              render: (_, check) => <RouteLink to={{ view: 'service', name: check.serviceName }}>{check.serviceName}</RouteLink>,
            },
            { title: 'Instance', render: (_, check) => check.serviceId },
            {
              title: 'Node',
              render: (_, check) => (
                <Space size="small">
                  <span>{check.nodeId ?? 'No node'}</span>
                  {check.nodeStatus === null ? 'Node not registered' : <NodeStatusLabel status={check.nodeStatus} />}
                </Space>
              ),
            },
            { title: 'Check', render: (_, check) => check.checkId },
            { title: 'Status', render: (_, check) => <HealthLabel health={check.status} /> },
            {
              title: 'Result',
              render: (_, check) => (
                <Space direction="vertical" size={0}>
                  <span>{`Observed ${formatInstant(check.observedAt)}`}</span>
                  <span>{`Accepted ${formatInstant(check.acceptedAt)}`}</span>
                  <span>
                    {check.expired
                      ? `Expired: no renewal by ${formatInstant(check.deadline)}`
                      : `Renew by ${formatInstant(check.deadline)}`}
                  </span>
                </Space>
              ),
            },
            { title: 'Output', render: (_, check) => check.output ?? 'No output' },
          ]}
        />
      )}
    </Space>
  );
}

function byAttention(a: CheckEntry, b: CheckEntry): number {
  return (
    HEALTH_ORDER.indexOf(a.status) - HEALTH_ORDER.indexOf(b.status) ||
    a.serviceName.localeCompare(b.serviceName) ||
    a.serviceId.localeCompare(b.serviceId) ||
    a.checkId.localeCompare(b.checkId)
  );
}

function searchable(check: CheckEntry): string {
  return [check.serviceName, check.serviceId, check.nodeId ?? '', check.checkId, check.output ?? ''].join(' ').toLowerCase();
}
