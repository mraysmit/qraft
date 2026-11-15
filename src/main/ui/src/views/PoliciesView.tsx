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

import { Alert, Button, Space, Typography } from 'antd';
import { useState } from 'react';
import { useAccess } from '../access';
import type { PolicyBody, QraftApi } from '../api/client';
import type { Policy, Token } from '../api/schemas';
import { useLiveRead } from '../live';
import { RouteLink, useRouter } from '../routing';
import { Failure, PlainTable, ReadMeta } from './common';
import { ExplainPanel } from './ExplainPanel';
import { Mutation } from './Mutation';
import { PRECEDENCE, ruleText, rulesOf, rulesSummary } from './rules';
import { AccessNav } from './TokensView';

/** The built-in policies, `global-management` and `builtin/global-read-only`, which cannot be deleted. */
const BUILT_IN = new Set(['00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002']);

const tokenName = (token: Token) => (token.description ? `${token.description} (${token.accessorId})` : token.accessorId);

const linkedCount = (count: number) => `${count} ${count === 1 ? 'token' : 'tokens'}`;

/** The Policies list (UI/UX design section 10.2.4): name, a summary of the rules, and how many tokens link to it. */
export function PoliciesView({ api }: { api: QraftApi }) {
  const { may } = useAccess();
  const [state, again] = useLiveRead(({ signal }) => api.policies({ signal }), [api]);
  const [tokens] = useLiveRead(({ signal }) => api.tokens({}, { signal }), [api]);
  const [creating, setCreating] = useState(false);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const linked = tokens.status === 'ready' ? tokens.value.data : tokens.previous?.data;
  const countOf = (policy: Policy) => linked === undefined ? 'Not read'
    : linkedCount(linked.filter((token) => token.policies.some((link) => link.id === policy.id)).length);

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Policies</Typography.Title>
      <AccessNav />
      {may.aclWrite && !creating && <Button onClick={() => setCreating(true)}>Create policy</Button>}
      {creating && <CreatePolicy api={api} onClose={() => setCreating(false)} />}
      {state.status === 'failed' && <Failure title="Policies could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
      <PlainTable<Policy>
        label="Policies"
        rowKey={(policy) => policy.id}
        rows={shown?.data ?? []}
        empty={shown === undefined ? 'Reading policies…' : 'No policy exists.'}
        columns={[
          { title: 'Name', render: (policy) => <RouteLink to={{ view: 'policy', id: policy.id }}>{policy.name}</RouteLink> },
          { title: 'Description', render: (policy) => policy.description ?? '' },
          { title: 'Rules', render: (policy) => rulesSummary(policy.rules) },
          { title: 'Linked tokens', render: (policy) => countOf(policy) },
          { title: 'Marks', render: (policy) => (BUILT_IN.has(policy.id) ? 'Built-in' : '') },
        ]}
      />
    </Space>
  );
}

