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

import { Alert, Button, Space, Tag, Typography } from 'antd';
import { ApiError, DEFAULT_SCOPE, MalformedResponseError, type QraftApi, type Scope } from '../api/client';
import type { Health, HealthEntry, NodeStatus, ServiceNames } from '../api/schemas';

// Each health state has its own label and mark, so colour is never the only signal (UI/UX design sections 3.6, 5.3).
const HEALTH: Record<Health, { label: string; mark: string; color: string }> = {
  CRITICAL: { label: 'Critical', mark: '✕', color: 'red' },
  WARNING: { label: 'Warning', mark: '!', color: 'gold' },
  MAINTENANCE: { label: 'Maintenance', mark: '‖', color: 'blue' },
  UNKNOWN: { label: 'Unknown', mark: '?', color: 'default' },
  PASSING: { label: 'Passing', mark: '✓', color: 'green' },
};

/** The order a list sorts health in: what needs attention first. */
export const HEALTH_ORDER: readonly Health[] = ['CRITICAL', 'WARNING', 'MAINTENANCE', 'UNKNOWN', 'PASSING'];

export function HealthLabel({ health }: { health: Health }) {
  const { label, mark, color } = HEALTH[health];
  return (
    <Tag color={color}>
      <span aria-hidden="true">{mark} </span>
      <span>{label}</span>
    </Tag>
  );
}

/** The worst health of a service's instances, or null when it has none. */
export function worstHealth(entries: readonly HealthEntry[]): Health | null {
  let worst: Health | null = null;
  for (const entry of entries) {
    const health = entry.service.health;
    if (worst === null || HEALTH_ORDER.indexOf(health) < HEALTH_ORDER.indexOf(worst)) worst = health;
  }
  return worst;
}

const NODE_STATUS: Record<NodeStatus, string> = {
  registering: 'Registering',
  healthy: 'Healthy',
  unreachable: 'Unreachable',
};

/** A node's status in words. It is not service health, so it takes no health colour or mark (UI/UX section 5.2). */
export function NodeStatusLabel({ status }: { status: NodeStatus }) {
  return <Tag>{NODE_STATUS[status]}</Tag>;
}

/** An instant as UTC to the second, the same for every viewer. */
export function formatInstant(iso: string): string {
  const time = new Date(iso);
  if (Number.isNaN(time.getTime())) return iso;
  return `${time.toISOString().slice(0, 19).replace('T', ' ')} UTC`;
}

/** The read metadata a view's data came with (UI/UX design section 12). */
export function ReadMeta({ index, stale }: { index: number | null; stale?: boolean }) {
  return (
    <Typography.Text type="secondary">
      {index === null ? 'Applied index not reported' : `Applied index ${index}`}
      {stale === true ? ' · reading again, this may be out of date' : ''}
    </Typography.Text>
  );
}

/** A failed read: what failed, why, whether trying again may help, and the IDs a diagnosis needs. */
export function Failure({ title, error, onRetry }: { title: string; error: unknown; onRetry: () => void }) {
  const lines: string[] = [];
  if (error instanceof ApiError) {
    lines.push(error.message, `Code: ${error.code}`);
    if (error.requestId !== null) lines.push(`Request ID: ${error.requestId}`);
    if (error.leaderId !== null) lines.push(`Leader: ${error.leaderId}`);
    lines.push(error.retryable ? 'Retrying may succeed.' : 'Retrying will not help until the cause is fixed.');
  } else if (error instanceof MalformedResponseError) {
    lines.push(error.message);
    if (error.requestId !== null) lines.push(`Request ID: ${error.requestId}`);
    lines.push('The interface and the server may be different versions.');
  } else {
    lines.push(error instanceof Error ? error.message : String(error));
  }
  return (
    <Alert
      type="error"
      showIcon
      aria-label={title}
      message={title}
      description={
        <Space direction="vertical" size={0}>
          {lines.map((line) => (
            <span key={line}>{line}</span>
          ))}
        </Space>
      }
      action={
        <Button size="small" onClick={onRetry}>
          Read again
        </Button>
      }
    />
  );
}

/** Every service in the read scope with its instances and checks: one read per service until UI-2's summary. */
export interface ServiceEntries {
  names: ServiceNames;
  entries: Record<string, HealthEntry[]>;
  index: number | null;
}

export async function loadServiceEntries(api: QraftApi, scope: Scope): Promise<ServiceEntries> {
  const services = await api.services(scope);
  const names = Object.keys(services.data);
  const reads = await Promise.all(names.map((name) => api.serviceHealth(name, scope)));
  const entries: Record<string, HealthEntry[]> = {};
  names.forEach((name, position) => {
    entries[name] = reads[position]?.data ?? [];
  });
  return { names: services.data, entries, index: services.index };
}

/** The distinct values of a list, joined for display, or a dash when there are none. */
export function distinct(values: readonly string[]): string {
  const unique = [...new Set(values)];
  return unique.length === 0 ? '—' : unique.join(', ');
}

/** Where an instance or node runs: its datacenter and region. */
export function location(datacenter: string | null, region: string | null): string {
  return `${datacenter ?? 'no datacenter'} / ${region ?? 'no region'}`;
}

/** The scope a view read in, in words: the default scope by that name, any other by its tenant and namespace. */
export function scopeText(scope: Scope): string {
  return scope.tenant === DEFAULT_SCOPE.tenant && scope.namespace === DEFAULT_SCOPE.namespace
    ? 'the default tenant and namespace'
    : `tenant ${scope.tenant}, namespace ${scope.namespace}`;
}

/** The line each scoped view starts with, naming the scope it read (UI/UX design section 3.2). */
export function ReadIn({ scope }: { scope: Scope }) {
  return <Typography.Text>{`Read in ${scopeText(scope)}.`}</Typography.Text>;
}

/** Counts by health in words, attention first, leaving out the states with none: `1 critical, 2 passing`. */
export function healthCountsText(counts: Partial<Record<Lowercase<Health>, number>>): string {
  return HEALTH_ORDER.map((health) => [counts[health.toLowerCase() as Lowercase<Health>] ?? 0, health] as const)
    .filter(([count]) => count > 0)
    .map(([count, health]) => `${count} ${health.toLowerCase()}`)
    .join(', ');
}

/** A size in words: bytes, kB or MB, to one decimal. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} kB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** How long ago, from a count of milliseconds: `120 ms ago`, `12.0 s ago`, `3 min ago`. */
export function formatSince(millis: number): string {
  if (millis < 1000) return `${millis} ms ago`;
  if (millis < 60_000) return `${(millis / 1000).toFixed(1)} s ago`;
  return `${Math.round(millis / 60_000)} min ago`;
}

/** A count with its noun, singular or plural: `1 service`, `0 transitions`. */
export function plural(count: number, noun: string, nouns = `${noun}s`): string {
  return `${count} ${count === 1 ? noun : nouns}`;
}
