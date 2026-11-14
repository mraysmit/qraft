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

import { Empty, Space, Table, Typography } from 'antd';
import type { QraftApi, Scope } from '../api/client';
import type { CatalogNode, HealthEntry } from '../api/schemas';
import { useRead } from '../useRead';
import { Failure, formatInstant, loadServiceEntries, location, NodeStatusLabel, ReadMeta, scopeText } from './common';
import { MembersTable } from './MembersTable';

const VERSION_KEY = 'qraft.version';

interface Row {
  node: CatalogNode;
  services: number;
  failing: number;
}

/**
 * Nodes and Clients (UI/UX design section 5.2): the servers from the membership read, with their progress where the
 * answering server leads (UI-3), and the client nodes, whose owned services and failing checks are counted from the
 * services read in the selected scope.
 */
export function NodesView({ api, scope }: { api: QraftApi; scope: Scope }) {
  const [state, again] = useRead(async () => {
    const [nodes, services] = await Promise.all([api.nodes(), loadServiceEntries(api, scope)]);
    return { nodes, entries: Object.values(services.entries).flat() };
  }, [api, scope.tenant, scope.namespace]);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const rows: Row[] = shown === undefined ? [] : shown.nodes.data.map((node) => row(node, shown.entries));
  const [members, readMembersAgain] = useRead(() => api.members(), [api]);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Nodes and Clients</Typography.Title>
      <Typography.Title level={3}>Servers</Typography.Title>
      {members.status === 'failed' && (
        <Failure title="Servers could not be read" error={members.error} onRetry={readMembersAgain} />
      )}
      {members.status === 'ready' && <MembersTable members={members.value.data} detailed={false} />}
      {members.status === 'loading' && <Typography.Text>Reading servers…</Typography.Text>}
      <Typography.Title level={3}>Clients</Typography.Title>
      <Typography.Text>
        {`Client nodes. A node has no tenant or namespace; its services and failing checks are counted in ${scopeText(scope)}.`}
      </Typography.Text>
      {state.status === 'failed' && <Failure title="Nodes could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta index={shown.nodes.index} stale={state.status !== 'ready'} />}
      {state.status !== 'failed' && (
        <Table<Row>
          rowKey={(item) => item.node.name}
          loading={state.status === 'loading'}
          dataSource={rows}
          pagination={false}
          locale={{
            emptyText: <Empty description={shown === undefined ? 'Reading nodes…' : 'No client node is registered.'} />,
          }}
          columns={[
            { title: 'Node', render: (_, item) => item.node.name },
            { title: 'Node ID', render: (_, item) => item.node.id ?? 'Not assigned' },
            { title: 'Status', render: (_, item) => <NodeStatusLabel status={item.node.status} /> },
            { title: 'Address', render: (_, item) => item.node.address },
            { title: 'Datacenter / region', render: (_, item) => location(item.node.datacenter, item.node.region) },
            { title: 'Version', render: (_, item) => item.node.metadata[VERSION_KEY] ?? 'Not reported' },
            {
              title: 'Registered',
              render: (_, item) =>
                item.node.registrationTime === null ? 'Not recorded' : formatInstant(item.node.registrationTime),
            },
            {
              title: 'Last heartbeat',
              render: (_, item) => (item.node.lastHeartbeat === null ? 'No heartbeat yet' : formatInstant(item.node.lastHeartbeat)),
            },
            { title: 'Services', render: (_, item) => `${item.services} ${item.services === 1 ? 'service' : 'services'}` },
            { title: 'Failing checks', render: (_, item) => (item.failing === 0 ? 'None failing' : `${item.failing} failing`) },
          ]}
        />
      )}
    </Space>
  );
}

function row(node: CatalogNode, entries: readonly HealthEntry[]): Row {
  const owned = entries.filter((entry) => entry.service.nodeId === node.name);
  const failing = owned
    .flatMap((entry) => entry.checks)
    .filter((check) => check.status === 'CRITICAL' || check.status === 'WARNING').length;
  return { node, services: owned.length, failing };
}
