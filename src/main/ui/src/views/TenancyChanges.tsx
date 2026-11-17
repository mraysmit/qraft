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

import { Space, Typography } from 'antd';
import { type ReactNode, useState } from 'react';
import { useAccess } from '../access';
import type { QraftApi, Read, Scope } from '../api/client';
import type { NamespaceHoldings, TenantHoldings } from '../api/schemas';
import { totalsOf, validScopeName } from '../api/tenancy';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { plural } from './common';
import { type Outcome, Mutation } from './Mutation';

// Tenancy changes (UI-6 Tenants, Namespaces and Audit task list, Phase 2): a tenant or namespace created, its
// description changed, or deleted after a review of what it removes (decision 3), each with operator:write and through
// the mutation workflow (decision 10). The default scopes are not offered for deletion.

const NAME_RULE = 'A name is lowercase letters, digits and dashes, starting and ending with a letter or digit, at most '
  + '63 characters.';
const NO_WRITE = 'Your token may not change tenants or namespaces: it needs operator:write.';
const DEFAULT = 'default';
const button = { marginRight: 8 };

/** Whether the token may change the tenancy, and if not the line that says so. */
function useOperatorWrite(): boolean {
  return useAccess().may.operatorWrite;
}

/** A deletion that found the scope gone: it was deleted already (decision 3). */
const deletedAlready = (outcome: Outcome) => (outcome.kind === 'not-found' ? <p>It was deleted already.</p> : null);

/** What a scope holds, in words: `2 services with 3 instances, and 3 keys`. */
function holdsText(services: number, instances: number, keys: number): string {
  return `${plural(services, 'service')} with ${plural(instances, 'instance')}, and ${plural(keys, 'key')}`;
}

/** The keys sessions hold in a scope, and that those sessions go on (decision 3). */
function heldText(heldKeys: number, sessions: readonly string[]): string {
  if (heldKeys === 0) return 'No session holds a key here.';
  const holders = sessions.length === 1 ? `session ${sessions[0]}` : `sessions ${sessions.join(', ')}`;
  return `${heldKeys === 1 ? '1 key is' : `${heldKeys} keys are`} held, by ${holders}: the ${
    sessions.length === 1 ? 'session goes' : 'sessions go'} on, holding nothing here.`;
}

/** A name and description being written, with what is wrong with them. */
function Draft({ name, description, onName, onDescription, problems }: {
  name?: string | undefined;
  description: string;
  onName?: ((name: string) => void) | undefined;
  onDescription: (description: string) => void;
  problems: string[];
}) {
  return (
    <>
      {onName !== undefined && (
        <label>{'Name '}<input aria-label="Name" value={name ?? ''} onChange={(event) => onName(event.target.value)} /></label>
      )}
      <label>
        {'Description '}
        <input aria-label="Description" value={description} onChange={(event) => onDescription(event.target.value)} />
      </label>
      {problems.map((problem) => <Typography.Text key={problem} type="danger">{problem}</Typography.Text>)}
    </>
  );
}

type CreatePanel = { kind: 'none' } | { kind: 'form' | 'review'; name: string; description: string; problems: string[] };

/** A creation's form and review: a tenant's, or a namespace's in `tenant`. */
function Create({ api, tenant }: { api: QraftApi; tenant?: string }) {
  const [panel, setPanel] = useState<CreatePanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  const what = tenant === undefined ? 'tenant' : 'namespace';
  if (panel.kind === 'none') {
    return (
      <button type="button" style={button} onClick={() => setPanel({ kind: 'form', name: '', description: '', problems: [] })}>
        {`Create a ${what}`}
      </button>
    );
  }
  if (panel.kind === 'form') {
    return (
      <Space direction="vertical">
        <Typography.Title level={3}>{tenant === undefined ? 'Create a tenant' : `Create a namespace in ${tenant}`}</Typography.Title>
        <Draft name={panel.name} description={panel.description} problems={panel.problems}
          onName={(name) => setPanel({ ...panel, name })} onDescription={(description) => setPanel({ ...panel, description })} />
        <div>
          <button type="button" style={button} onClick={() => {
            const problems = validScopeName(panel.name) ? [] : [NAME_RULE];
            setPanel({ ...panel, kind: problems.length === 0 ? 'review' : 'form', problems });
          }}>Review</button>
          <button type="button" onClick={close}>Cancel</button>
        </div>
      </Space>
    );
  }
  const { name, description } = panel;
  const label = tenant === undefined ? name : `${tenant}/${name}`;
  return (
    <Mutation<unknown>
      title={`Create ${what} ${label}`}
      identity={[[tenant === undefined ? 'Tenant' : 'Namespace', label], ['Description', description === '' ? 'None' : description]]}
      review={<>
        <p>{tenant === undefined ? 'It is created with its default namespace.' : 'It is created empty.'}</p>
        <p>It is created once: an unknown outcome is settled by reading the tenants, never by sending it again.</p>
      </>}
      action="Create"
      idempotent={false}
      submit={() => (tenant === undefined ? api.createTenant(name, description)
        : api.createNamespace(tenant, name, description)) as Promise<Read<unknown>>}
      committed={() => (
        <p>
          <RouteLink to={tenant === undefined ? { view: 'tenant', name } : { view: 'namespace', tenant, name }}>
            {`Open ${what} ${label}`}
          </RouteLink>
        </p>
      )}
      onClose={close}
    />
  );
}

/** The Tenants page's change: a new tenant. */
export function CreateTenant({ api }: { api: QraftApi }) {
  if (!useOperatorWrite()) return <Typography.Text>{NO_WRITE}</Typography.Text>;
  return <div><Create api={api} /></div>;
}

type ScopePanel = { kind: 'none' | 'delete' } | { kind: 'edit' | 'review'; description: string };

