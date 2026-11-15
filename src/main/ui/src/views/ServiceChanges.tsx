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
import { useAccess } from '../access';
import type { QraftApi, Scope, ServiceBody } from '../api/client';
import type { CatalogNode, HealthEntry, ServiceInstance } from '../api/schemas';
import { useRead } from '../useRead';
import { scopeText } from './common';
import { Mutation } from './Mutation';

/** The node metadata a client sets: a node that carries it has a client running on it. */
const CLIENT_KEY = 'qraft.version';

const REGISTERS_AGAIN = 'If a client registered this instance, it registers it again at its next reconciliation, and '
  + 'this change does not last.';

/**
 * Whether the token may change `name` in `scope`, `service:write` there, asked once when the page opens; without
 * access control, always (UI-5 task list, decision 3).
 */
export function useServiceWrite(api: QraftApi, name: string, scope: Scope): boolean {
  const access = useAccess();
  const [state] = useRead(async () => {
    if (access.mode === 'none') return true;
    const answers = await api.authorize([{ resource: 'service', segment: name, access: 'write', tenant: scope.tenant,
      namespace: scope.namespace }]);
    return answers.data[0]?.allow === true;
  }, [api, name, scope.tenant, scope.namespace, access.mode]);
  return state.status === 'ready' && state.value;
}

type Panel =
  | { kind: 'none' }
  | { kind: 'register' }
  | { kind: 'update' | 'toggle' | 'deregister'; instance: ServiceInstance };

const bodyOf = (instance: ServiceInstance): ServiceBody => ({
  serviceId: instance.serviceId,
  serviceName: instance.serviceName,
  address: instance.address,
  port: instance.port,
  tags: instance.tags,
  metadata: instance.metadata,
  enabled: instance.enabled,
});

/**
 * A service's changes (UI-5 task list, decision 7): instances registered, updated, enabled, disabled and
 * deregistered on nodes the catalog has, in the selected scope; no node is created. Each review says what a client on
 * the node does to the change: the catalog does not record which client registered an instance.
 */
