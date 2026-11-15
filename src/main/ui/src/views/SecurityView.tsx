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
import { useAccess } from '../access';
import type { QraftApi } from '../api/client';
import type { AclStatus } from '../api/schemas';
import type { Bootstrap } from '../bootstrap';
import { useLiveRead } from '../live';
import { Failure, formatInstant, ReadMeta } from './common';
import { AccessNav } from './TokensView';

/**
 * The Access section's Status page (UI/UX design section 10.2.1): whether the cluster's access control can be trusted.
 * With access control on it reads the security status, which the server decides with the authorizer it enforces
 * with; the interface evaluates no rule itself. Without it, the page says so from the bootstrap object.
 */
export function SecurityView({ api, bootstrap }: { api: QraftApi; bootstrap: Bootstrap }) {
  const { mode } = useAccess();
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Access control</Typography.Title>
      <AccessNav />
      {mode === 'none' ? (
        <section aria-label="Security status">
          <p>{`Security profile: ${bootstrap.securityProfile}`}</p>
          <Alert type="warning" message="No access control: anyone who can reach this API can change the cluster." />
        </section>
      ) : (
        <TokenStatus api={api} />
      )}
    </Space>
  );
}

function TokenStatus({ api }: { api: QraftApi }) {
  const [state, again] = useLiveRead(({ signal }) => api.aclStatus({ signal }), [api]);
  const shown = state.status === 'ready' ? state.value : state.previous;
  return (
    <>
      {state.status === 'failed' && <Failure title="The security status could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the security status…</Typography.Text>}
      {shown !== undefined && <StatusSections status={shown.data} />}
    </>
  );
}

const name = (token: { accessorId: string; description: string | null }) =>
  token.description ? `${token.description} (${token.accessorId})` : token.accessorId;

function StatusSections({ status }: { status: AclStatus }) {
  const writers = status.aclWriters.length;
  const anonymous = status.anonymous;
  return (
    <section aria-label="Security status">
      <Typography.Title level={3}>Settings</Typography.Title>
      <p>{`Security profile: ${status.securityProfile}`}</p>
      <p>{status.enabled ? `ACLs enabled, default policy ${status.defaultPolicy}` : 'ACLs disabled'}</p>
      {status.transport.https ? (
        <p>{status.transport.clientCertificateRequired ? 'HTTPS, client certificate required'
          : 'HTTPS, no client certificate required'}</p>
      ) : (
        <Alert type="warning" message="This listener serves plain HTTP: tokens cross the network unencrypted." />
      )}

      <Typography.Title level={3}>Management tokens</Typography.Title>
      {status.managementTokens.length === 0 ? (
        <p>No management token exists.</p>
      ) : (
        <ul>
          {status.managementTokens.map((token) => (
            <li key={token.accessorId}>
              {name(token)}
              {token.installedFromFile && ' · Installed from the management file'}
              {token.expirationTime !== null && ` · Expires ${formatInstant(token.expirationTime)}`}
            </li>
          ))}
        </ul>
      )}

      <Typography.Title level={3}>The anonymous token</Typography.Title>
      <p>Every request without a token acts as the anonymous token.</p>
      {anonymous === undefined || anonymous.policies.length === 0 ? (
        <p>The anonymous token holds no policy.</p>
      ) : (
        <p>{`Policies: ${anonymous.policies.map((policy) => policy.name ?? policy.id).join(', ')}`}</p>
      )}
      {anonymous !== undefined && anonymous.writes.length > 0 && (
        <Alert type="warning"
          message={`The anonymous token may write ${anonymous.writes.join(', ')}: every caller without a token can.`} />
      )}

      <Typography.Title level={3}>Who can change access control</Typography.Title>
      <p>
        {writers === 0 ? 'No token holds acl:write without an expiry.'
          : `${writers} ${writers === 1 ? 'token holds' : 'tokens hold'} acl:write without an expiry`}
      </p>
      <ul>
        {status.aclWriters.map((token) => <li key={token.accessorId}>{name(token)}</li>)}
      </ul>
      {writers === 1 && (
        <Alert type="warning"
          message="Only one token holds acl:write without an expiry: the next change to it would be a lock-out." />
      )}

      <Typography.Title level={3}>Expiring soon</Typography.Title>
      {status.expiringSoon.length === 0 ? (
        <p>{`No token expires within ${status.expiringWithinMinutes} minutes.`}</p>
      ) : (
        <ul>
          {status.expiringSoon.map((token) => (
            <li key={token.accessorId}>
              {`${name(token)} · Expires ${formatInstant(token.expirationTime)}`}
              {token.nodeIdentities.map((identity) => ` · Node identity ${identity.nodeName}`).join('')}
              {token.serviceIdentities.map((identity) =>
                ` · Service identity ${identity.serviceName} in ${identity.tenant}/${identity.namespace}`).join('')}
              {token.holdsAclWrite && ' · Holds acl:write'}
            </li>
          ))}
        </ul>
      )}

      <Typography.Title level={3}>Recovery</Typography.Title>
      <p>
        When every management secret is lost, recovery is a new secret in the management file and a restart of the
        leader, or a bootstrap reset through a file in the leader&apos;s data directory. The interface cannot perform
        either; the operations runbook describes both.
      </p>
    </section>
  );
}
