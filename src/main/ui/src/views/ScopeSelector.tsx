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

import { Select, Space, Typography } from 'antd';
import type { QraftApi } from '../api/client';
import { useRouter } from '../routing';
import { useRead } from '../useRead';

/**
 * The top bar's tenant and namespace selector (UI/UX design section 4.1; UI-2 task list, decision 1): the replicated
 * tenants, and the selected tenant's namespaces. Choosing one re-reads the current view in it. When the scopes cannot
 * be listed, the selected scope is still read, and the selector says why it lists nothing.
 */
export function ScopeSelector({ api }: { api: QraftApi }) {
  const { scope, selectScope } = useRouter();
  const [tenants] = useRead(() => api.tenants(), [api]);
  const [namespaces] = useRead(() => api.namespaces(scope.tenant), [api, scope.tenant]);
  const tenantNames = tenants.status === 'ready' ? tenants.value.data.map((tenant) => tenant.Name) : [];
  const namespaceNames = namespaces.status === 'ready' ? namespaces.value.data.map((namespace) => namespace.Name) : [];
  const failure = tenants.status === 'failed' ? tenants.error : namespaces.status === 'failed' ? namespaces.error : null;

  return (
    <Space wrap>
      <Select
        aria-label="Tenant"
        virtual={false}
        value={scope.tenant}
        style={{ minWidth: 140 }}
        options={tenantNames.map((name) => ({ label: name, value: name }))}
        onChange={(tenant: string) => selectScope({ tenant, namespace: 'default' })}
      />
      <Select
        aria-label="Namespace"
        virtual={false}
        value={scope.namespace}
        style={{ minWidth: 140 }}
        options={namespaceNames.map((name) => ({ label: name, value: name }))}
        onChange={(namespace: string) => selectScope({ tenant: scope.tenant, namespace })}
      />
      {failure !== null && (
        <Typography.Text style={{ color: '#fff' }}>
          {`The scopes could not be listed: ${failure instanceof Error ? failure.message : String(failure)}`}
        </Typography.Text>
      )}
    </Space>
  );
}