/** A description's form, its review and its submission. */
function EditDescription({ title, current, panel, setPanel, submit, close }: {
  title: string;
  current: string;
  panel: Extract<ScopePanel, { kind: 'edit' | 'review' }>;
  setPanel: (panel: ScopePanel) => void;
  submit: (description: string) => Promise<Read<unknown>>;
  close: () => void;
}) {
  if (panel.kind === 'edit') {
    return (
      <Space direction="vertical">
        <Typography.Title level={3}>{title}</Typography.Title>
        <Draft description={panel.description} problems={[]} onDescription={(description) => setPanel({ ...panel, description })} />
        <div>
          <button type="button" style={button} onClick={() => setPanel({ ...panel, kind: 'review' })}>Review</button>
          <button type="button" onClick={close}>Cancel</button>
        </div>
      </Space>
    );
  }
  return (
    <Mutation<unknown>
      title={title}
      identity={[]}
      review={<p>{`Description: ${current === '' ? 'none' : current} → ${panel.description === '' ? 'none' : panel.description}`}</p>}
      action="Save"
      idempotent
      submit={() => submit(panel.description)}
      onClose={close}
    />
  );
}

/** A tenant's changes: a namespace created in it, its description, and its deletion. */
export function TenantChanges({ api, tenant }: { api: QraftApi; tenant: TenantHoldings }) {
  const writable = useOperatorWrite();
  const [panel, setPanel] = useState<ScopePanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  if (!writable) return <Typography.Text>{NO_WRITE}</Typography.Text>;
  if (panel.kind === 'edit' || panel.kind === 'review') {
    return <EditDescription title={`Update tenant ${tenant.Name}`} current={tenant.Description} panel={panel}
      setPanel={setPanel} submit={(description) => api.updateTenant(tenant.Name, description)} close={close} />;
  }
  if (panel.kind === 'delete') {
    const totals = totalsOf(tenant);
    const sessions = [...new Set(tenant.Namespaces.flatMap((namespace) => namespace.Sessions))];
    return (
      <Mutation<boolean>
        title={`Delete tenant ${tenant.Name}`}
        identity={[['Tenant', tenant.Name]]}
        review={<>
          <p>{`Its ${plural(totals.namespaces, 'namespace')} ${totals.namespaces === 1 ? 'holds' : 'hold'} ${
            holdsText(totals.services, totals.instances, totals.keys)}: all are removed.`}</p>
          <p>{heldText(totals.heldKeys, sessions)}</p>
        </>}
        action="Delete"
        idempotent
        submit={() => api.deleteTenant(tenant.Name)}
        detail={deletedAlready}
        onClose={close}
      />
    );
  }
  return (
    <div>
      <Create api={api} tenant={tenant.Name} />
      <button type="button" style={button} onClick={() => setPanel({ kind: 'edit', description: tenant.Description })}>
        Edit the description
      </button>
      {tenant.Name === DEFAULT ? <Typography.Text>The default tenant cannot be deleted.</Typography.Text>
        : <button type="button" style={button} onClick={() => setPanel({ kind: 'delete' })}>Delete this tenant</button>}
    </div>
  );
}

/** A namespace's changes: its description, and its deletion after a review of what it holds. */
export function NamespaceChanges({ api, scope, namespace }: { api: QraftApi; scope: Scope; namespace: NamespaceHoldings }) {
  const writable = useOperatorWrite();
  const [panel, setPanel] = useState<ScopePanel>({ kind: 'none' });
  const close = () => setPanel({ kind: 'none' });
  const label = `${scope.tenant}/${scope.namespace}`;
  if (!writable) return <Typography.Text>{NO_WRITE}</Typography.Text>;
  if (panel.kind === 'edit' || panel.kind === 'review') {
    return <EditDescription title={`Update namespace ${label}`} current={namespace.Description} panel={panel}
      setPanel={setPanel} submit={(description) => api.updateNamespace(scope.tenant, scope.namespace, description)}
      close={close} />;
  }
  if (panel.kind === 'delete') {
    return (
      <Mutation<boolean>
        title={`Delete namespace ${label}`}
        identity={[['Namespace', label]]}
        review={<>
          <p>{`It holds ${holdsText(namespace.Services, namespace.Instances, namespace.Keys)}: all are removed.`}</p>
          <RemovedServices api={api} scope={scope} />
          <p>{heldText(namespace.HeldKeys, namespace.Sessions)}</p>
        </>}
        action="Delete"
        idempotent
        submit={() => api.deleteNamespace(scope.tenant, scope.namespace)}
        detail={deletedAlready}
        onClose={close}
      />
    );
  }
  return (
    <div>
      <button type="button" style={button} onClick={() => setPanel({ kind: 'edit', description: namespace.Description })}>
        Edit the description
      </button>
      {namespace.Name === DEFAULT ? <Typography.Text>A default namespace is deleted only with its tenant.</Typography.Text>
        : <button type="button" style={button} onClick={() => setPanel({ kind: 'delete' })}>Delete this namespace</button>}
    </div>
  );
}

/** The services a namespace's deletion removes, by name with their instances. */
function RemovedServices({ api, scope }: { api: QraftApi; scope: Scope }): ReactNode {
  const [state] = useRead(() => api.servicesSummary(scope), [api, scope.tenant, scope.namespace]);
  if (state.status === 'loading') return <p>Reading its services…</p>;
  if (state.status === 'failed') return <p>Its services could not be read.</p>;
  const services = state.value.data;
  if (services.length === 0) return null;
  return <p>{`Services removed: ${services.map((service) => `${service.name} (${plural(service.instanceCount, 'instance')})`)
    .join(', ')}.`}</p>;
}
