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

// Test data in the shapes a server answered on 2026-11-14, for the tests only: nothing outside a test imports it.

export const RAFT_STATUS = {
  nodeId: 'server-1',
  serverId: '341e9cb3-511c-4fba-bbf5-da75710c18ef',
  state: 'LEADER',
  term: 3,
  leaderId: 'server-1',
  commitIndex: 42,
  lastApplied: 42,
  lastLogIndex: 42,
  snapshotLastIndex: 0,
  fenced: false,
  fenceReason: null,
  removed: false,
  storageBytes: 632,
  snapshotAgeSeconds: -1,
};

export const NODE = {
  name: 'client-a',
  id: 'f9fbc3fb-6e4e-4685-8125-90d9132f5890',
  address: '10.0.0.5',
  datacenter: 'dc1',
  region: 'eu-west',
  metadata: { 'qraft.registrationId': '329aca92-737a-4ea0-bb0b-64d929f316b9', 'qraft.version': '1.0-SNAPSHOT' },
  status: 'healthy',
  registrationTime: '2026-11-14T03:15:24.567Z',
  lastHeartbeat: '2026-11-14T03:16:01.002Z',
};

export const INSTANCE = {
  serviceId: 'web-1',
  serviceName: 'web',
  nodeId: 'client-a',
  address: '10.0.0.5',
  port: 8081,
  tags: ['http'],
  metadata: { owner: 'platform' },
  health: 'PASSING',
  tenantId: 'default',
  namespace: 'default',
  datacenter: 'dc1',
  region: 'eu-west',
  enabled: true,
  createIndex: 4,
  modifyIndex: 9,
};

export const CHECK = {
  checkId: 'http',
  status: 'PASSING',
  required: true,
  sequenceNumber: 7,
  observedAt: '2026-11-14T03:16:00.100Z',
  acceptedAt: '2026-11-14T03:16:00.150Z',
  deadline: '2026-11-14T03:16:30.150Z',
  expired: false,
  output: '200 OK',
  deregisterAfterMillis: 0,
};

export const HEALTH_ENTRY = { service: INSTANCE, checks: [CHECK], nodeStatus: 'healthy' };

export const SUMMARY_ENTRY = {
  name: 'web',
  tenantId: 'default',
  namespace: 'default',
  tags: ['http'],
  instanceCount: 2,
  health: { unknown: 0, passing: 1, warning: 0, critical: 1, maintenance: 0 },
  datacenters: ['dc1'],
  regions: ['eu-west'],
  modifyIndex: 9,
};

export const CHECK_ENTRY = {
  serviceId: 'web-1',
  serviceName: 'web',
  nodeId: 'client-a',
  nodeStatus: 'healthy',
  tenantId: 'default',
  namespace: 'default',
  ...CHECK,
};

export const TENANT = { Name: 'acme', Description: 'Acme Ltd', CreateIndex: 3, ModifyIndex: 3 };

export const NAMESPACE = { Name: 'prod', Tenant: 'acme', Description: '', CreateIndex: 4, ModifyIndex: 4 };

const member = (name: string, changes: Record<string, unknown> = {}) => ({
  serverId: `${name}-id`,
  name,
  address: `${name}:9080`,
  voter: true,
  leader: false,
  self: false,
  matchIndex: 42,
  nextIndex: 43,
  lag: 0,
  lastContactMillis: 120,
  reachable: true,
  healthy: true,
  ...changes,
});

export const MEMBERS = {
  view: 'leader',
  answeredBy: 'server-1',
  leaderId: 'server-1',
  term: 3,
  commitIndex: 42,
  lastLogIndex: 42,
  quorum: { voters: 3, quorumSize: 2, reachableVoters: 3, failureTolerance: 1, healthy: true },
  servers: [
    member('server-1', { leader: true, self: true, lastContactMillis: 0 }),
    member('server-2'),
    member('server-3'),
  ],
};

