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

import { Alert, Layout, Space, Typography } from 'antd';
import { type Access, AccessProvider, ManagementBanner, TokenMenu, unrestricted, useAccess } from './access';
import type { QraftApi } from './api/client';
import type { Bootstrap } from './bootstrap';
import { LiveProvider, PauseControl, type Scheduler } from './live';
import { type Route, RouteLink, RouterProvider, useRouter } from './routing';
import { AuditView, auditKey } from './views/AuditView';
import { NodesView } from './views/NodesView';
import { ChecksView } from './views/ChecksView';
import { ClusterView } from './views/ClusterView';
import { ConfigurationView } from './views/ConfigurationView';
import { ConsistencySelector } from './views/ConsistencySelector';
import { EventsView } from './views/EventsView';
import { KvView } from './views/KvView';
import { LocksView } from './views/LocksView';
import { MembersView } from './views/MembersView';
import { ScopeSelector } from './views/ScopeSelector';
import { ExplainView, PoliciesView, PolicyView } from './views/PoliciesView';
import { SecurityView } from './views/SecurityView';
import { AccessNav, TokensView, TokenView } from './views/TokensView';
import { ServiceDetailView } from './views/ServiceDetailView';
import { ServicesView } from './views/ServicesView';
import { SessionsView, SessionView } from './views/SessionsView';
import { StatusStrip } from './views/StatusStrip';
import { StorageView } from './views/StorageView';
import { NamespacesView, NamespaceView, TenantsView, TenantView } from './views/TenancyView';
import { WatchesView } from './views/WatchesView';

const NAV_LINK = { color: '#fff' };

/**
 * The application shell: top bar with navigation, the scope and consistency selectors, the live-updates control and
 * the token menu, the security banners, the status strip, and the selected view. Navigation offers the views the
 * token may read (UI-5 task list, decision 3). A test gives the scheduler live updates wait on; given no access, the
 * shell offers every view.
 */
export function App({ bootstrap, api, scheduler, access }: {
  bootstrap: Bootstrap;
  api: QraftApi;
  scheduler?: Scheduler | undefined;
  access?: Access | undefined;
}) {
  const granted = access ?? unrestricted(bootstrap.authMode);
  const { operatorRead, aclRead } = granted.may;
  return (
    <AccessProvider value={granted}>
    <RouterProvider uiPath={bootstrap.uiPath}>
      <LiveProvider scheduler={scheduler}>
      <Layout style={{ minHeight: '100vh' }}>
        <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
          <Typography.Title level={1} style={{ color: '#fff', margin: 0, fontSize: 20 }}>
            Qraft
          </Typography.Title>
          <nav aria-label="Primary">
            <Space size="large" wrap>
              {navigation(operatorRead, aclRead).map(([group, links]) => (
                <Space key={group} size="middle">
                  <span data-nav-group={group} style={NAV_GROUP}>{group}</span>
                  {links.map(([to, title]) => (
                    <RouteLink key={title} to={to} style={NAV_LINK}>
                      {title}
                    </RouteLink>
                  ))}
                </Space>
              ))}
            </Space>
          </nav>
          <Space style={{ marginLeft: 'auto' }} wrap>
            <ScopeSelector api={api} />
            <ConsistencySelector />
            <PauseControl color="#fff" />
            <TokenMenu color="#fff" />
          </Space>
        </Layout.Header>
        {bootstrap.authMode === 'none' && bootstrap.securityProfile === 'development' && (
          // Persistent and not dismissible (UI/UX design section 3.7).
          <Alert
            type="warning"
            banner
            aria-label="No access control"
            message="This development server has no access control: anyone who can reach this server can read and change it."
          />
        )}
        <ManagementBanner />
        <StatusStrip api={api} bootstrap={bootstrap} />
        <Layout.Content style={{ padding: 24 }}>
          <CurrentView api={api} bootstrap={bootstrap} />
        </Layout.Content>
      </Layout>
      </LiveProvider>
    </RouterProvider>
    </AccessProvider>
  );
}

/** The views that need `operator:read`, in navigation order, with their titles. */
const OPERATOR_VIEWS = [
  ['cluster', 'Cluster Overview'],
  ['members', 'Raft Members'],
  ['storage', 'Storage & Snapshots'],
  ['events', 'Events'],
  ['configuration', 'Configuration'],
] as const;

/** The Govern views, which need `operator:read` as the tenancy and audit reads do, with their titles. */
const GOVERN_VIEWS: readonly [Route, string][] = [
  [{ view: 'tenants' }, 'Tenants'],
  [{ view: 'namespaces' }, 'Namespaces'],
  [{ view: 'audit', filter: {} }, 'Audit'],
];

/** Each Govern page's title, a detail page's the same as its list's. */
const GOVERN_TITLES: Readonly<Record<string, string>> = { tenants: 'Tenants', tenant: 'Tenants',
  namespaces: 'Namespaces', namespace: 'Namespaces', audit: 'Audit' };

const NAV_GROUP = { color: 'rgba(255, 255, 255, 0.65)', fontSize: 12, textTransform: 'uppercase' as const };

/**
 * The navigation in the groups of UI/UX design section 4.3 (UI-6 Tenants, Namespaces and Audit task list, decision 8),
 * the links in one order; a group with no link the token may use is left out.
 */
