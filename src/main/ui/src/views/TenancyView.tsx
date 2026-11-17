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
import type { ReactNode } from 'react';
import type { QraftApi, Read, Scope } from '../api/client';
import type { HeldKey, NamespaceHoldings, Scopes, ServiceSummary, TenantHoldings } from '../api/schemas';
import { totalsOf } from '../api/tenancy';
import { type FeedQuery, useEventFeed } from '../eventFeed';
import { useLiveRead } from '../live';
import { RouteLink, useRouter } from '../routing';
import type { ReadState } from '../useRead';
import { Failure, PlainTable, ReadMeta } from './common';
import { EphemeralNote, EventsTable, FeedState } from './EventsTable';
import { CreateTenant, NamespaceChanges, TenantChanges } from './TenancyChanges';

// The Tenants and Namespaces pages (UI/UX design section 10.1; UI-6 Tenants, Namespaces and Audit task list, Phase 1):
// every tenant and namespace with what it holds (decision 2), a tenant's namespaces, and a namespace's services and
// held keys, each with its recent events. They follow the server live in the consistency chosen.

/** The scopes read, followed live, with its metadata and failure shown above the page's own content. */
function useScopes(api: QraftApi): [ReadState<Read<Scopes>>, Read<Scopes> | undefined, () => void] {
  const { consistency } = useRouter();
  const [state, again] = useLiveRead((wait) => api.scopes({ consistency, ...wait }), [api, consistency],
    (read) => read.index);
  return [state, state.status === 'ready' ? state.value : state.previous, again];
}

function ScopesState({ state, shown, again }: {
  state: ReadState<Read<Scopes>>;
  shown: Read<Scopes> | undefined;
  again: () => void;
}) {
  return (
    <>
      {state.status === 'failed' && <Failure title="The tenants could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <ReadMeta read={shown} stale={state.status !== 'ready'} resynchronized={state.status === 'ready' && state.resynchronized} />
      )}
      {shown === undefined && state.status === 'loading' && <Typography.Text>Reading the tenants…</Typography.Text>}
    </>
  );
}

const count = (value: number) => String(value);

export function TenantsView({ api }: { api: QraftApi }) {
  const [state, shown, again] = useScopes(api);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Tenants</Typography.Title>
      <CreateTenant api={api} />
      <ScopesState state={state} shown={shown} again={again} />
      {shown !== undefined && (
        <PlainTable<TenantHoldings>
          label="Tenants"
          rows={shown.data.Tenants}
          rowKey={(tenant) => tenant.Name}
          empty="No tenant exists."
          columns={[
            { title: 'Tenant', render: (tenant) => <RouteLink to={{ view: 'tenant', name: tenant.Name }}>{tenant.Name}</RouteLink> },
            { title: 'Description', render: (tenant) => tenant.Description },
            { title: 'Namespaces', render: (tenant) => count(totalsOf(tenant).namespaces) },
            { title: 'Services', render: (tenant) => count(totalsOf(tenant).services) },
            { title: 'Instances', render: (tenant) => count(totalsOf(tenant).instances) },
            { title: 'Keys', render: (tenant) => count(totalsOf(tenant).keys) },
            { title: 'Held keys', render: (tenant) => count(totalsOf(tenant).heldKeys) },
          ]}
        />
      )}
    </Space>
  );
}

