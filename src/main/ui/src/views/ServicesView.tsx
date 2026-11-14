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

import { Empty, Space, Table, Tag, Typography } from 'antd';
import type { QraftApi, Scope } from '../api/client';
import type { Health, ServiceSummary } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { Failure, HEALTH_ORDER, HealthLabel, healthCountsText, ReadIn, ReadMeta, scopeText } from './common';

interface Row {
  summary: ServiceSummary;
  worst: Health | null;
}

/**
 * The landing page (UI/UX design section 5.1): every service in the selected scope from one summary read (UI-2
 * task list, decision 2), services needing attention first.
 */
export function ServicesView({ api, scope }: { api: QraftApi; scope: Scope }) {
  const [state, again] = useRead(() => api.servicesSummary(scope), [api, scope.tenant, scope.namespace]);
  const shown = state.status === 'ready' ? state.value : state.previous;

  const rows: Row[] =
    shown === undefined
      ? []
      : shown.data
          .map((summary) => ({ summary, worst: worstOf(summary) }))
          .sort((a, b) => rank(a.worst) - rank(b.worst) || a.summary.name.localeCompare(b.summary.name));

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Services</Typography.Title>
      <ReadIn scope={scope} />
      {state.status === 'failed' && <Failure title="Services could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta index={shown.index} stale={state.status !== 'ready'} />}
      {state.status !== 'failed' && (
        <Table<Row>
          rowKey={(row) => row.summary.name}
          loading={state.status === 'loading'}
          dataSource={rows}
          pagination={false}
          locale={{
            emptyText: (
              <Empty
                description={shown === undefined ? 'Reading services…' : `No service is registered in ${scopeText(scope)}.`}
              />
            ),
          }}
          columns={[
            {
              title: 'Service',
              render: (_, row) => <RouteLink to={{ view: 'service', name: row.summary.name }}>{row.summary.name}</RouteLink>,
            },
            {
              title: 'Health',
              render: (_, row) => (row.worst === null ? 'No instances' : <HealthLabel health={row.worst} />),
            },
            { title: 'Instances', render: (_, row) => row.summary.instanceCount },
            { title: 'By health', render: (_, row) => healthCountsText(row.summary.health) },
            { title: 'Tags', render: (_, row) => row.summary.tags.map((tag) => <Tag key={tag}>{tag}</Tag>) },
            { title: 'Tenant / namespace', render: (_, row) => `${row.summary.tenantId} / ${row.summary.namespace}` },
            {
              title: 'Datacenter / region',
              render: (_, row) => `${list(row.summary.datacenters)} / ${list(row.summary.regions)}`,
            },
            {
              title: 'Last change',
              render: (_, row) => (row.summary.instanceCount === 0 ? '—' : `Changed at index ${row.summary.modifyIndex}`),
            },
          ]}
        />
      )}
    </Space>
  );
}

/** The worst health any instance of the service has, or null when it has none. */
function worstOf(summary: ServiceSummary): Health | null {
  return HEALTH_ORDER.find((health) => summary.health[health.toLowerCase() as Lowercase<Health>] > 0) ?? null;
}

function rank(health: Health | null): number {
  return health === null ? HEALTH_ORDER.length : HEALTH_ORDER.indexOf(health);
}

function list(values: string[]): string {
  return values.length === 0 ? '—' : values.join(', ');
}