function navigation(operatorRead: boolean, aclRead: boolean): [string, [Route, string][]][] {
  const groups: [string, [Route, string][]][] = [
    ['Discover', [[{ view: 'services' }, 'Services'], [{ view: 'nodes' }, 'Nodes and Clients'],
      [{ view: 'checks' }, 'Health Checks']]],
    ['Coordinate', [[{ view: 'kv', path: '' }, 'Key/Value'], [{ view: 'sessions' }, 'Sessions'],
      [{ view: 'locks' }, 'Locks'], [{ view: 'watches' }, 'Watches']]],
    ['Operate', operatorRead ? OPERATOR_VIEWS.map(([view, title]) => [{ view }, title] as [Route, string]) : []],
    ['Govern', operatorRead ? [...GOVERN_VIEWS] : []],
    ['Access', aclRead ? [[{ view: 'access' }, 'Access']] : []],
  ];
  return groups.filter(([, links]) => links.length > 0);
}

/** The Access section's views, which need `acl:read`. */
const ACCESS_VIEWS = new Set(['access', 'tokens', 'token', 'policies', 'policy', 'explain']);

/** The token pages of a server without access control, which has neither tokens nor policies. */
function NoAccessControl() {
  return (
    <Space direction="vertical">
      <Typography.Title level={2}>Access control</Typography.Title>
      <AccessNav />
      <Typography.Text>This server has no access control, so it has no tokens or policies to show.</Typography.Text>
    </Space>
  );
}

/** What a view whose permission the token lacks shows in its place, naming the permission (principle 3.5). */
function NotPermitted({ title, permission }: { title: string; permission: string }) {
  return (
    <Space direction="vertical">
      <Typography.Title level={2}>{title}</Typography.Title>
      <Typography.Text>{`Your token may not read ${title}: it needs ${permission}.`}</Typography.Text>
    </Space>
  );
}

function CurrentView({ api, bootstrap }: { api: QraftApi; bootstrap: Bootstrap }) {
  const { route, scope } = useRouter();
  const { may, mode } = useAccess();
  const operator = OPERATOR_VIEWS.find(([view]) => view === route.view);
  if (operator !== undefined && !may.operatorRead) return <NotPermitted title={operator[1]} permission="operator:read" />;
  const govern = GOVERN_TITLES[route.view];
  if (govern !== undefined && Object.hasOwn(GOVERN_TITLES, route.view) && !may.operatorRead) {
    return <NotPermitted title={govern} permission="operator:read" />;
  }
  if (ACCESS_VIEWS.has(route.view) && !may.aclRead) return <NotPermitted title="Access control" permission="acl:read" />;
  if (ACCESS_VIEWS.has(route.view) && route.view !== 'access' && mode === 'none') return <NoAccessControl />;
  switch (route.view) {
    case 'services':
      return <ServicesView api={api} scope={scope} />;
    case 'service':
      return <ServiceDetailView key={route.name} api={api} name={route.name} scope={scope} />;
    case 'nodes':
      return <NodesView api={api} scope={scope} />;
    case 'checks':
      return <ChecksView api={api} scope={scope} />;
    case 'kv':
      return <KvView key={route.path} api={api} scope={scope} path={route.path} />;
    case 'sessions':
      return <SessionsView api={api} />;
    case 'sessionInfo':
      return <SessionView key={route.id} api={api} id={route.id} />;
    case 'locks':
      return <LocksView api={api} />;
    case 'audit':
      return <AuditView key={auditKey(route.filter)} api={api} filter={route.filter} />;
    case 'tenants':
      return <TenantsView api={api} />;
    case 'tenant':
      return <TenantView key={route.name} api={api} name={route.name} />;
    case 'namespaces':
      return <NamespacesView api={api} />;
    case 'namespace':
      return <NamespaceView key={`${route.tenant}/${route.name}`} api={api} tenant={route.tenant} name={route.name} />;
    case 'watches':
      return <WatchesView key={`${route.target ?? ''}:${route.name ?? ''}`} api={api} target={route.target} name={route.name} />;
    case 'cluster':
      return <ClusterView api={api} scope={scope} />;
    case 'members':
      return <MembersView api={api} />;
    case 'storage':
      return <StorageView api={api} />;
    case 'events':
      return (
        <EventsView key={`${route.category ?? ''}:${route.correlation ?? ''}`} api={api}
          initial={{ category: route.category, correlation: route.correlation }} />
      );
    case 'configuration':
      return <ConfigurationView api={api} />;
    case 'access':
      return <SecurityView api={api} bootstrap={bootstrap} />;
    case 'tokens':
      return <TokensView api={api} />;
    case 'token':
      return <TokenView key={route.accessorId} api={api} accessorId={route.accessorId} />;
    case 'policies':
      return <PoliciesView api={api} />;
    case 'policy':
      return <PolicyView key={route.id} api={api} id={route.id} />;
    case 'explain':
      return <ExplainView api={api} />;
    case 'unknown':
      return (
        <Space direction="vertical">
          <Typography.Title level={2}>No such view</Typography.Title>
          <Typography.Text>This address does not name a view of the interface.</Typography.Text>
          <RouteLink to={{ view: 'services' }}>Services</RouteLink>
        </Space>
      );
  }
}
