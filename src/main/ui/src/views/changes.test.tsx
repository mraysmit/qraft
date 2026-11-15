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

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Root } from '../Root';
import type { AuthorizationCheck, QraftApi, Scope, ServiceBody } from '../api/client';
import { BOOTSTRAP, fakeApi, memorySession, node, read } from './testing';

// UI-5 task list, Phase 3 and decision 7: a service's instances registered, updated, enabled, disabled and
// deregistered on nodes the catalog has, in the selected scope, each change reviewed by what a client does to it.

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/services/web'));

interface Sent {
  kind: 'register' | 'deregister';
  node: string;
  scope: Scope;
  body: ServiceBody | string;
}

/** An API recording each catalog change and each authorize check, granting `service:write` unless told not to. */
function changing(writable = true, overrides: Partial<QraftApi> = {}) {
  const sent: Sent[] = [];
  const checks: AuthorizationCheck[][] = [];
  const api = fakeApi({
    nodes: () => read([node(), node({ name: 'bare-node', id: null, metadata: {} })]),
    authorize: (asked) => {
      checks.push(asked);
      return read(asked.map((check) => ({ ...check, allow: check.resource !== 'service' || writable })), null);
    },
    registerService: (nodeName, scope, body) => {
      sent.push({ kind: 'register', node: nodeName, scope, body });
      return read({ node: nodeName, service: { serviceId: body.serviceId, serviceName: body.serviceName, nodeId: nodeName,
        tenantId: scope.tenant, namespace: scope.namespace, registered: true } }, 61);
    },
    deregisterService: (nodeName, scope, serviceId) => {
      sent.push({ kind: 'deregister', node: nodeName, scope, body: serviceId });
      return read({ node: nodeName, serviceId, deregistered: true }, 62);
    },
    ...overrides,
  });
  return { api, sent, checks };
}

async function openChanges(api: QraftApi, bootstrap = BOOTSTRAP) {
  render(<Root bootstrap={bootstrap} api={api} session={memorySession('held')} />);
  fireEvent.click(await screen.findByText('Changes', { selector: '.ant-tabs-tab-btn' }));
}

const button = (text: string) => screen.getByText(text, { selector: 'button, button span' });
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('service changes', () => {
  it("are offered with service:write on the service in the selected scope, and not without it", async () => {
    at('/ui/services/web?tenant=acme&namespace=prod');
    const { api, checks } = changing();
    await openChanges(api);

    expect(await screen.findByText('Register an instance', { selector: 'button, button span' })).toBeInTheDocument();
    expect(checks.flat()).toContainEqual({ resource: 'service', segment: 'web', access: 'write', tenant: 'acme', namespace: 'prod' });
  });

  it('are not offered without service:write', async () => {
    const { api } = changing(false);
    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    await screen.findByText('Instances', { selector: '.ant-tabs-tab-btn' });
    await waitFor(() => expect(screen.queryByText('Changes', { selector: '.ant-tabs-tab-btn' })).toBeNull());
  });

  it("registers an instance on a node the catalog has, saying what a client running there does to it", async () => {
    const { api, sent } = changing();
    await openChanges(api);
    fireEvent.click(await screen.findByText('Register an instance', { selector: 'button, button span' }));

    type('Node', 'client-a');
    type('Instance ID', 'web-2');
    type('Address', '10.0.0.6');
    type('Port', '8082');
    type('Tags', 'http, v2');
    type('Metadata', 'owner=platform, tier=front');
    fireEvent.click(button('Review'));

    const review = await screen.findByLabelText('Register an instance');
    expect(review.textContent).toContain('Instance IDweb-2');
    expect(review.textContent).toContain('Nodeclient-a');
    expect(review.textContent).toContain('Scopedefault/default');
    expect(review.textContent).toContain('A client runs on node client-a: if this service is in its own tenant and namespace, '
      + 'the client removes it at its next reconciliation.');
    fireEvent.click(button('Register'));

    expect(await screen.findByText(/Committed and applied at index 61/)).toBeInTheDocument();
    expect(sent).toEqual([{ kind: 'register', node: 'client-a', scope: { tenant: 'default', namespace: 'default' }, body: {
      serviceId: 'web-2', serviceName: 'web', address: '10.0.0.6', port: 8082, tags: ['http', 'v2'],
      metadata: { owner: 'platform', tier: 'front' }, enabled: true } }]);
  });

  it('offers only the nodes the catalog has, and creates none', async () => {
    const { api } = changing();
    await openChanges(api);
    fireEvent.click(await screen.findByText('Register an instance', { selector: 'button, button span' }));

    const options = Array.from((screen.getByLabelText('Node') as HTMLSelectElement).options).map((option) => option.value);
    expect(options).toEqual(['', 'bare-node', 'client-a']);
    fireEvent.click(button('Review'));
    expect(await screen.findByText('Choose the node the instance runs on.')).toBeInTheDocument();
  });

  it('updates an instance by registering it again, and says a client may register its own again', async () => {
    const { api, sent } = changing();
    await openChanges(api);
    fireEvent.click(await screen.findByText('Update web-1', { selector: 'button, button span' }));

    expect((screen.getByLabelText('Instance ID') as HTMLInputElement).disabled).toBe(true);
    type('Port', '8090');
    fireEvent.click(button('Review'));
    const review = await screen.findByLabelText('Update web-1');
    expect(review.textContent).toContain('Port: 8081 → 8090');
    expect(review.textContent).toContain('If a client registered this instance, it registers it again at its next '
      + 'reconciliation, and this change does not last.');
    fireEvent.click(button('Save'));

    expect(await screen.findByText(/Committed and applied at index 61/)).toBeInTheDocument();
    expect(sent[0]?.node).toBe('client-a');
    expect(sent[0]?.body).toEqual({ serviceId: 'web-1', serviceName: 'web', address: '10.0.0.5', port: 8090, tags: ['http'],
      metadata: { owner: 'platform' }, enabled: true });
  });

  it('disables and deregisters an instance', async () => {
    const { api, sent } = changing();
    await openChanges(api);

    fireEvent.click(await screen.findByText('Disable web-1', { selector: 'button, button span' }));
    expect((await screen.findByLabelText('Disable web-1')).textContent).toContain('Enabled: yes → no');
    fireEvent.click(button('Disable'));
    await screen.findByText(/Committed and applied at index 61/);
    fireEvent.click(button('Close'));

    fireEvent.click(await screen.findByText('Deregister web-1', { selector: 'button, button span' }));
    expect((await screen.findByLabelText('Deregister web-1')).textContent).toContain('it registers it again at its next reconciliation');
    fireEvent.click(button('Deregister'));
    await screen.findByText(/Committed and applied at index 62/);

    expect(sent.map((change) => [change.kind, change.node, typeof change.body === 'string' ? change.body : change.body.enabled]))
      .toEqual([['register', 'client-a', false], ['deregister', 'client-a', 'web-1']]);
  });

  it('are offered without access control, under its warning, asking nothing', async () => {
    const { api, checks } = changing();
    await openChanges(api, { ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' });

    fireEvent.click(await screen.findByText('Deregister web-1', { selector: 'button, button span' }));
    expect((await screen.findByLabelText('Deregister web-1')).textContent).toContain('No access control');
    expect(checks).toEqual([]);
  });
});