const unknownProgress = {
  matchIndex: null,
  nextIndex: null,
  lag: null,
  lastContactMillis: null,
  reachable: null,
  healthy: null,
};

export const FOLLOWER_MEMBERS = {
  ...MEMBERS,
  view: 'follower',
  answeredBy: 'server-2',
  quorum: null,
  servers: [
    member('server-1', { leader: true, ...unknownProgress }),
    member('server-2', { self: true, ...unknownProgress }),
    member('server-3', unknownProgress),
  ],
};

export const STORAGE = {
  answeredBy: 'server-1',
  storageBytes: 1536000,
  snapshotAgeSeconds: 90,
  snapshotLastIndex: 30,
  snapshotLastTerm: 2,
  firstLogIndex: 31,
  lastLogIndex: 42,
  commitIndex: 42,
  lastApplied: 42,
  fenced: false,
  fenceReason: null,
  transitionQueueDepth: 0,
  lastSnapshotFailure: null,
};

const BOOT = '6d1f0c52-8a0e-4a51-9c39-3f7a2a1b9e01';

/** A cursor of the server's boot in the event fixtures. */
export const cursor = (sequence: number): string => `${BOOT}:${sequence}`;

export const SYSTEM_EVENT = {
  cursor: cursor(1),
  node: 'server-1',
  observedAt: '2026-11-15T09:00:00.000Z',
  category: 'system',
  severity: 'info',
  term: 3,
  type: 'raft.leadership_gained',
  event: { term: 3 },
};

export const SERVICE_EVENT = {
  cursor: cursor(2),
  node: 'server-1',
  observedAt: '2026-11-15T09:00:01.000Z',
  category: 'domain',
  severity: 'info',
  term: 3,
  committedIndex: 7,
  replicatedId: '7:0:service.registered',
  tenant: 'default',
  namespace: 'default',
  type: 'service.registered',
  event: { serviceId: 'web-1', serviceName: 'web', node: 'client-a' },
};

export const EVENT_PAGE = {
  events: [SYSTEM_EVENT, SERVICE_EVENT],
  next: cursor(2),
  status: 'complete',
  capacity: 4096,
};

export const CONFIGURATION = {
  server: 'server-1',
  file: '/etc/qraft/server.json',
  readAt: '2026-11-15T08:59:58.000Z',
  settings: [
    { key: 'server.id', value: 'server-1', source: 'configured', redacted: false, restartRequired: true },
    { key: 'server.http.port', value: 8080, source: 'default', redacted: false, restartRequired: true },
    { key: 'server.raft.nodes', value: { 'server-1': '10.0.0.1:9080' }, source: 'configured', redacted: false,
      restartRequired: true },
    { key: 'server.raft.storage.fsync', value: true, source: 'default', redacted: false, restartRequired: true },
    { key: 'server.operator.token', value: null, source: 'configured', redacted: true, restartRequired: true },
    { key: 'server.acl.defaultPolicy', value: null, source: 'default', redacted: false, restartRequired: true },
  ],
};

// UI-5: the access answers, in the shapes AdminUiDataContractTest's server answered on 2026-11-15. No fixture holds a
// real secret: a read shows `<hidden>`, and a create's secret here is made up.

export const MANAGEMENT_POLICY_ID = '00000000-0000-0000-0000-000000000001';

export const TOKEN = {
  accessorId: '6a1f0c2e-3b7d-4c55-9a10-2f4e8d1b7c90',
  secretId: '<hidden>',
  description: 'web deployer',
  policies: [{ id: '5e0b8a3c-1d2f-4a6b-8c9d-0e1f2a3b4c5d', name: 'web-writer' }],
  roles: [],
  nodeIdentities: [{ nodeName: 'client-a' }],
  serviceIdentities: [{ serviceName: 'web', tenant: 'default', namespace: 'default' }],
  createTime: '2026-11-15T07:00:00Z',
  expirationTime: null,
  installedFromFile: false,
};

