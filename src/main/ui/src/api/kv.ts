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

/**
 * A value as the interface shows it (UI-6 Key/Value task list, decision 4): text when its bytes are UTF-8, with a
 * layout when the text is a JSON object or list; otherwise binary, shown by its size and base64 and not edited.
 */
export type Value =
  | { kind: 'text'; text: string; size: number; json: string | null }
  | { kind: 'binary'; size: number; base64: string };

/** The value the server answers in base64. */
export function valueOf(base64: string): Value {
  const binary = atob(base64);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return { kind: 'binary', size: bytes.length, base64 };
  }
  return { kind: 'text', text, size: bytes.length, json: layoutOf(text) };
}

function layoutOf(text: string): string | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed !== null && typeof parsed === 'object' ? JSON.stringify(parsed, null, 2) : null;
  } catch {
    return null;
  }
}

/**
 * Whether flags read from the server are exactly its flags. The server answers them as an unsigned 64-bit number,
 * which the browser reads rounded above its safe integers; such an entry is not edited here (decision 6).
 */
export function exactFlags(flags: number): boolean {
  return Number.isSafeInteger(flags) && flags >= 0;
}

/** One row of the browser: a folder, ending in `/`, or a key, named below the folder shown. */
export interface LevelItem {
  name: string;
  path: string;
  folder: boolean;
}

/**
 * The rows of one level from the keys the server answers for `prefix` with the `/` separator (decision 3): folders
 * first, each group by name. The folder key of the prefix itself is the folder shown, not a row of it.
 */
export function levelOf(prefix: string, keys: readonly string[]): LevelItem[] {
  const items = keys
    .filter((key) => key !== prefix && key.startsWith(prefix))
    .map((key) => ({ name: key.slice(prefix.length), path: key, folder: key.endsWith('/') }));
  return items.sort((a, b) => (a.folder === b.folder ? a.name.localeCompare(b.name) : a.folder ? -1 : 1));
}

/** One line of a difference: in both values, or only in the one before or the one after. */
export interface DiffLine {
  kind: 'same' | 'removed' | 'added';
  text: string;
}

/** The most lines compared line by line; past it, the difference is the whole of each value. */
const MAX_CELLS = 1_000_000;

/**
 * The line difference from `before` to `after`, by their longest common run of lines, a removed line before the line
 * that replaces it: what a stale write shows between the value now held and the one sent (decision 5).
 */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = before.split('\n');
  const b = after.split('\n');
  if (a.length * b.length > MAX_CELLS) {
    return [...a.map((text) => ({ kind: 'removed' as const, text })), ...b.map((text) => ({ kind: 'added' as const, text }))];
  }
  // common[i][j]: the longest common run of a's lines from i and b's from j.
  const common = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      common[i]![j] = a[i] === b[j] ? common[i + 1]![j + 1]! + 1 : Math.max(common[i + 1]![j]!, common[i]![j + 1]!);
    }
  }
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i]! });
      i += 1;
      j += 1;
    } else if (j >= b.length || (i < a.length && common[i + 1]![j]! >= common[i]![j + 1]!)) {
      lines.push({ kind: 'removed', text: a[i]! });
      i += 1;
    } else {
      lines.push({ kind: 'added', text: b[j]! });
      j += 1;
    }
  }
  return lines;
}
