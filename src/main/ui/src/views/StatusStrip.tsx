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

import { Alert, Space, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { QraftApi, Readiness } from '../api/client';
import type { RaftRole, RaftStatus } from '../api/schemas';
import type { Bootstrap } from '../bootstrap';
import { useRead } from '../useRead';
import { Failure } from './common';

const ROLE: Record<RaftRole, string> = { LEADER: 'Leader', FOLLOWER: 'Follower', CANDIDATE: 'Candidate' };

/**
 * The cluster status strip (UI/UX design section 4.2), limited to what `/raft/status` and readiness provide: the
 * answering node's view, not the cluster's. Quorum and replication lag wait for the membership API. Conditions that
 * need attention are persistent messages, not notifications (section 3.4).
 */
export function StatusStrip({ api, bootstrap }: { api: QraftApi; bootstrap: Bootstrap }) {
  const [state, again] = useRead(async () => {
    const [status, readiness] = await Promise.all([api.raftStatus(), api.readiness()]);
    return { status: status.data, readiness: readiness.data };
  }, [api]);

  const security = (
    <>
      <Typography.Text>Security profile: {bootstrap.securityProfile}</Typography.Text>
      <Typography.Text>Access control: {bootstrap.authMode === 'none' ? 'off' : 'on'}</Typography.Text>
    </>
  );

  let body: ReactNode;
  if (state.status === 'failed') {
    body = <Failure title="Cluster status could not be read" error={state.error} onRetry={again} />;
  } else {
    const shown = state.status === 'ready' ? state.value : state.previous;
    body = shown === undefined ? (
      <Space wrap>
        <Typography.Text>Reading cluster status…</Typography.Text>
        {security}
      </Space>
    ) : (
      <Strip status={shown.status} readiness={shown.readiness} security={security} />
    );
  }

  return (
    <section aria-label="Cluster status" style={{ padding: '8px 24px', borderBottom: '1px solid #f0f0f0' }}>
      {body}
    </section>
  );
}

function Strip({ status, readiness, security }: { status: RaftStatus; readiness: Readiness; security: ReactNode }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap split="|">
        <Typography.Text>Answering node: {status.nodeId}</Typography.Text>
        <Typography.Text>Role: {ROLE[status.state]}</Typography.Text>
        <Typography.Text>Leader: {status.leaderId ?? 'none known'}</Typography.Text>
        <Typography.Text>Term: {status.term}</Typography.Text>
        <Typography.Text>Readiness: {readiness.ready ? 'Ready' : 'Not ready'}</Typography.Text>
        {security}
      </Space>
      {!readiness.ready && (
        <Alert
          type="warning"
          showIcon
          aria-label="Not ready"
          message="Not ready"
          description={`The server does not meet: ${readiness.conditions.join(', ') || 'no condition named'}`}
        />
      )}
      {status.leaderId === null && (
        <Alert
          type="warning"
          showIcon
          aria-label="No leader"
          message="No leader"
          description="The answering node knows no leader: writes cannot be committed until one is elected."
        />
      )}
      {status.fenced && (
        <Alert
          type="error"
          showIcon
          aria-label="Storage fenced"
          message="Storage fenced"
          description={`The answering node has stopped writing to its storage: ${status.fenceReason ?? 'no reason given'}`}
        />
      )}
      {status.removed && (
        <Alert
          type="warning"
          showIcon
          aria-label="Removed from the cluster"
          message="Removed from the cluster"
          description="The answering node has been removed from the cluster's configuration."
        />
      )}
    </Space>
  );
}