export const MANAGEMENT_TOKEN = {
  accessorId: '2b7e4f1a-9c3d-4e8f-a1b2-c3d4e5f6a7b8',
  secretId: '<hidden>',
  description: 'Bootstrap Token (Global Management)',
  policies: [{ id: MANAGEMENT_POLICY_ID, name: 'global-management' }],
  roles: [],
  nodeIdentities: [],
  serviceIdentities: [],
  createTime: '2026-11-15T06:00:00Z',
  expirationTime: null,
  installedFromFile: false,
};

export const AUTHORIZATIONS = [
  { resource: 'operator', access: 'read', allow: true },
  { resource: 'operator', access: 'write', allow: false },
  { resource: 'acl', access: 'read', allow: true },
  { resource: 'acl', access: 'write', allow: false },
];

export const POLICY = {
  id: '5e0b8a3c-1d2f-4a6b-8c9d-0e1f2a3b4c5d',
  name: 'web-writer',
  description: 'writes web',
  rules: { service: { web: { policy: 'write' } } },
};

export const EXPLANATION = {
  accessorId: TOKEN.accessorId,
  allowed: true,
  defaultPolicy: 'deny',
  decidedBy: 'rule',
  policy: { id: POLICY.id, name: 'web-writer' },
  rule: { resource: 'service', name: 'web', prefix: false, access: 'write',
    tenant: { name: 'default', prefix: false }, namespace: { name: 'default', prefix: false } },
};

export const ACL_STATUS = {
  enabled: true,
  defaultPolicy: 'deny',
  securityProfile: 'production',
  transport: { https: false, clientCertificateRequired: false },
  readAt: '2026-11-15T09:00:00Z',
  expiringWithinMinutes: 60,
  managementTokens: [
    { accessorId: MANAGEMENT_TOKEN.accessorId, description: MANAGEMENT_TOKEN.description, installedFromFile: false,
      expirationTime: null },
    { accessorId: '9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a', description: 'management file', installedFromFile: true,
      expirationTime: null },
  ],
  anonymous: {
    accessorId: '00000000-0000-0000-0000-000000000002',
    policies: [{ id: '7c6b5a4f-3e2d-4c1b-a09f-8e7d6c5b4a3f', name: 'open-services' }],
    writes: ['service'],
  },
  aclWriters: [{ accessorId: MANAGEMENT_TOKEN.accessorId, description: MANAGEMENT_TOKEN.description }],
  expiringSoon: [
    { accessorId: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5e', description: 'ci deploy', expirationTime: '2026-11-15T09:30:00Z',
      nodeIdentities: [{ nodeName: 'client-b' }], serviceIdentities: [], holdsAclWrite: true },
  ],
};

export const ANONYMOUS_TOKEN = {
  accessorId: '00000000-0000-0000-0000-000000000002',
  secretId: '<hidden>',
  description: 'Anonymous Token',
  policies: [],
  roles: [],
  nodeIdentities: [],
  serviceIdentities: [],
  createTime: '2026-11-15T06:00:00Z',
  expirationTime: null,
  installedFromFile: false,
};

export const FILE_TOKEN = {
  ...MANAGEMENT_TOKEN,
  accessorId: '9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a',
  description: 'management file',
  installedFromFile: true,
};

export const GLOBAL_MANAGEMENT_POLICY = {
  id: MANAGEMENT_POLICY_ID,
  name: 'global-management',
  description: 'Builtin Policy that grants unlimited access',
  rules: { acl: 'write', operator: 'write', node_prefix: { '': { policy: 'write' } } },
};

export const READ_ONLY_POLICY = {
  id: '00000000-0000-0000-0000-000000000002',
  name: 'builtin/global-read-only',
  description: 'Builtin Policy that grants unlimited read-only access',
  rules: { operator: 'read', node_prefix: { '': { policy: 'read' } } },
};
