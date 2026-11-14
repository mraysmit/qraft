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