export function ServiceChanges({ api, name, scope, entries }: {
  api: QraftApi;
  name: string;
  scope: Scope;
  entries: HealthEntry[];
}) {
  const [nodes] = useRead(() => api.nodes(), [api]);
  const [panel, setPanel] = useState<Panel>({ kind: 'none' });
  const known = nodes.status === 'ready' ? nodes.value.data : [];
  const hasClient = (node: string | null) =>
    known.some((item) => item.name === node && item.metadata[CLIENT_KEY] !== undefined);
  const close = () => setPanel({ kind: 'none' });
  const scoped: [string, string][] = [['Scope', `${scope.tenant}/${scope.namespace}`]];

  if (panel.kind === 'register') {
    return <Register api={api} name={name} scope={scope} nodes={known} hasClient={hasClient} onClose={close} />;
  }
  if (panel.kind !== 'none') {
    const { instance } = panel;
    const node = instance.nodeId ?? '';
    const identity: [string, string][] = [['Service', name], ['Instance ID', instance.serviceId], ['Node', node], ...scoped];
    const note = hasClient(node) ? <p>{REGISTERS_AGAIN}</p> : null;
    if (panel.kind === 'update') {
      return <Update api={api} instance={instance} scope={scope} identity={identity} note={note} nodes={known} onClose={close} />;
    }
    if (panel.kind === 'toggle') {
      const enabling = !instance.enabled;
      return (
        <Mutation
          title={`${enabling ? 'Enable' : 'Disable'} ${instance.serviceId}`}
          identity={identity}
          review={<><p>{`Enabled: ${enabling ? 'no → yes' : 'yes → no'}`}</p>{note}</>}
          action={enabling ? 'Enable' : 'Disable'}
          idempotent
          submit={() => api.registerService(node, scope, { ...bodyOf(instance), enabled: enabling })}
          onClose={close}
        />
      );
    }
    return (
      <Mutation
        title={`Deregister ${instance.serviceId}`}
        identity={identity}
        review={<><p>The instance and its checks leave the catalog; its node stays.</p>{note}</>}
        action="Deregister"
        idempotent
        submit={() => api.deregisterService(node, scope, instance.serviceId)}
        onClose={close}
      />
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Text>
        {`Changes to ${name} in ${scopeText(scope)}, on nodes the catalog has. A client registers its own services again `
          + 'at each reconciliation, so a change to one of them does not last.'}
      </Typography.Text>
      <div><Button onClick={() => setPanel({ kind: 'register' })}>Register an instance</Button></div>
      <ul aria-label="Instances to change">
        {entries.map(({ service: instance }) => (
          <li key={instance.serviceId} style={{ marginBottom: 8 }}>
            <Space wrap>
              <span>{`${instance.serviceId} on ${instance.nodeId ?? 'no node'} · ${instance.enabled ? 'enabled' : 'disabled'}`}</span>
              <Button size="small" onClick={() => setPanel({ kind: 'update', instance })}>{`Update ${instance.serviceId}`}</Button>
              <Button size="small" onClick={() => setPanel({ kind: 'toggle', instance })}>
                {`${instance.enabled ? 'Disable' : 'Enable'} ${instance.serviceId}`}
              </Button>
              <Button size="small" danger onClick={() => setPanel({ kind: 'deregister', instance })}>
                {`Deregister ${instance.serviceId}`}
              </Button>
            </Space>
          </li>
        ))}
      </ul>
    </Space>
  );
}

interface Fields {
  node: string;
  serviceId: string;
  address: string;
  port: string;
  tags: string;
  metadata: string;
  enabled: boolean;
}

const fieldsOf = (instance: ServiceInstance): Fields => ({
  node: instance.nodeId ?? '',
  serviceId: instance.serviceId,
  address: instance.address,
  port: String(instance.port),
  tags: instance.tags.join(', '),
  metadata: Object.entries(instance.metadata).map(([key, value]) => `${key}=${value}`).join(', '),
  enabled: instance.enabled,
});

const list = (text: string) => text.split(',').map((item) => item.trim()).filter((item) => item !== '');

/** The body the fields give, or what is wrong with them; checked for form only, the server decides the rest. */
function parse(fields: Fields, name: string): ServiceBody | string {
  if (fields.node === '') return 'Choose the node the instance runs on.';
  if (fields.serviceId.trim() === '') return 'Give the instance an ID.';
  const port = Number(fields.port);
  if (!Number.isInteger(port) || port < 1 || port > 65535) return 'The port is a number from 1 to 65535.';
  const metadata: Record<string, string> = {};
  for (const item of list(fields.metadata)) {
    const equals = item.indexOf('=');
    if (equals <= 0) return 'Metadata is key=value, separated by commas.';
    metadata[item.slice(0, equals).trim()] = item.slice(equals + 1).trim();
  }
  return { serviceId: fields.serviceId.trim(), serviceName: name, address: fields.address.trim(), port,
    tags: list(fields.tags), metadata, enabled: fields.enabled };
}

function InstanceForm({ nodes, initial, fixed, onReview, onCancel }: {
  nodes: CatalogNode[];
  initial: Fields;
  /** On an update, the node and the instance ID cannot change. */
  fixed: boolean;
  onReview: (fields: Fields) => void;
  onCancel: () => void;
}) {
  const [fields, setFields] = useState(initial);
  const set = (key: keyof Fields) => (event: { target: { value: string } }) => setFields({ ...fields, [key]: event.target.value });
  const input = (key: 'serviceId' | 'address' | 'port' | 'tags' | 'metadata', label: string, hint?: string) => (
    <label>
      {`${label} `}
      <input aria-label={label} value={fields[key]} disabled={fixed && key === 'serviceId'} onChange={set(key)} />
      {hint !== undefined && <Typography.Text type="secondary">{` ${hint}`}</Typography.Text>}
    </label>
  );
  return (
    <section aria-label="Instance form" style={{ display: 'grid', gap: 8, maxWidth: 640 }}>
      <label>
        {'Node '}
        <select aria-label="Node" value={fields.node} disabled={fixed} onChange={set('node')}>
          <option value="">Choose a node</option>
          {[...nodes].sort((a, b) => a.name.localeCompare(b.name)).map((node) => (
            <option key={node.name} value={node.name}>{node.name}</option>
          ))}
        </select>
      </label>
      {input('serviceId', 'Instance ID')}
      {input('address', 'Address')}
      {input('port', 'Port')}
      {input('tags', 'Tags', 'separated by commas')}
      {input('metadata', 'Metadata', 'key=value, separated by commas')}
      <label>
        <input type="checkbox" aria-label="Enabled" checked={fields.enabled}
          onChange={(event) => setFields({ ...fields, enabled: event.target.checked })} />
        {' Enabled'}
      </label>
      <Space>
        <Button type="primary" onClick={() => onReview(fields)}>Review</Button>
        <Button onClick={onCancel}>Cancel</Button>
      </Space>
    </section>
  );
}

function Register({ api, name, scope, nodes, hasClient, onClose }: {
  api: QraftApi;
  name: string;
  scope: Scope;
  nodes: CatalogNode[];
  hasClient: (node: string) => boolean;
  onClose: () => void;
}) {
  const [fields, setFields] = useState<Fields | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const blank: Fields = { node: '', serviceId: '', address: '', port: '', tags: '', metadata: '', enabled: true };
  const body = fields === null ? null : parse(fields, name);
  if (fields === null || typeof body === 'string' || body === null) {
    return (
      <>
        <InstanceForm nodes={nodes} initial={fields ?? blank} fixed={false} onCancel={onClose} onReview={(given) => {
          const parsed = parse(given, name);
          setProblem(typeof parsed === 'string' ? parsed : null);
          setFields(given);
        }} />
        {problem !== null && <Alert type="error" message={problem} />}
      </>
    );
  }
  return (
    <Mutation
      title="Register an instance"
      identity={[['Service', name], ['Instance ID', body.serviceId], ['Node', fields.node],
        ['Scope', `${scope.tenant}/${scope.namespace}`]]}
      review={(
        <>
          <p>{`Address: ${body.address}:${body.port}`}</p>
          <p>{`Tags: ${body.tags.join(', ') || 'none'}`}</p>
          <p>{`Metadata: ${Object.entries(body.metadata).map(([key, value]) => `${key}=${value}`).join(', ') || 'none'}`}</p>
          <p>{`Enabled: ${body.enabled ? 'yes' : 'no'}`}</p>
          {hasClient(fields.node) ? (
            <p>
              {`A client runs on node ${fields.node}: if this service is in its own tenant and namespace, the client `
                + 'removes it at its next reconciliation. The catalog does not record which client registered an instance.'}
            </p>
          ) : (
            <p>
              {`No client runs on node ${fields.node}: a node that stops heartbeating is reaped, and its instances with it.`}
            </p>
          )}
        </>
      )}
      action="Register"
      idempotent
      submit={() => api.registerService(fields.node, scope, body)}
      onClose={onClose}
    />
  );
}

function Update({ api, instance, scope, identity, note, nodes, onClose }: {
  api: QraftApi;
  instance: ServiceInstance;
  scope: Scope;
  identity: [string, string][];
  note: ReactNode;
  nodes: CatalogNode[];
  onClose: () => void;
}) {
  const [fields, setFields] = useState<Fields | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const body = fields === null ? null : parse(fields, instance.serviceName);
  if (fields === null || typeof body === 'string' || body === null) {
    return (
      <>
        <InstanceForm nodes={nodes} initial={fields ?? fieldsOf(instance)} fixed onCancel={onClose} onReview={(given) => {
          const parsed = parse(given, instance.serviceName);
          setProblem(typeof parsed === 'string' ? parsed : null);
          setFields(given);
        }} />
        {problem !== null && <Alert type="error" message={problem} />}
      </>
    );
  }
  const before = fieldsOf(instance);
  const changes: string[] = [];
  const compare = (label: string, was: string, now: string) => {
    if (was !== now) changes.push(`${label}: ${was || 'none'} → ${now || 'none'}`);
  };
  compare('Address', before.address, body.address);
  compare('Port', before.port, String(body.port));
  compare('Tags', before.tags, body.tags.join(', '));
  compare('Metadata', before.metadata, Object.entries(body.metadata).map(([key, value]) => `${key}=${value}`).join(', '));
  compare('Enabled', before.enabled ? 'yes' : 'no', body.enabled ? 'yes' : 'no');
  return (
    <Mutation
      title={`Update ${instance.serviceId}`}
      identity={identity}
      review={(
        <>
          {changes.length === 0 ? <p>Nothing changes.</p> : changes.map((change) => <p key={change}>{change}</p>)}
          {note}
        </>
      )}
      action="Save"
      idempotent
      submit={() => api.registerService(instance.nodeId ?? '', scope, body)}
      onClose={onClose}
    />
  );
}
