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

import { Descriptions, Empty, Space, Table, Tabs, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { QraftApi, Scope } from '../api/client';
import type { Check, HealthEntry, ServiceInstance } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import {
  distinct,
  Failure,
  formatInstant,
  HealthLabel,
  location,
  NodeStatusLabel,
  ReadIn,
  ReadMeta,
  scopeText,
  worstHealth,
} from './common';

/**
 * A service's Overview, Instances, Health Checks and Metadata (UI/UX design section 5.1). A check's observation, made
 * by a client, is shown apart from the time the servers accepted it, the replicated state (section 3.3).
 */
export function ServiceDetailView({ api, name, scope }: { api: QraftApi; name: string; scope: Scope }) {
  const [state, again] = useRead(() => api.serviceHealth(name, scope), [api, name, scope.tenant, scope.namespace]);
  const shown = state.status === 'ready' ? state.value : state.previous;

  let body: ReactNode;
  if (state.status === 'failed') {
    body = <Failure title={`Service ${name} could not be read`} error={state.error} onRetry={again} />;
  } else if (shown === undefined) {
    body = <Typography.Text>Reading service {name}…</Typography.Text>;
  } else if (shown.data.length === 0) {
    body = <Empty description={`No instance of ${name} is registered in ${scopeText(scope)}.`} />;
  } else {
    body = (
      <>
        <ReadMeta index={shown.index} stale={state.status !== 'ready'} />
        <Tabs
          items={[
            { key: 'overview', label: 'Overview', children: <Overview entries={shown.data} /> },
            { key: 'instances', label: 'Instances', children: <Instances entries={shown.data} /> },
            { key: 'checks', label: 'Health Checks', children: <Checks entries={shown.data} /> },
            { key: 'metadata', label: 'Metadata', children: <Metadata entries={shown.data} /> },
          ]}
        />
      </>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <RouteLink to={{ view: 'services' }}>Services</RouteLink>
      <Typography.Title level={2}>Service: {name}</Typography.Title>
      <ReadIn scope={scope} />
      {body}
    </Space>
  );
}

function Overview({ entries }: { entries: HealthEntry[] }) {
  const health = worstHealth(entries);
  const enabled = entries.filter((entry) => entry.service.enabled).length;
  return (
    <Descriptions column={1} bordered size="small">
      <Descriptions.Item label="Tenant / namespace">
        {distinct(entries.map((entry) => `${entry.service.tenantId} / ${entry.service.namespace}`))}
      </Descriptions.Item>
      <Descriptions.Item label="Health">{health === null ? 'No instances' : <HealthLabel health={health} />}</Descriptions.Item>
      <Descriptions.Item label="Instances">{`${entries.length}, ${enabled} enabled`}</Descriptions.Item>
      <Descriptions.Item label="Datacenter / region">
        {distinct(entries.map((entry) => location(entry.service.datacenter, entry.service.region)))}
      </Descriptions.Item>
    </Descriptions>
  );
}

function Instances({ entries }: { entries: HealthEntry[] }) {
  return (
    <Table<HealthEntry>
      rowKey={(entry) => entry.service.serviceId}
      dataSource={entries}
      pagination={false}
      columns={[
        { title: 'Instance', render: (_, entry) => entry.service.serviceId },
        { title: 'Address', render: (_, entry) => `${entry.service.address}:${entry.service.port}` },
        { title: 'Node', render: (_, entry) => entry.service.nodeId ?? 'No node' },
        {
          title: 'Node status',
          render: (_, entry) =>
            entry.nodeStatus === null ? 'Node not registered' : <NodeStatusLabel status={entry.nodeStatus} />,
        },
        { title: 'Health', render: (_, entry) => <HealthLabel health={entry.service.health} /> },
        { title: 'Enabled', render: (_, entry) => (entry.service.enabled ? 'Enabled' : 'Disabled') },
      ]}
    />
  );
}

interface CheckRow {
  key: string;
  instance: string;
  check: Check;
}

function Checks({ entries }: { entries: HealthEntry[] }) {
  const rows: CheckRow[] = entries.flatMap((entry) =>
    entry.checks.map((check) => ({ key: `${entry.service.serviceId}/${check.checkId}`, instance: entry.service.serviceId, check })),
  );
  return (
    <Table<CheckRow>
      rowKey="key"
      dataSource={rows}
      pagination={false}
      locale={{ emptyText: <Empty description="No instance has a check result." /> }}
      columns={[
        { title: 'Instance', dataIndex: 'instance' },
        { title: 'Check', render: (_, row) => row.check.checkId },
        { title: 'Status', render: (_, row) => <HealthLabel health={row.check.status} /> },
        { title: 'Required', render: (_, row) => (row.check.required ? 'Required' : 'Optional') },
        {
          title: 'Result',
          render: (_, row) => (
            <Space direction="vertical" size={0}>
              <span>{`Observed ${formatInstant(row.check.observedAt)}`}</span>
              <span>{`Accepted ${formatInstant(row.check.acceptedAt)}`}</span>
              <span>
                {row.check.expired
                  ? `Expired: no renewal by ${formatInstant(row.check.deadline)}`
                  : `Renew by ${formatInstant(row.check.deadline)}`}
              </span>
            </Space>
          ),
        },
        { title: 'Output', render: (_, row) => row.check.output ?? 'No output' },
      ]}
    />
  );
}

function Metadata({ entries }: { entries: HealthEntry[] }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      {entries.map(({ service }) => (
        <InstanceMetadata key={service.serviceId} service={service} />
      ))}
    </Space>
  );
}

function InstanceMetadata({ service }: { service: ServiceInstance }) {
  const metadata = Object.entries(service.metadata);
  return (
    <Descriptions title={service.serviceId} column={1} bordered size="small">
      <Descriptions.Item label="Tags">
        {service.tags.length === 0 ? 'No tags' : service.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}
      </Descriptions.Item>
      <Descriptions.Item label="Metadata">
        {metadata.length === 0 ? 'No metadata' : metadata.map(([key, value]) => <Tag key={key}>{`${key}=${value}`}</Tag>)}
      </Descriptions.Item>
      <Descriptions.Item label="Indexes">
        {`Created at index ${service.createIndex}, modified at index ${service.modifyIndex}`}
      </Descriptions.Item>
    </Descriptions>
  );
}
