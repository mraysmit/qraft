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
import type { QraftApi } from '../api/client';
import { useRead } from '../useRead';
import { Failure, plural } from './common';
import { MembersTable } from './MembersTable';

/**
 * Raft Members, read-only (UI/UX design section 9.2; UI-3): each server's progress and the quorum as the leader sees
 * them; on a follower, the configuration it holds, who it follows, and that only the leader knows the progress
 * (decision 5). Membership changes are not offered before UI-5.
 */
export function MembersView({ api }: { api: QraftApi }) {
  const [state, again] = useRead(() => api.members(), [api]);
  const shown = state.status === 'ready' ? state.value : state.previous;

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Raft Members</Typography.Title>
      {state.status === 'failed' && <Failure title="Raft members could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && shown.data.quorum !== null && (
        <Typography.Text>
          {`A quorum is ${shown.data.quorum.quorumSize} of ${shown.data.quorum.voters} voters; ${shown.data.quorum.reachableVoters} reachable; tolerates ${plural(shown.data.quorum.failureTolerance, 'failure')}`}
        </Typography.Text>
      )}
      {shown !== undefined && shown.data.view === 'follower' && (
        <Alert
          type="info"
          showIcon
          aria-label="Progress known to the leader"
          message="Progress known to the leader"
          description={`${shown.data.answeredBy} follows ${shown.data.leaderId ?? 'no known leader'}: only the leader knows each server's match index, lag and contact. Open the interface on the leader to see them.`}
        />
      )}
      {shown !== undefined && <MembersTable members={shown.data} detailed />}
      <Typography.Text type="secondary">Read-only: membership changes are not offered here before sign-in (UI-5).</Typography.Text>
    </Space>
  );
}
