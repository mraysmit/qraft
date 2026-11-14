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
import type { QraftApi, Read } from '../api/client';
import { CHECK, HEALTH_ENTRY, INSTANCE, NODE, RAFT_STATUS } from '../api/fixtures';
import {
  type CatalogNode,
  CatalogNodeSchema,
  type HealthEntry,
  HealthEntrySchema,
  type RaftStatus,
  RaftStatusSchema,
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

/** An API that answers from the given data; any read can be replaced. */
export function fakeApi(overrides: Partial<QraftApi> = {}, entries: Record<string, HealthEntry[]> = { web: [entry()] }): QraftApi {
  const names = Object.fromEntries(
    Object.entries(entries).map(([name, list]) => [name, [...new Set(list.flatMap((item) => item.service.tags))]]),
  );
  return {
    raftStatus: () => read(raftStatus()),
    readiness: () => read({ ready: true, conditions: [] }),
    nodes: () => read([node()]),
    services: () => read(names),
    serviceHealth: (name) => read(entries[name] ?? []),
    ...overrides,
  };
}