/** A policy's rules as rows, with the precedence the server applies; for reading, never for deciding. */
function RulesTable({ rules }: { rules: Record<string, unknown> }) {
  const rows = rulesOf(rules);
  return (
    <section aria-label="Rules">
      <table>
        <thead>
          <tr><th>Rule</th><th>Match</th><th>Access</th><th>Scope</th></tr>
        </thead>
        <tbody>
          {rows.map((row, at) => (
            <tr key={`${ruleText(row)}-${row.tenant ?? ''}-${row.namespace ?? ''}-${at}`}>
              <td>{ruleText(row)}</td>
              <td>{row.match === 'single' ? 'The resource' : row.match === 'prefix' ? 'Prefix' : 'Exact name'}</td>
              <td>{row.access}</td>
              <td>{row.tenant === undefined ? '' : `Tenant ${row.tenant}, namespace ${row.namespace ?? 'default'}`}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p>{`${PRECEDENCE} A name ending * in a scope is a prefix; the empty prefix matches any.`}</p>
    </section>
  );
}

/** Name, description and rules in Consul's JSON form, checked locally only to be JSON; the server decides the rest. */
function PolicyForm({ initial, onReview, onCancel }: {
  initial?: Policy;
  onReview: (body: PolicyBody) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [rules, setRules] = useState(initial === undefined ? '{}' : JSON.stringify(initial.rules, null, 2));
  const [problem, setProblem] = useState<string | null>(null);

  const review = () => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(rules);
    } catch (error) {
      setProblem(`The rules are not valid JSON: ${error instanceof Error ? error.message : String(error)}`);
      return;
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setProblem('The rules must be a JSON object.');
      return;
    }
    setProblem(null);
    onReview({ name: name.trim(), description: description.trim(), rules: parsed as Record<string, unknown> });
  };

  return (
    <section aria-label="Policy form" style={{ display: 'grid', gap: 8, maxWidth: 720 }}>
      <label>
        {'Name '}
        <input aria-label="Name" value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <label>
        {'Description '}
        <input aria-label="Description" value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <label>
        {'Rules (JSON) '}
        <textarea aria-label="Rules (JSON)" rows={10} cols={80} value={rules} onChange={(event) => setRules(event.target.value)} />
      </label>
      <Typography.Text type="secondary">
        A rule names a resource, an exact name or a prefix, and read, write or deny; service and key rules may be nested
        in a tenant and a namespace. The server checks the rules when the change is submitted.
      </Typography.Text>
      {problem !== null && <Alert type="error" message={problem} />}
      <Space>
        <Button type="primary" onClick={review}>Review</Button>
        <Button onClick={onCancel}>Cancel</Button>
      </Space>
    </section>
  );
}

function CreatePolicy({ api, onClose }: { api: QraftApi; onClose: () => void }) {
  const [body, setBody] = useState<PolicyBody | null>(null);
  if (body === null) return <PolicyForm onReview={setBody} onCancel={onClose} />;
  return (
    <Mutation
      title="Create policy"
      identity={[['Name', body.name], ['Description', body.description || 'None']]}
      review={(
        <>
          <p>{`Rules: ${rulesSummary(body.rules) || 'none'}`}</p>
          <RulesTable rules={body.rules} />
        </>
      )}
      action="Create"
      idempotent={false}
      submit={() => api.createPolicy(body)}
      committed={(answer) => (
        <p><RouteLink to={{ view: 'policy', id: answer.data.id }}>Open the policy</RouteLink></p>
      )}
      onClose={onClose}
    />
  );
}

type Panel = 'none' | 'edit' | 'delete';

/**
 * A policy (UI/UX design section 10.2.4): its rules structured, the tokens that link to it, and its change and
 * deletion, each reviewed by the linked tokens it affects. The built-ins cannot be deleted.
 */
export function PolicyView({ api, id }: { api: QraftApi; id: string }) {
  const { may } = useAccess();
  const { navigate } = useRouter();
  const [state, again] = useLiveRead(({ signal }) => api.policy(id, { signal }), [api, id]);
  const [tokens] = useLiveRead(({ signal }) => api.tokens({ policy: id }, { signal }), [api, id]);
  const [panel, setPanel] = useState<Panel>('none');
  const [body, setBody] = useState<PolicyBody | null>(null);
  const [deleted, setDeleted] = useState(false);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const policy = shown?.data;
  const linked = tokens.status === 'ready' ? tokens.value.data : tokens.previous?.data;
  const builtIn = BUILT_IN.has(id);
  const affected = linked === undefined ? 'The linked tokens are being read.'
    : linked.length === 0 ? 'No token links to it.' : `Tokens affected: ${linked.map(tokenName).join(', ')}`;

  const close = () => {
    setPanel('none');
    setBody(null);
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{`Policy ${policy?.name ?? id}`}</Typography.Title>
      <AccessNav />
      {state.status === 'failed' && <Failure title="The policy could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
      {policy !== undefined && (
        <>
          <Typography.Text>{policy.description || 'No description.'}</Typography.Text>
          <RulesTable rules={policy.rules} />
          <pre>{JSON.stringify(policy.rules, null, 2)}</pre>
          <section aria-label="Linked tokens">
            <Typography.Title level={3}>Linked tokens</Typography.Title>
            {linked === undefined && <p>Reading the linked tokens…</p>}
            {linked !== undefined && linked.length === 0 && <p>No token links to it.</p>}
            <ul>
              {linked?.map((token) => (
                <li key={token.accessorId}>
                  <RouteLink to={{ view: 'token', accessorId: token.accessorId }}>{token.accessorId}</RouteLink>
                  {token.description ? ` ${token.description}` : ''}
                </li>
              ))}
            </ul>
          </section>
          {builtIn && <Typography.Text>A built-in policy cannot be deleted.</Typography.Text>}
          {may.aclWrite && panel === 'none' && (
            <Space>
              <Button onClick={() => setPanel('edit')}>Edit</Button>
              {!builtIn && <Button danger onClick={() => setPanel('delete')}>Delete</Button>}
            </Space>
          )}
          {panel === 'edit' && body === null && <PolicyForm initial={policy} onReview={setBody} onCancel={close} />}
          {panel === 'edit' && body !== null && (
            <Mutation
              title="Update policy"
              identity={[['ID', policy.id], ['Name', body.name]]}
              review={(
                <>
                  <p>{`Rules: ${rulesSummary(body.rules) || 'none'}`}</p>
                  <RulesTable rules={body.rules} />
                  <p>{affected}</p>
                </>
              )}
              action="Save"
              idempotent
              submit={() => api.updatePolicy(policy.id, body)}
              onClose={close}
            />
          )}
          {panel === 'delete' && (
            <Mutation
              title="Delete policy"
              identity={[['ID', policy.id], ['Name', policy.name]]}
              review={(
                <>
                  <p>{affected}</p>
                  <p>
                    The tokens linked to it lose what it granted. A client may go on honouring the policy until its
                    cache entry expires.
                  </p>
                  <p>
                    The server refuses a deletion after which no token would hold acl:write without an expiry
                    (acl_lockout).
                  </p>
                </>
              )}
              action="Delete"
              idempotent
              submit={async () => {
                const answer = await api.deletePolicy(policy.id);
                setDeleted(true);
                return answer;
              }}
              onClose={() => (deleted ? navigate({ view: 'policies' }) : close())}
            />
          )}
          {panel === 'none' && <ExplainPanel api={api} />}
        </>
      )}
    </Space>
  );
}

/** The Explain page: any token, any request, answered by the server. */
export function ExplainView({ api }: { api: QraftApi }) {
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Explain</Typography.Title>
      <AccessNav />
      <Typography.Text>
        Choose a token and a request: the server says whether the token may make it, and the policy and rule, the
        identity, or the default policy that decided. Which tokens may do something has no API, and is not offered.
      </Typography.Text>
      <ExplainPanel api={api} />
    </Space>
  );
}
