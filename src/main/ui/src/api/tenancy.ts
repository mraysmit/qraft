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

import type { TenantHoldings } from './schemas';

/** What a tenant's namespaces hold, summed (UI-6 Tenants, Namespaces and Audit task list, decision 2). */
export interface Totals {
  namespaces: number;
  services: number;
  instances: number;
  keys: number;
  heldKeys: number;
}

/** Whether `name` is a tenant or namespace name the server takes: a DNS label in lower case, as `TenancyNames` has it. */
export function validScopeName(name: string): boolean {
  return /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(name);
}

export function totalsOf(tenant: TenantHoldings): Totals {
  return tenant.Namespaces.reduce((sum, namespace) => ({
    namespaces: sum.namespaces + 1,
    services: sum.services + namespace.Services,
    instances: sum.instances + namespace.Instances,
    keys: sum.keys + namespace.Keys,
    heldKeys: sum.heldKeys + namespace.HeldKeys,
  }), { namespaces: 0, services: 0, instances: 0, keys: 0, heldKeys: 0 });
}
