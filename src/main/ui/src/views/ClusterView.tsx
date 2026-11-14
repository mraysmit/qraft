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

import { Descriptions, Space, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { QraftApi, Scope } from '../api/client';
import { RouteLink } from '../routing';
import { type ReadState, useRead } from '../useRead';
import { FencedBanner } from './StorageView';
import { formatBytes, plural, scopeText } from './common';

/** A read's value in words, or why it is not shown. */
function shown<T>(state: ReadState<T>, words: (value: T) => ReactNode): ReactNode {
  if (state.status === 'ready') return words(state.value);
  if (state.status === 'failed') {
    return `Not read: ${state.error instanceof Error ? state.error.message : String(state.error)}`;
  }
  return 'Reading…';
}

/**
 * Cluster Overview (UI/UX design section 9.1; UI-3): the servers and quorum, the services and client nodes of the
 * selected scope, and the answering server's storage, each from its own read so one that fails leaves the rest.
 */
export function ClusterView({ api, scope }: { api: QraftApi; scope: Scope }) {
  const [members] = useRead(() => api.members(), [api]);
  const [storage] = useRead(() => api.storage(), [api]);
  const [services] = useRead(() => api.servicesSummary(scope), [api, scope.tenant, scope.namespace]);
  const [nodes] = useRead(() => api.nodes(), [api]);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Cluster Overview</Typography.Title>
      {storage.status === 'ready' && storage.value.data.fenced && <FencedBanner storage={storage.value.data} />}
      <Descriptions column={1} bordered size="small">
        <Descriptions.Item label="Servers">
          {shown(members, ({ data }) =>
            data.view === 'leader'
              ? `${plural(data.servers.length, 'server')}, ${data.servers.filter((server) => server.reachable).length} reachable, leader ${data.leaderId ?? 'none known'}`
              : `${plural(data.servers.length, 'server')}; their progress is known to the leader, ${data.leaderId ?? 'none known'}`,
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Quorum">
          {shown(members, ({ data }) =>
            data.quorum === null
              ? 'Known to the leader'
              : `Quorum of ${data.quorum.quorumSize} of ${data.quorum.voters} voters; tolerates ${plural(data.quorum.failureTolerance, 'failure')}`,
          )}
        </Descriptions.Item>
        <Descriptions.Item label="Services">
          {shown(services, ({ data }) => {
            const instances = data.reduce((total, service) => total + service.instanceCount, 0);
            return `${plural(data.length, 'service')}, ${plural(instances, 'instance')} in ${scopeText(scope)}`;
          })}
        </Descriptions.Item>
        <Descriptions.Item label="Client nodes">{shown(nodes, ({ data }) => plural(data.length, 'client node'))}</Descriptions.Item>
        <Descriptions.Item label="Storage">
          {shown(storage, ({ data }) =>
            [
              data.storageBytes === null ? 'size not known' : formatBytes(data.storageBytes),
              data.snapshotLastIndex === 0 ? 'no snapshot yet' : `snapshot at index ${data.snapshotLastIndex}`,
              `${plural(data.transitionQueueDepth, 'transition')} queued`,
            ].join('; '),
          )}
        </Descriptions.Item>
      </Descriptions>
      <Space>
        <RouteLink to={{ view: 'members' }}>Raft Members</RouteLink>
        <RouteLink to={{ view: 'storage' }}>Storage & Snapshots</RouteLink>
      </Space>
    </Space>
  );
}
