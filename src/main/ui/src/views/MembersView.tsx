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
import { useState } from 'react';
import { useAccess } from '../access';
import type { QraftApi } from '../api/client';
import type { Member } from '../api/schemas';
import { useLiveRead } from '../live';
import { Failure, plural, ReadMeta } from './common';
import { MembersTable } from './MembersTable';
import { Mutation } from './Mutation';

/**
 * Raft Members (UI/UX design section 9.2; UI-3): each server's progress and the quorum as the leader sees them; on a
 * follower, the configuration it holds, who it follows, and that only the leader knows the progress (decision 5).
 * Since UI-5, a token with `operator:write` may remove a server, never the last voter, after a review of the quorum
 * before and after (UI-5 task list, decision 8). Servers join, and non-voters are promoted, through configuration.
 */
export function MembersView({ api }: { api: QraftApi }) {
  const [state, again] = useLiveRead((wait) => api.members({ signal: wait.signal }), [api]);
  const shown = state.status === 'ready' ? state.value : state.previous;

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Raft Members</Typography.Title>
      {state.status === 'failed' && <Failure title="Raft members could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
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
      {shown !== undefined && <Removals api={api} servers={shown.data.servers} />}
    </Space>
  );
}

/** The size of a quorum of `voters`: a majority. */
const quorumOf = (voters: number) => Math.floor(voters / 2) + 1;

/** The removal of each server, to a token with `operator:write`; the last voter is never offered. */
function Removals({ api, servers }: { api: QraftApi; servers: Member[] }) {
  const { may } = useAccess();
  const [removing, setRemoving] = useState<Member | null>(null);
  if (!may.operatorWrite) {
    return <Typography.Text type="secondary">Removing a server needs operator:write.</Typography.Text>;
  }
  const voters = servers.filter((server) => server.voter).length;
  if (removing !== null) {
    return <RemoveServer api={api} server={removing} voters={voters} onClose={() => setRemoving(null)} />;
  }
  return (
    <section aria-label="Removals">
      <Typography.Title level={3}>Remove a server</Typography.Title>
      <Typography.Text>
        Servers join, and non-voters are promoted, through server configuration and the leader&apos;s policy; only a
        removal is offered here.
      </Typography.Text>
      <Space wrap style={{ display: 'flex', marginTop: 8 }}>
        {servers.map((server) => (server.voter && voters <= 1 ? (
          <Typography.Text key={server.name}>The last voter cannot be removed.</Typography.Text>
        ) : (
          // A plain button: Ant Design's beside a table slowed the table in jsdom (UI-4).
          <button key={server.name} type="button" onClick={() => setRemoving(server)}>{`Remove ${server.name}`}</button>
        )))}
      </Space>
    </section>
  );
}

/**
 * A removal's review: the voters and the quorum before and after, the failure tolerance after, whether the server is
 * reachable, and what must happen to a removed server. Without access control the operator token is asked for this
 * one request, and kept for none other.
 */
function RemoveServer({ api, server, voters, onClose }: {
  api: QraftApi;
  server: Member;
  voters: number;
  onClose: () => void;
}) {
  const { mode } = useAccess();
  const [operatorToken, setOperatorToken] = useState('');
  const after = server.voter ? voters - 1 : voters;
  const reachability = server.reachable === true ? 'The server is reachable.'
    : server.reachable === false ? 'The server is unreachable.' : 'Its reachability is known to the leader.';
  return (
    <Mutation
      title={`Remove ${server.name}`}
      identity={[['Server', server.name], ['Server ID', server.serverId], ['Address', server.address],
        ['Voter', server.voter ? 'yes' : 'no']]}
      review={(
        <>
          {server.voter ? (
            <>
              <p>{`Voters: ${voters} → ${after}`}</p>
              <p>{`Quorum: ${quorumOf(voters)} of ${voters} → ${quorumOf(after)} of ${after}`}</p>
              <p>{`Failure tolerance after: ${after - quorumOf(after)}`}</p>
            </>
          ) : (
            <p>A non-voter: the quorum does not change.</p>
          )}
          <p>{reachability}</p>
          {server.leader && <Alert type="warning" message="This server is the leader: removing it starts an election." />}
          <p>A removed server still running must be stopped, and not restarted with its old data.</p>
          {mode === 'none' && (
            <label>
              {'Operator token '}
              <input type="password" aria-label="Operator token" autoComplete="off" value={operatorToken}
                onChange={(event) => setOperatorToken(event.target.value)} />
              <Typography.Text type="secondary"> for this removal only; it is not kept</Typography.Text>
            </label>
          )}
        </>
      )}
      action="Remove"
      idempotent
      submit={() => api.removeServer(server.serverId, mode === 'none' ? operatorToken : undefined)}
      onClose={onClose}
    />
  );
}
