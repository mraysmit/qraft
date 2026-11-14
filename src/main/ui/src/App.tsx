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
import type { QraftApi } from './api/client';
import type { Bootstrap } from './bootstrap';
import { RouteLink, RouterProvider, useRouter } from './routing';
import { NodesView } from './views/NodesView';
import { ChecksView } from './views/ChecksView';
import { ClusterView } from './views/ClusterView';
import { MembersView } from './views/MembersView';
import { ScopeSelector } from './views/ScopeSelector';
import { ServiceDetailView } from './views/ServiceDetailView';
import { ServicesView } from './views/ServicesView';
import { StatusStrip } from './views/StatusStrip';
import { StorageView } from './views/StorageView';

const NAV_LINK = { color: '#fff' };

/** The application shell: top bar with navigation, the security banner, the status strip, and the selected view. */
export function App({ bootstrap, api }: { bootstrap: Bootstrap; api: QraftApi }) {
  return (
    <RouterProvider uiPath={bootstrap.uiPath}>
      <Layout style={{ minHeight: '100vh' }}>
        <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
          <Typography.Title level={1} style={{ color: '#fff', margin: 0, fontSize: 20 }}>
            Qraft
          </Typography.Title>
          <nav aria-label="Primary">
            <Space size="large">
              <RouteLink to={{ view: 'services' }} style={NAV_LINK}>
                Services
              </RouteLink>
              <RouteLink to={{ view: 'nodes' }} style={NAV_LINK}>
                Nodes and Clients
              </RouteLink>
              <RouteLink to={{ view: 'checks' }} style={NAV_LINK}>
                Health Checks
              </RouteLink>
              <RouteLink to={{ view: 'cluster' }} style={NAV_LINK}>
                Cluster Overview
              </RouteLink>
              <RouteLink to={{ view: 'members' }} style={NAV_LINK}>
                Raft Members
              </RouteLink>
              <RouteLink to={{ view: 'storage' }} style={NAV_LINK}>
                Storage & Snapshots
              </RouteLink>
            </Space>
          </nav>
          <div style={{ marginLeft: 'auto' }}>
            <ScopeSelector api={api} />
          </div>
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
        <StatusStrip api={api} bootstrap={bootstrap} />
        <Layout.Content style={{ padding: 24 }}>
          <CurrentView api={api} />
        </Layout.Content>
      </Layout>
    </RouterProvider>
  );
}

function CurrentView({ api }: { api: QraftApi }) {
  const { route, scope } = useRouter();
  switch (route.view) {
    case 'services':
      return <ServicesView api={api} scope={scope} />;
    case 'service':
      return <ServiceDetailView key={route.name} api={api} name={route.name} scope={scope} />;
    case 'nodes':
      return <NodesView api={api} scope={scope} />;
    case 'checks':
      return <ChecksView api={api} scope={scope} />;
    case 'cluster':
      return <ClusterView api={api} scope={scope} />;
    case 'members':
      return <MembersView api={api} />;
    case 'storage':
      return <StorageView api={api} />;
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
