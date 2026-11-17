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

/** The most rows a watch's log keeps (UI-6 Sessions, Locks and Watches task list, decision 10). */
export const MAX_WATCH_ROWS = 500;

/** One row of a watch's log: its start, a delivery, a resynchronization, or a failed read. */
export interface WatchRow {
  id: number;
  /** When the interface logged it, in epoch milliseconds by its clock. */
  at: number;
  /** The index the delivery reflects, or null for a row without one. */
  index: number | null;
  kind: 'start' | 'delivery' | 'resynchronized' | 'failure';
  text: string;
}

/** `rows` with `row` first, keeping at most the limit. */
export function withRow(rows: readonly WatchRow[], row: WatchRow): WatchRow[] {
  return [row, ...rows].slice(0, MAX_WATCH_ROWS);
}

/** What changed between two lists of names, `added a; removed b`, or nothing when neither has one the other lacks. */
export function changesOf(before: readonly string[] | null, after: readonly string[], added: string, removed: string): string {
  if (before === null) return '';
  const was = new Set(before);
  const now = new Set(after);
  const parts: string[] = [];
  const came = after.filter((name) => !was.has(name));
  const went = before.filter((name) => !now.has(name));
  if (came.length > 0) parts.push(`${added} ${came.join(', ')}`);
  if (went.length > 0) parts.push(`${removed} ${went.join(', ')}`);
  return parts.map((part) => `; ${part}`).join('');
}