function Fields({ fields }: { fields: [string, ReactNode][] }) {
  return (
    <dl style={{ display: 'grid', gridTemplateColumns: 'max-content auto', columnGap: 16, rowGap: 4 }}>
      {fields.map(([label, value]) => (
        <div key={label} style={{ display: 'contents' }}>
          <dt style={{ fontWeight: 600 }}>{label}</dt>
          <dd style={{ margin: 0 }} data-field={label}>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A scope's recent events, as the answering server's journal holds them. */
function ScopeEvents({ api, query, what }: { api: QraftApi; query: FeedQuery; what: string }) {
  const [feed] = useEventFeed(api, query, [query.tenant, query.namespace]);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <EphemeralNote feed={feed} />
      <FeedState feed={feed} />
      <EventsTable events={feed.events} paged
        empty={feed.phase === 'reading' ? 'Reading the journal…' : `The journal holds no event of ${what}.`} />
    </Space>
  );
}

export function TenantView({ api, name }: { api: QraftApi; name: string }) {
  const [state, shown, again] = useScopes(api);
  const tenant = shown?.data.Tenants.find((candidate) => candidate.Name === name);
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{`Tenant ${name}`}</Typography.Title>
      <RouteLink to={{ view: 'tenants' }}>All tenants</RouteLink>
      <ScopesState state={state} shown={shown} again={again} />
      {shown !== undefined && tenant === undefined && <Typography.Text>{`No tenant ${name} exists.`}</Typography.Text>}
      {tenant !== undefined && (
        <>
          <Fields fields={[
            ['Description', tenant.Description === '' ? 'None' : tenant.Description],
            ['Created at index', count(tenant.CreateIndex)],
            ['Modified at index', count(tenant.ModifyIndex)],
          ]} />
          <TenantChanges api={api} tenant={tenant} />
          <Typography.Title level={3}>Namespaces</Typography.Title>
          <NamespacesTable tenant={tenant.Name} namespaces={tenant.Namespaces} withTenant={false} />
        </>
      )}
      <Typography.Title level={3}>Recent events</Typography.Title>
      <ScopeEvents api={api} query={{ tenant: name }} what={`tenant ${name}`} />
    </Space>
  );
}

type NamespaceRow = NamespaceHoldings & { Tenant: string };

function NamespacesTable({ tenant, namespaces, withTenant }: {
  tenant?: string;
  namespaces: readonly (NamespaceHoldings | NamespaceRow)[];
  withTenant: boolean;
}) {
  const tenantOf = (namespace: NamespaceHoldings | NamespaceRow) => ('Tenant' in namespace ? namespace.Tenant : tenant ?? '');
  return (
    <PlainTable<NamespaceHoldings | NamespaceRow>
      label="Namespaces"
      rows={namespaces}
      rowKey={(namespace) => `${tenantOf(namespace)}/${namespace.Name}`}
      empty="No namespace exists."
      columns={[
        { title: 'Namespace', render: (namespace) => (
          <RouteLink to={{ view: 'namespace', tenant: tenantOf(namespace), name: namespace.Name }}>{namespace.Name}</RouteLink>
        ) },
        ...(withTenant ? [{ title: 'Tenant', render: (namespace: NamespaceHoldings | NamespaceRow) => tenantOf(namespace) }] : []),
        { title: 'Description', render: (namespace) => namespace.Description },
        { title: 'Services', render: (namespace) => count(namespace.Services) },
        { title: 'Instances', render: (namespace) => count(namespace.Instances) },
        { title: 'Keys', render: (namespace) => count(namespace.Keys) },
        { title: 'Held keys', render: (namespace) => count(namespace.HeldKeys) },
      ]}
    />
  );
}

export function NamespacesView({ api }: { api: QraftApi }) {
  const [state, shown, again] = useScopes(api);
  const rows: NamespaceRow[] = shown?.data.Tenants.flatMap((tenant) =>
    tenant.Namespaces.map((namespace) => ({ ...namespace, Tenant: tenant.Name }))) ?? [];
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Namespaces</Typography.Title>
      <ScopesState state={state} shown={shown} again={again} />
      {shown !== undefined && <NamespacesTable namespaces={rows} withTenant />}
    </Space>
  );
}

export function NamespaceView({ api, tenant, name }: { api: QraftApi; tenant: string; name: string }) {
  const [state, shown, again] = useScopes(api);
  const namespace = shown?.data.Tenants.find((candidate) => candidate.Name === tenant)?.Namespaces
    .find((candidate) => candidate.Name === name);
  const scope: Scope = { tenant, namespace: name };
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>{`Namespace ${tenant}/${name}`}</Typography.Title>
      <RouteLink to={{ view: 'tenant', name: tenant }}>{`Tenant ${tenant}`}</RouteLink>
      <ScopesState state={state} shown={shown} again={again} />
      {shown !== undefined && namespace === undefined && (
        <Typography.Text>{`No namespace ${name} exists in tenant ${tenant}.`}</Typography.Text>
      )}
      {namespace !== undefined && (
        <>
          <Fields fields={[
            ['Description', namespace.Description === '' ? 'None' : namespace.Description],
            ['Created at index', count(namespace.CreateIndex)],
            ['Modified at index', count(namespace.ModifyIndex)],
            ['Services', count(namespace.Services)],
            ['Instances', count(namespace.Instances)],
            ['Keys', count(namespace.Keys)],
            ['Held keys', count(namespace.HeldKeys)],
          ]} />
          <NamespaceChanges api={api} scope={scope} namespace={namespace} />
          <Typography.Title level={3}>Services</Typography.Title>
          <ScopeServices api={api} scope={scope} />
          <Typography.Title level={3}>Held keys</Typography.Title>
          <ScopeHeldKeys api={api} scope={scope} />
        </>
      )}
      <Typography.Title level={3}>Recent events</Typography.Title>
      <ScopeEvents api={api} query={{ tenant, namespace: name }} what={`namespace ${tenant}/${name}`} />
    </Space>
  );
}

function ScopeServices({ api, scope }: { api: QraftApi; scope: Scope }) {
  const { consistency } = useRouter();
  const [state, again] = useLiveRead((wait) => api.servicesSummary(scope, { consistency, ...wait }),
    [api, scope.tenant, scope.namespace, consistency], (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  if (shown === undefined) {
    return state.status === 'failed' ? <Failure title="Its services could not be read" error={state.error} onRetry={again} />
      : <Typography.Text>Reading its services…</Typography.Text>;
  }
  return (
    <PlainTable<ServiceSummary>
      label="Services"
      rows={shown.data}
      rowKey={(service) => service.name}
      empty="It holds no service."
      columns={[
        { title: 'Service', render: (service) => (
          <RouteLink to={{ view: 'service', name: service.name }} scope={scope}>{service.name}</RouteLink>
        ) },
        { title: 'Instances', render: (service) => count(service.instanceCount) },
      ]}
    />
  );
}

function ScopeHeldKeys({ api, scope }: { api: QraftApi; scope: Scope }) {
  const { consistency } = useRouter();
  const [state, again] = useLiveRead((wait) => api.locks({}, { consistency, ...wait }), [api, consistency],
    (read) => read.index);
  const shown = state.status === 'ready' ? state.value : state.previous;
  if (shown === undefined) {
    return state.status === 'failed' ? <Failure title="Its held keys could not be read" error={state.error} onRetry={again} />
      : <Typography.Text>Reading its held keys…</Typography.Text>;
  }
  const held = shown.data.Held.filter((key) => key.Tenant === scope.tenant && key.Namespace === scope.namespace);
  return (
    <PlainTable<HeldKey>
      label="Held keys"
      rows={held}
      rowKey={(key) => key.Key}
      empty={shown.filtered ? 'It holds no key your token may read that a session holds.' : 'No session holds a key in it.'}
      columns={[
        { title: 'Key', render: (key) => <RouteLink to={{ view: 'kv', path: key.Key }} scope={scope}>{key.Key}</RouteLink> },
        { title: 'Session', render: (key) => <RouteLink to={{ view: 'sessionInfo', id: key.Session }}>{key.Session}</RouteLink> },
      ]}
    />
  );
}
