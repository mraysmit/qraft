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
import type { Members, RaftRole, RaftStatus } from '../api/schemas';
import type { Bootstrap } from '../bootstrap';
import { type ReadState, useRead } from '../useRead';
import { Failure } from './common';

const ROLE: Record<RaftRole, string> = { LEADER: 'Leader', FOLLOWER: 'Follower', CANDIDATE: 'Candidate' };

/**
 * The cluster status strip (UI/UX design section 4.2): the answering node's Raft state and readiness, and from the
 * membership read the quorum, the failure tolerance and the largest replication lag, which only the leader knows
 * (UI-3 task list, decision 5). The membership is read on its own, so a server that refuses it, for want of
 * `operator:read`, still shows the rest. Conditions that need attention are persistent messages, not notifications
 * (section 3.4).
 */
export function StatusStrip({ api, bootstrap }: { api: QraftApi; bootstrap: Bootstrap }) {
  const [state, again] = useRead(async () => {
    const [status, readiness] = await Promise.all([api.raftStatus(), api.readiness()]);
    return { status: status.data, readiness: readiness.data };
  }, [api]);
  const [membership] = useRead(() => api.members(), [api]);

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
      <Strip status={shown.status} readiness={shown.readiness} membership={membership} security={security} />
    );
  }

  return (
    <section aria-label="Cluster status" style={{ padding: '8px 24px', borderBottom: '1px solid #f0f0f0' }}>
      {body}
    </section>
  );
}

/** The quorum, failure tolerance and largest lag in words, or why they are not shown. */
function membershipItems(membership: ReadState<{ data: Members }>): string[] {
  if (membership.status === 'failed') {
    const reason = membership.error instanceof Error ? membership.error.message : String(membership.error);
    return [`Quorum: not read (${reason})`];
  }
  const shown = membership.status === 'ready' ? membership.value.data : membership.previous?.data;
  if (shown === undefined) return ['Quorum: reading…'];
  if (shown.view === 'follower' || shown.quorum === null) {
    return [`Quorum: known to the leader, ${shown.leaderId ?? 'none known'}`];
  }
  const lag = Math.max(0, ...shown.servers.map((server) => server.lag ?? 0));
  return [
    `Quorum: ${shown.quorum.reachableVoters} of ${shown.quorum.voters} voters reachable`,
    `Failure tolerance: ${shown.quorum.failureTolerance}`,
    `Replication lag: ${lag}`,
  ];
}

function unreachable(membership: ReadState<{ data: Members }>): string[] {
  if (membership.status !== 'ready') return [];
  return membership.value.data.servers.filter((server) => server.reachable === false).map((server) => server.name);
}

function Strip({
  status,
  readiness,
  membership,
  security,
}: {
  status: RaftStatus;
  readiness: Readiness;
  membership: ReadState<{ data: Members }>;
  security: ReactNode;
}) {
  const lost = unreachable(membership);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space wrap split="|">
        <Typography.Text>Answering node: {status.nodeId}</Typography.Text>
        <Typography.Text>Role: {ROLE[status.state]}</Typography.Text>
        <Typography.Text>Leader: {status.leaderId ?? 'none known'}</Typography.Text>
        <Typography.Text>Term: {status.term}</Typography.Text>
        <Typography.Text>Readiness: {readiness.ready ? 'Ready' : 'Not ready'}</Typography.Text>
        {membershipItems(membership).map((item) => (
          <Typography.Text key={item}>{item}</Typography.Text>
        ))}
        {security}
      </Space>
      {lost.length > 0 && (
        <Alert
          type="warning"
          showIcon
          aria-label="Servers unreachable"
          message="Servers unreachable"
          description={`${lost.join(', ')} ${lost.length === 1 ? 'has' : 'have'} not answered the leader within an election timeout.`}
        />
      )}
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
