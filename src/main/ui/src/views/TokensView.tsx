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
import { type ReactNode, useState } from 'react';
import { isManagement, useAccess } from '../access';
import type { QraftApi, TokenBody } from '../api/client';
import type { AclStatus, Policy, Token } from '../api/schemas';
import { useLiveRead } from '../live';
import { RouteLink, useRouter } from '../routing';
import { Failure, formatInstant, PlainTable, ReadMeta } from './common';
import { ExplainPanel } from './ExplainPanel';
import { Mutation } from './Mutation';

/** The anonymous token's accessor ID, a built-in. */
export const ANONYMOUS_ACCESSOR = '00000000-0000-0000-0000-000000000002';

/** How soon a token expires for the list's filter and the token menu to warn of it. */
const SOON_MILLIS = 60 * 60_000;

/**
 * The Access section's own navigation: Status, Tokens, Policies, Explain; without access control only Status, since
 * the server then has no tokens or policies.
 */
export function AccessNav() {
  const { mode } = useAccess();
  return (
    <nav aria-label="Access pages">
      <Space split="|">
        <RouteLink to={{ view: 'access' }}>Status</RouteLink>
        {mode === 'token' && <RouteLink to={{ view: 'tokens' }}>Tokens</RouteLink>}
        {mode === 'token' && <RouteLink to={{ view: 'policies' }}>Policies</RouteLink>}
        {mode === 'token' && <RouteLink to={{ view: 'explain' }}>Explain</RouteLink>}
      </Space>
    </nav>
  );
}

/** What marks a token apart in lists: the management tokens, the one from the file, and the anonymous token. */
export function marksOf(token: Token): string[] {
  const marks: string[] = [];
  if (isManagement(token)) marks.push('Management token');
  if (token.installedFromFile) marks.push('Installed from the management file');
  if (token.accessorId === ANONYMOUS_ACCESSOR) marks.push('Anonymous token');
  return marks;
}

type ServiceIdentity = Token['serviceIdentities'][number];

const serviceText = (identity: ServiceIdentity) => `${identity.serviceName} in ${identity.tenant}/${identity.namespace}`;

function identitiesText(token: Token): string[] {
  return [
    ...token.nodeIdentities.map((identity) => `Node ${identity.nodeName}`),
    ...token.serviceIdentities.map((identity) => `Service ${serviceText(identity)}`),
  ];
}

const policyName = (link: { id: string; name: string | null }) => link.name ?? link.id;

const expiryText = (token: Token) =>
  token.expirationTime === null ? 'Does not expire' : `Expires ${formatInstant(token.expirationTime)}`;

