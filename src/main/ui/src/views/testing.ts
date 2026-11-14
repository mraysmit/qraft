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

// Test support only: nothing outside a test imports it.

import type { Bootstrap } from '../bootstrap';
import type { CheckState, QraftApi, Read, Scope } from '../api/client';
import { CHECK, FOLLOWER_MEMBERS, HEALTH_ENTRY, INSTANCE, MEMBERS, NODE, RAFT_STATUS, STORAGE } from '../api/fixtures';
import {
  type CatalogNode,
  CatalogNodeSchema,
  type CheckEntry,
  type HealthEntry,
  HealthEntrySchema,
  type Members,
  MembersSchema,
  type Storage,
  StorageSchema,
  type RaftStatus,
  RaftStatusSchema,
  type ServiceSummary,
} from '../api/schemas';

export const BOOTSTRAP: Bootstrap = {
  apiBasePath: '/',
  uiPath: '/ui/',
  features: {},
  authMode: 'token',
  securityProfile: 'production',
  cspNonce: 'dGVzdC1ub25jZQ==',
};

export function read<T>(data: T, index: number | null = 12): Promise<Read<T>> {
  return Promise.resolve({ data, index, requestId: 'req-1' });
}

export const raftStatus = (changes: Partial<RaftStatus> = {}): RaftStatus =>
  RaftStatusSchema.parse({ ...RAFT_STATUS, ...changes });

export const node = (changes: Partial<CatalogNode> = {}): CatalogNode => CatalogNodeSchema.parse({ ...NODE, ...changes });

export function entry(
  service: Partial<HealthEntry['service']> = {},
  checks: Partial<HealthEntry['checks'][number]>[] = [{}],
  nodeStatus: HealthEntry['nodeStatus'] = 'healthy',
): HealthEntry {
  return HealthEntrySchema.parse({
    ...HEALTH_ENTRY,
    service: { ...INSTANCE, ...service },
    checks: checks.map((check) => ({ ...CHECK, ...check })),
    nodeStatus,
  });
}

/** The membership as the leader answers it, with any field replaced. */
export const members = (changes: Partial<Members> = {}): Members => MembersSchema.parse({ ...MEMBERS, ...changes });

/** The membership as a follower answers it. */
export const followerMembers = (): Members => MembersSchema.parse(FOLLOWER_MEMBERS);

/** The storage read, with any field replaced. */
export const storage = (changes: Partial<Storage> = {}): Storage => StorageSchema.parse({ ...STORAGE, ...changes });

/** The summary the server would give for one service's entries. */
export function summaryOf(name: string, list: HealthEntry[]): ServiceSummary {
  const health = { unknown: 0, passing: 0, warning: 0, critical: 0, maintenance: 0 };
  for (const item of list) {
    const key = item.service.health.toLowerCase() as keyof typeof health;
    health[key] += 1;
  }
  const distinct = (values: (string | null)[]) => [...new Set(values.filter((value): value is string => !!value))].sort();
  return {
    name,
    tenantId: list[0]?.service.tenantId ?? 'default',
    namespace: list[0]?.service.namespace ?? 'default',
    tags: distinct(list.flatMap((item) => item.service.tags)),
    instanceCount: list.length,
    health,
    datacenters: distinct(list.map((item) => item.service.datacenter)),
    regions: distinct(list.map((item) => item.service.region)),
    modifyIndex: Math.max(0, ...list.map((item) => item.service.modifyIndex)),
  };
}

/** The check listing the server would give for the entries, in a state. */
export function checksOf(entries: Record<string, HealthEntry[]>, state: CheckState): CheckEntry[] {
  return Object.values(entries)
    .flat()
    .flatMap((item) =>
      item.checks.map((check) => ({
        ...check,
        serviceId: item.service.serviceId,
        serviceName: item.service.serviceName,
        nodeId: item.service.nodeId,
        nodeStatus: item.nodeStatus,
        tenantId: item.service.tenantId,
        namespace: item.service.namespace,
      })),
    )
    .filter((check) => state === 'any' || check.status.toLowerCase() === state);
}

/** The scopes each scoped read was asked for, in order, by read. */
export interface ScopesAsked {
  services: Scope[];
  serviceHealth: Scope[];
  servicesSummary: Scope[];
  checks: Scope[];
}

/**
 * An API that answers from the given data, whatever the scope, and records the scope each read asked for in `asked`;
 * any read can be replaced. Two tenants exist: `default`, and `acme` with namespaces `default` and `prod`.
 */
export function fakeApi(
  overrides: Partial<QraftApi> = {},
  entries: Record<string, HealthEntry[]> = { web: [entry()] },
  asked: ScopesAsked = { services: [], serviceHealth: [], servicesSummary: [], checks: [] },
): QraftApi {
  const names = Object.fromEntries(
    Object.entries(entries).map(([name, list]) => [name, [...new Set(list.flatMap((item) => item.service.tags))]]),
  );
  const DEFAULT = { tenant: 'default', namespace: 'default' };
  return {
    raftStatus: () => read(raftStatus()),
    readiness: () => read({ ready: true, conditions: [] }),
    nodes: () => read([node()]),
    services: (scope = DEFAULT) => {
      asked.services.push(scope);
      return read(names);
    },
    serviceHealth: (name, scope = DEFAULT) => {
      asked.serviceHealth.push(scope);
      return read(entries[name] ?? []);
    },
    servicesSummary: (scope = DEFAULT) => {
      asked.servicesSummary.push(scope);
      return read(Object.entries(entries).map(([name, list]) => summaryOf(name, list)));
    },
    checks: (state, scope = DEFAULT) => {
      asked.checks.push(scope);
      return read(checksOf(entries, state));
    },
    tenants: () =>
      read([
        { Name: 'acme', Description: 'Acme Ltd' },
        { Name: 'default', Description: '' },
      ]),
    namespaces: (tenant) =>
      read(
        tenant === 'acme'
          ? [
              { Name: 'default', Tenant: 'acme', Description: '' },
              { Name: 'prod', Tenant: 'acme', Description: '' },
            ]
          : [{ Name: 'default', Tenant: tenant, Description: '' }],
      ),
    members: () => read(members()),
    storage: () => read(storage()),
    ...overrides,
  };
}
