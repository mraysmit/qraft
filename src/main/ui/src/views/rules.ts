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

import type { Explanation } from '../api/schemas';

// A policy's rules shown as rows, for reading only. The interface never decides from them what a token may do: the
// server explains that (UI/UX design section 10.2.5), and anything here it does not recognise is shown as it is.

/** One rule of a policy as the structured view shows it. */
export interface RuleRow {
  resource: string;
  match: 'exact' | 'prefix' | 'single';
  name: string;
  access: string;
  /** For a service or key rule, its tenant and namespace; a name ending `*` is a prefix. */
  tenant?: string;
  namespace?: string;
}

const NAMED = new Set(['node', 'service', 'client', 'key', 'session']);
const SCOPED = new Set(['service', 'key']);

/** The rows of Consul's JSON form of `rules`, as the server's `AclRulesJson` reads it. */
export function rulesOf(rules: Record<string, unknown>): RuleRow[] {
  const rows: RuleRow[] = [];
  walk(rules, undefined, undefined, rows);
  return rows;
}

function entries(value: unknown): [string, unknown][] {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? Object.entries(value) : [];
}

function walk(block: unknown, tenant: string | undefined, namespace: string | undefined, rows: RuleRow[]) {
  for (const [key, value] of entries(block)) {
    if (key === 'acl' || key === 'operator') {
      rows.push({ resource: key, match: 'single', name: '', access: String(value) });
    } else if (key === 'tenant' || key === 'tenant_prefix') {
      for (const [name, inner] of entries(value)) walk(inner, key === 'tenant' ? name : `${name}*`, namespace, rows);
    } else if (key === 'namespace' || key === 'namespace_prefix') {
      for (const [name, inner] of entries(value)) walk(inner, tenant, key === 'namespace' ? name : `${name}*`, rows);
    } else {
      const prefix = key.endsWith('_prefix');
      const resource = prefix ? key.slice(0, -'_prefix'.length) : key;
      if (!NAMED.has(resource)) {
        rows.push({ resource: key, match: 'single', name: '', access: JSON.stringify(value) });
        continue;
      }
      for (const [name, rule] of entries(value)) {
        const access = entries(rule).find(([field]) => field === 'policy')?.[1];
        const row: RuleRow = { resource, match: prefix ? 'prefix' : 'exact', name, access: String(access) };
        if (SCOPED.has(resource)) {
          row.tenant = tenant ?? 'default';
          row.namespace = namespace ?? 'default';
        }
        rows.push(row);
      }
    }
  }
}

/** A rule as words: `service web`, `node_prefix ""`, `operator`. */
export function ruleText(rule: { resource: string; name?: string | undefined; prefix?: boolean; match?: RuleRow['match'] }): string {
  const prefix = rule.match === 'prefix' || (rule.match === undefined && rule.prefix === true);
  if (rule.match === 'single' || rule.name === undefined) return rule.resource;
  return prefix ? `${rule.resource}_prefix "${rule.name}"` : `${rule.resource} ${rule.name}`;
}

/** The rules in one line, for a list. */
export function rulesSummary(rules: Record<string, unknown>): string {
  return rulesOf(rules).map((row) => `${ruleText(row)}: ${row.access}`).join('; ');
}

export const PRECEDENCE = 'An exact name beats a prefix, a longer prefix beats a shorter one, and where two rules are '
  + 'equally specific deny wins, then write, then read.';

/** The server's explanation in words: allowed or denied, and the rule, identity or default policy that decided. */
export function explanationText(explanation: Explanation): string {
  const word = explanation.allowed ? 'Allowed' : 'Denied';
  if (explanation.decidedBy === 'default' || explanation.rule === undefined) {
    return `${word}: no rule matches, so the default policy, ${explanation.defaultPolicy}, decides.`;
  }
  const { rule } = explanation;
  let origin = '';
  if (explanation.policy !== undefined) origin = ` of policy ${explanation.policy.name ?? explanation.policy.id}`;
  else if (explanation.nodeIdentity !== undefined) origin = ` of the node identity ${explanation.nodeIdentity.nodeName}`;
  else if (explanation.serviceIdentity !== undefined) {
    const identity = explanation.serviceIdentity;
    origin = ` of the service identity ${identity.serviceName} in ${identity.tenant}/${identity.namespace}`;
  }
  const role = explanation.role === undefined ? '' : ` through role ${explanation.role.name ?? explanation.role.id}`;
  return `${word}: decided by the rule ${ruleText(rule)}: ${rule.access}${origin}${role}.`;
}
