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
import type { QraftApi } from '../api/client';
import type { Health } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import {
  distinct,
  Failure,
  HEALTH_ORDER,
  HealthLabel,
  loadServiceEntries,
  location,
  READ_SCOPE,
  ReadMeta,
  worstHealth,
} from './common';

interface Row {
  name: string;
  health: Health | null;
  instances: number;
  tags: string[];
  scope: string;
  location: string;
}

/** The landing page (UI/UX design section 5.1): services needing attention first. */
export function ServicesView({ api }: { api: QraftApi }) {
  const [state, again] = useRead(() => loadServiceEntries(api), [api]);
  const shown = state.status === 'ready' ? state.value : state.previous;

  const rows: Row[] =
    shown === undefined
      ? []
      : Object.entries(shown.names)
          .map(([name, tags]) => {
            const entries = shown.entries[name] ?? [];
            return {
              name,
              health: worstHealth(entries),
              instances: entries.length,
              tags,
              scope: distinct(entries.map((entry) => `${entry.service.tenantId} / ${entry.service.namespace}`)),
              location: distinct(entries.map((entry) => location(entry.service.datacenter, entry.service.region))),
            };
          })
          .sort((a, b) => rank(a.health) - rank(b.health) || a.name.localeCompare(b.name));

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Services</Typography.Title>
      <Typography.Text>Read in {READ_SCOPE}.</Typography.Text>
      {state.status === 'failed' && <Failure title="Services could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta index={shown.index} stale={state.status !== 'ready'} />}
      {state.status !== 'failed' && (
        <Table<Row>
          rowKey="name"
          loading={state.status === 'loading'}
          dataSource={rows}
          pagination={false}
          locale={{
            emptyText: (
              <Empty description={shown === undefined ? 'Reading services…' : `No service is registered in ${READ_SCOPE}.`} />
            ),
          }}
          columns={[
            {
              title: 'Service',
              dataIndex: 'name',
              render: (name: string) => <RouteLink to={{ view: 'service', name }}>{name}</RouteLink>,
            },
            {
              title: 'Health',
              dataIndex: 'health',
              render: (health: Health | null) => (health === null ? 'No instances' : <HealthLabel health={health} />),
            },
            { title: 'Instances', dataIndex: 'instances' },
            {
              title: 'Tags',
              dataIndex: 'tags',
              render: (tags: string[]) => tags.map((tag) => <Tag key={tag}>{tag}</Tag>),
            },
            { title: 'Tenant / namespace', dataIndex: 'scope' },
            { title: 'Datacenter / region', dataIndex: 'location' },
          ]}
        />
      )}
    </Space>
  );
}

function rank(health: Health | null): number {
  return health === null ? HEALTH_ORDER.length : HEALTH_ORDER.indexOf(health);
}