/** The Tokens list (UI/UX design section 10.2.3): by accessor ID, with its links, marks and filters. */
export function TokensView({ api }: { api: QraftApi }) {
  const { may } = useAccess();
  const [state, again] = useLiveRead(({ signal }) => api.tokens({}, { signal }), [api]);
  const [policies] = useLiveRead(({ signal }) => api.policies({ signal }), [api]);
  const [policy, setPolicy] = useState('');
  const [identity, setIdentity] = useState('');
  const [soon, setSoon] = useState(false);
  const [creating, setCreating] = useState(false);
  const shown = state.status === 'ready' ? state.value : state.previous;
  const known = policies.status === 'ready' ? policies.value.data : policies.previous?.data ?? [];
  const needle = identity.trim().toLowerCase();
  const tokens = (shown?.data ?? []).filter((token) =>
    (policy === '' || token.policies.some((link) => link.id === policy))
    && (needle === '' || identitiesText(token).some((text) => text.toLowerCase().includes(needle)))
    && (!soon || (token.expirationTime !== null && Date.parse(token.expirationTime) - Date.now() <= SOON_MILLIS)));

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Tokens</Typography.Title>
      <AccessNav />
      <Typography.Text>
        A token is named by its accessor ID; no view shows a secret after the create that made it.
      </Typography.Text>
      {may.aclWrite && !creating && <Button onClick={() => setCreating(true)}>Create token</Button>}
      {creating && <CreateToken api={api} policies={known} onClose={() => setCreating(false)} />}
      <Space wrap>
        <label>
          {'Policy '}
          <select aria-label="Policy" value={policy} onChange={(event) => setPolicy(event.target.value)}>
            <option value="">Any policy</option>
            {known.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
        <label>
          {'Identity '}
          <input aria-label="Identity" value={identity} onChange={(event) => setIdentity(event.target.value)} />
        </label>
        <label>
          <input type="checkbox" aria-label="Expiring soon" checked={soon} onChange={(event) => setSoon(event.target.checked)} />
          {' Expiring within the hour'}
        </label>
      </Space>
      {state.status === 'failed' && <Failure title="Tokens could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
      <PlainTable<Token>
        label="Tokens"
        rowKey={(token) => token.accessorId}
        rows={tokens}
        empty={shown === undefined ? 'Reading tokens…' : 'No token matches.'}
        columns={[
          { title: 'Accessor ID', render: (token) => <RouteLink to={{ view: 'token', accessorId: token.accessorId }}>{token.accessorId}</RouteLink> },
          { title: 'Description', render: (token) => token.description ?? '' },
          { title: 'Policies', render: (token) => token.policies.map(policyName).join(', ') },
          { title: 'Identities', render: (token) => identitiesText(token).join(', ') },
          { title: 'Expiry', render: (token) => expiryText(token) },
          { title: 'Created', render: (token) => formatInstant(token.createTime) },
          { title: 'Marks', render: (token) => marksOf(token).join(', ') },
        ]}
      />
    </Space>
  );
}

/** The fields a token form gives: a body to send, and the words its review shows. */
interface TokenFields {
  description: string;
  policyIds: string[];
  nodes: string[];
  services: ServiceIdentity[];
  ttl: string;
}

const list = (text: string) => text.split(',').map((item) => item.trim()).filter((item) => item !== '');

/** `name`, or `name@tenant/namespace`; null when it is neither. */
function serviceIdentityOf(text: string): ServiceIdentity | null {
  const match = /^([^@/\s]+)(?:@([^/\s]+)\/([^/\s]+))?$/.exec(text);
  if (match === null) return null;
  return { serviceName: match[1] as string, tenant: match[2] ?? 'default', namespace: match[3] ?? 'default' };
}

const serviceInput = (identity: ServiceIdentity) =>
  identity.tenant === 'default' && identity.namespace === 'default' ? identity.serviceName
    : `${identity.serviceName}@${identity.tenant}/${identity.namespace}`;

/**
 * A token's description, policies and identities, and for a create its time to live. Checked locally only for its
 * form; the server decides whether it accepts the token.
 */
function TokenForm({ policies, initial, withTtl, onReview, onCancel }: {
  policies: Policy[];
  initial?: Token;
  withTtl: boolean;
  onReview: (fields: TokenFields) => void;
  onCancel: () => void;
}) {
  const [description, setDescription] = useState(initial?.description ?? '');
  const [chosen, setChosen] = useState<string[]>(initial?.policies.map((link) => link.id) ?? []);
  const [nodes, setNodes] = useState(initial?.nodeIdentities.map((identity) => identity.nodeName).join(', ') ?? '');
  const [services, setServices] = useState(initial?.serviceIdentities.map(serviceInput).join(', ') ?? '');
  const [ttl, setTtl] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const review = () => {
    const parsed = list(services).map(serviceIdentityOf);
    if (parsed.some((identity) => identity === null)) {
      setProblem('A service identity is a name, or name@tenant/namespace.');
      return;
    }
    setProblem(null);
    onReview({ description: description.trim(), policyIds: chosen, nodes: list(nodes),
      services: parsed as ServiceIdentity[], ttl: ttl.trim() });
  };

  return (
    <section aria-label="Token form" style={{ display: 'grid', gap: 8, maxWidth: 640 }}>
      <label>
        {'Description '}
        <input aria-label="Description" value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <fieldset>
        <legend>Policies</legend>
        {policies.map((policy) => (
          <label key={policy.id} style={{ display: 'block' }}>
            <input
              type="checkbox"
              aria-label={`Policy ${policy.name}`}
              checked={chosen.includes(policy.id)}
              onChange={(event) => setChosen((current) => event.target.checked ? [...current, policy.id]
                : current.filter((id) => id !== policy.id))}
            />
            {` ${policy.name}`}
          </label>
        ))}
      </fieldset>
      <label>
        {'Node identities '}
        <input aria-label="Node identities" value={nodes} onChange={(event) => setNodes(event.target.value)} />
      </label>
      <label>
        {'Service identities '}
        <input aria-label="Service identities" value={services} onChange={(event) => setServices(event.target.value)} />
        <Typography.Text type="secondary"> name, or name@tenant/namespace, separated by commas</Typography.Text>
      </label>
      {withTtl && (
        <label>
          {'Time to live '}
          <input aria-label="Time to live" value={ttl} onChange={(event) => setTtl(event.target.value)} />
          <Typography.Text type="secondary"> such as 30m or 1h, at most 24h; empty for no expiry</Typography.Text>
        </label>
      )}
      {problem !== null && <Alert type="error" message={problem} />}
      <Space>
        <Button type="primary" onClick={review}>Review</Button>
        <Button onClick={onCancel}>Cancel</Button>
      </Space>
    </section>
  );
}

function bodyOf(fields: TokenFields, withTtl: boolean): TokenBody {
  const body: TokenBody = {
    description: fields.description,
    policies: fields.policyIds.map((id) => ({ id })),
    nodeIdentities: fields.nodes.map((nodeName) => ({ nodeName })),
    serviceIdentities: fields.services,
  };
  if (withTtl && fields.ttl !== '') body.expirationTtl = fields.ttl;
  return body;
}

const namesOf = (ids: string[], policies: Policy[]) =>
  ids.map((id) => policies.find((policy) => policy.id === id)?.name ?? id);

/** A secret shown once, with a copy control, cleared when the panel that holds it closes. */
function NewSecret({ secret }: { secret: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <section aria-label="New secret" style={{ marginTop: 8 }}>
      <p>
        {'Secret: '}
        <code>{secret}</code>{' '}
        <Button size="small" onClick={() => {
          navigator.clipboard?.writeText(secret).then(() => setCopied(true), () => setCopied(false));
        }}>
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </p>
      <Alert type="warning" message="This secret cannot be shown again. Copy it now: closing this panel clears it." />
    </section>
  );
}

/** Create (UI/UX design section 10.2.3): the form, the review, the one-time secret, and an unknown outcome settled. */
function CreateToken({ api, policies, onClose }: { api: QraftApi; policies: Policy[]; onClose: () => void }) {
  const [fields, setFields] = useState<TokenFields | null>(null);
  if (fields === null) return <TokenForm policies={policies} withTtl onReview={setFields} onCancel={onClose} />;
  const services = fields.services.map(serviceText);
  return (
    <Mutation
      title="Create token"
      identity={[['Description', fields.description === '' ? 'None' : fields.description]]}
      review={(
        <>
          <p>{`Policies: ${fields.policyIds.length === 0 ? 'none' : namesOf(fields.policyIds, policies).join(', ')}`}</p>
          <p>{`Node identities: ${fields.nodes.length === 0 ? 'none' : fields.nodes.join(', ')}`}</p>
          <p>{`Service identities: ${services.length === 0 ? 'none' : services.join(', ')}`}</p>
          <p>{fields.ttl === '' ? 'Does not expire' : `Expires ${fields.ttl} after it is created`}</p>
          <p>
            Creating a token is not idempotent: each attempt makes a new secret, so the interface never sends it again
            by itself.
          </p>
        </>
      )}
      action="Create"
      idempotent={false}
      submit={() => api.createToken(bodyOf(fields, true))}
      refresh={async () => {
        const found = (await api.tokens()).data.filter((token) => (token.description ?? '') === fields.description);
        if (found.length === 0) return `No token has the description ${fields.description}: the token was not created.`;
        return (
          <>
            {`${found.length} ${found.length === 1 ? 'token has' : 'tokens have'} the description ${fields.description}: `
              + 'it may be the one just created, with nobody holding its secret. Delete it before creating again: '}
            {found.map((token) => (
              <RouteLink key={token.accessorId} to={{ view: 'token', accessorId: token.accessorId }}>{token.accessorId}</RouteLink>
            ))}
          </>
        );
      }}
      committed={(answer) => <NewSecret secret={answer.data.secretId} />}
      onClose={onClose}
    />
  );
}

type Panel = 'none' | 'edit' | 'delete' | 'replace';

/** A token (UI/UX design section 10.2.3): its links, its explanation, and its update, deletion and replacement. */
export function TokenView({ api, accessorId }: { api: QraftApi; accessorId: string }) {
  const access = useAccess();
  const { navigate } = useRouter();
  const [state, again] = useLiveRead(({ signal }) => api.token(accessorId, { signal }), [api, accessorId]);
  const [policies] = useLiveRead(({ signal }) => api.policies({ signal }), [api]);
  const [status] = useLiveRead(({ signal }) => api.aclStatus({ signal }), [api]);
  const [panel, setPanel] = useState<Panel>('none');
  const shown = state.status === 'ready' ? state.value : state.previous;
  const known = policies.status === 'ready' ? policies.value.data : policies.previous?.data ?? [];
  const security = status.status === 'ready' ? status.value.data : status.previous?.data;
  const token = shown?.data;
  const anonymous = accessorId === ANONYMOUS_ACCESSOR;
  const inUse = access.token?.accessorId === accessorId;

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{`Token ${token?.description || accessorId}`}</Typography.Title>
      <AccessNav />
      {state.status === 'failed' && <Failure title="The token could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && <ReadMeta read={shown} stale={state.status !== 'ready'} operator />}
      {token !== undefined && (
        <>
          <dl style={{ display: 'grid', gridTemplateColumns: 'max-content auto', columnGap: 16, rowGap: 4 }}>
            <Field label="Accessor ID">{token.accessorId}</Field>
            <Field label="Description">{token.description || 'None'}</Field>
            <Field label="Created">{formatInstant(token.createTime)}</Field>
            <Field label="Expiry">{expiryText(token)}</Field>
            <Field label="Marks">{marksOf(token).join(', ') || 'None'}</Field>
            <Field label="Policies">
              {token.policies.length === 0 ? 'None' : token.policies.map((link) => (
                <span key={link.id} style={{ marginRight: 8 }}>
                  <RouteLink to={{ view: 'policy', id: link.id }}>{policyName(link)}</RouteLink>
                </span>
              ))}
            </Field>
            {token.roles.length > 0 && <Field label="Roles">{token.roles.map(policyName).join(', ')}</Field>}
            <Field label="Node identities">
              {token.nodeIdentities.length === 0 ? 'None' : token.nodeIdentities.map((identity) => (
                <span key={identity.nodeName} style={{ marginRight: 8 }}>
                  <RouteLink to={{ view: 'nodes' }}>{identity.nodeName}</RouteLink>
                </span>
              ))}
            </Field>
            <Field label="Service identities">
              {token.serviceIdentities.length === 0 ? 'None' : token.serviceIdentities.map((identity) => (
                <span key={serviceText(identity)} style={{ marginRight: 8 }}>
                  <RouteLink to={{ view: 'service', name: identity.serviceName }}
                    scope={{ tenant: identity.tenant, namespace: identity.namespace }}>
                    {serviceText(identity)}
                  </RouteLink>
                </span>
              ))}
            </Field>
          </dl>
          {anonymous && (
            <Typography.Text>
              The anonymous token cannot be deleted. Its policies can be changed: every caller without a token gets them.
            </Typography.Text>
          )}
          {inUse && <Typography.Text>This is the token in use: sign in with another token to delete it.</Typography.Text>}
          {access.may.aclWrite && panel === 'none' && (
            <Space>
              <Button onClick={() => setPanel('edit')}>Edit</Button>
              {!anonymous && <Button onClick={() => setPanel('replace')}>Replace</Button>}
              {!anonymous && !inUse && <Button danger onClick={() => setPanel('delete')}>Delete</Button>}
            </Space>
          )}
          {panel === 'edit' && <UpdateToken api={api} token={token} policies={known} inUse={inUse} onClose={() => setPanel('none')} />}
          {panel === 'delete' && (
            <DeleteToken api={api} token={token} status={security}
              onClose={(deleted) => (deleted ? navigate({ view: 'tokens' }) : setPanel('none'))} />
          )}
          {panel === 'replace' && (
            <Mutation
              title="Replace token"
              identity={[['Accessor ID', token.accessorId], ['Description', token.description || 'None']]}
              review={(
                <>
                  <p>A secret cannot change once a token exists. Replacement takes three steps:</p>
                  <p>1. Clone this token: the clone has its policies, identities and expiry, and a new secret.</p>
                  <p>2. Move this token&apos;s users to the clone&apos;s secret.</p>
                  <p>3. Delete this token.</p>
                </>
              )}
              action="Clone"
              idempotent={false}
              submit={() => api.cloneToken(token.accessorId, `Replacement of ${token.description || token.accessorId}`)}
              committed={(answer) => (
                <>
                  <NewSecret secret={answer.data.secretId} />
                  {!inUse && (
                    <Button onClick={() => setPanel('delete')}>
                      Delete this token, once its users have the clone&apos;s secret
                    </Button>
                  )}
                </>
              )}
              onClose={() => setPanel('none')}
            />
          )}
          {access.may.aclRead && <ExplainPanel api={api} accessorId={token.accessorId} />}
        </>
      )}
    </Space>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div style={{ display: 'contents' }}>
      <dt style={{ fontWeight: 600 }}>{label}</dt>
      <dd style={{ margin: 0 }}>{children}</dd>
    </div>
  );
}

const added = (before: string[], after: string[]) => after.filter((item) => !before.includes(item));

/** Update: the review lists the policies and identities added and removed. An expiry cannot change. */
function UpdateToken({ api, token, policies, inUse, onClose }: {
  api: QraftApi;
  token: Token;
  policies: Policy[];
  inUse: boolean;
  onClose: () => void;
}) {
  const [fields, setFields] = useState<TokenFields | null>(null);
  if (fields === null) {
    return <TokenForm policies={policies} initial={token} withTtl={false} onReview={setFields} onCancel={onClose} />;
  }
  const beforePolicies = token.policies.map((link) => link.id);
  const beforeNodes = token.nodeIdentities.map((identity) => identity.nodeName);
  const beforeServices = token.serviceIdentities.map(serviceText);
  const afterServices = fields.services.map(serviceText);
  const changes: string[] = [];
  const say = (what: string, items: string[]) => {
    if (items.length > 0) changes.push(`${what}: ${items.join(', ')}`);
  };
  if ((token.description ?? '') !== fields.description) changes.push(`Description: ${fields.description || 'none'}`);
  say('Policies added', namesOf(added(beforePolicies, fields.policyIds), policies));
  say('Policies removed', namesOf(added(fields.policyIds, beforePolicies), policies));
  say('Node identities added', added(beforeNodes, fields.nodes));
  say('Node identities removed', added(fields.nodes, beforeNodes));
  say('Service identities added', added(beforeServices, afterServices));
  say('Service identities removed', added(afterServices, beforeServices));
  return (
    <Mutation
      title="Update token"
      identity={[['Accessor ID', token.accessorId]]}
      review={(
        <>
          {changes.length === 0 ? <p>Nothing changes.</p> : changes.map((change) => <p key={change}>{change}</p>)}
          {inUse && (
            <Alert type="warning" message={'This is the token in use: if the change removes its acl:write, the server allows it '
              + 'only while another token holds acl:write without an expiry.'} />
          )}
        </>
      )}
      action="Save"
      idempotent
      submit={() => api.updateToken(token.accessorId, bodyOf(fields, false))}
      onClose={onClose}
    />
  );
}

/** Delete, which is revocation: what depends on the token, what revocation does, and the lock-out guard. */
function DeleteToken({ api, token, status, onClose }: {
  api: QraftApi;
  token: Token;
  status: AclStatus | undefined;
  onClose: (deleted: boolean) => void;
}) {
  const [deleted, setDeleted] = useState(false);
  const writer = status?.aclWriters.some((item) => item.accessorId === token.accessorId) ?? false;
  const others = (status?.aclWriters.length ?? 0) - (writer ? 1 : 0);
  return (
    <Mutation
      title="Delete token"
      identity={[['Accessor ID', token.accessorId], ['Description', token.description || 'None']]}
      review={(
        <>
          {token.nodeIdentities.map((identity) => (
            <p key={identity.nodeName}>
              {`The client of node ${identity.nodeName} becomes not ready, and its node unreachable after the node TTL.`}
            </p>
          ))}
          {token.serviceIdentities.map((identity) => (
            <p key={serviceText(identity)}>
              {`What registers ${serviceText(identity)} with this token can no longer do so.`}
            </p>
          ))}
          <p>
            Revocation takes effect on the leader at once, and on each follower when it applies the deletion&apos;s index;
            until then a follower may still accept the token for reads. A client may go on accepting it until its cache
            entry expires, or for as long as it reaches no server.
          </p>
          {isManagement(token) && token.installedFromFile && (
            <Alert type="warning" message={"It returns at the next leadership change while any server's file holds its "
              + "secret. Revocation order: Replace the secret in every server's file, restart the leader so the new "
              + 'token is installed, check it exists, then delete the old one.'} />
          )}
          {writer && others === 0 && (
            <Alert type="warning" message={'This is the only token holding acl:write without an expiry: the server will '
              + 'refuse its deletion (acl_lockout).'} />
          )}
          {writer && others > 0 && (
            <p>{`${others} other ${others === 1 ? 'token holds' : 'tokens hold'} acl:write without an expiry.`}</p>
          )}
        </>
      )}
      action="Delete"
      idempotent
      submit={async () => {
        const answer = await api.deleteToken(token.accessorId);
        setDeleted(true);
        return answer;
      }}
      onClose={() => onClose(deleted)}
    />
  );
}
