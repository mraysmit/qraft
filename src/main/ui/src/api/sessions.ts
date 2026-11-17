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

import type { HeldKey } from './schemas';

/** Which held keys the locks read answers: one session's, or every session's (UI-6 Sessions list, decision 4). */
export interface LocksFilter {
  session?: string;
}

/** The keys each session holds, by session ID. */
export function heldBy(held: readonly HeldKey[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const key of held) counts.set(key.Session, (counts.get(key.Session) ?? 0) + 1);
  return counts;
}

const UNIT_MILLIS: Record<string, number> = { h: 3_600_000, m: 60_000, s: 1000, ms: 1, us: 0.001, µs: 0.001, ns: 0.000_001 };

/** A Go duration, as Consul writes a TTL, such as `30s` or `1m30s`, in milliseconds; null for anything else. */
export function durationMillis(text: string): number | null {
  const part = /(\d+(?:\.\d+)?)(ms|us|µs|ns|h|m|s)/y;
  let total = 0;
  let read = 0;
  for (let match = part.exec(text); match !== null; match = part.exec(text)) {
    total += Number(match[1]) * (UNIT_MILLIS[match[2] ?? ''] ?? Number.NaN);
    read = part.lastIndex;
  }
  return read > 0 && read === text.length && Number.isFinite(total) ? Math.round(total) : null;
}

/** A lock delay, which the server answers in nanoseconds, in words: `15 s`, `0.5 s`, or none. */
export function lockDelayText(nanos: number): string {
  if (nanos === 0) return 'None';
  return `${Number((nanos / 1_000_000_000).toFixed(3))} s`;
}

/** How long a session has left before it lapses, in whole seconds: `9 s`, `1 min 5 s`; due once none is left. */
export function timeLeftText(millis: number): string {
  const seconds = Math.floor(millis / 1000);
  if (seconds <= 0) return 'Due to lapse now';
  return seconds < 60 ? `${seconds} s` : `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

const END_REASONS: Record<string, string> = {
  destroyed: 'It was destroyed.',
  ttl: 'Its TTL lapsed without a renewal.',
  node: 'Its node failed or left the catalog.',
};

/** Why a session ended, from its `session.destroyed` event's reason (UI-6 Sessions list, decision 3). */
export function endReason(reason: string): string {
  return END_REASONS[reason] ?? `It ended: ${reason}.`;
}

/** What a session's behaviour does to its keys when it ends, in words. */
export function behaviourText(behaviour: 'release' | 'delete'): string {
  return behaviour === 'release' ? 'Release its keys' : 'Delete its keys';
}
