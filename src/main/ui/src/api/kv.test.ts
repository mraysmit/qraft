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

import { describe, expect, it } from 'vitest';
import { ApiError, createApi } from './client';
import { exactFlags, levelOf, lineDiff, valueOf } from './kv';

// UI-6 Key/Value task list, Phase 1: the key/value reads, and how the interface reads a value and its flags; Phase 2:
// the writes, and the line difference a stale write shows.

interface Call {
  url: string;
  init: RequestInit | undefined;
}

function server(answer: (url: string, init?: RequestInit) => Response) {
  const calls: Call[] = [];
  const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return Promise.resolve(answer(url, init));
  };
  return { calls, fetcher: fetcher as typeof fetch };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

const NOT_FOUND = { code: 'key_not_found', error: 'key_not_found', message: 'No key under: app/', retryable: false };

const ENTRY = { Key: 'app/config', Value: btoa('{"a":1}'), Flags: 3, CreateIndex: 7, ModifyIndex: 9, LockIndex: 0 };

const headerOf = (call: Call, name: string) => (call.init?.headers as Record<string, string>)[name];

describe('the key/value reads', () => {
  it('list one level of a folder in the scope, the consistency and the blocking query asked', async () => {
    const { calls, fetcher } = server(() => json(['app/config', 'app/db/'], 200, { 'X-Qraft-Index': '9' }));

    const level = await createApi('/', fetcher).kvLevel('app/', { tenant: 'acme', namespace: 'prod' },
      { consistency: 'stale', index: 8, wait: '30s' });

    expect(level.data).toEqual(['app/config', 'app/db/']);
    expect(level.index).toBe(9);
    expect(calls[0]?.url).toBe('/v1/kv/app/?keys&separator=/&stale&index=8&wait=30s');
    expect(headerOf(calls[0]!, 'X-Qraft-Tenant')).toBe('acme');
    expect(headerOf(calls[0]!, 'X-Qraft-Namespace')).toBe('prod');
  });

  it('read an empty folder, which the server answers 404, as one with nothing in it', async () => {
    const { calls, fetcher } = server(() => json(NOT_FOUND, 404, { 'X-Qraft-Index': '9' }));

    const level = await createApi('/', fetcher).kvLevel('');

    expect(level.data).toEqual([]);
    expect(level.index).toBe(9);
    expect(calls[0]?.url).toBe('/v1/kv/?keys&separator=/');
  });

  it('encode each segment of a key, keeping its separators', async () => {
    const { calls, fetcher } = server(() => json([ENTRY]));

    await createApi('/', fetcher).kvEntry('app/a b?#%');

    expect(calls[0]?.url).toBe('/v1/kv/app/a%20b%3F%23%25');
  });

  it('read an entry, and one that does not exist as none', async () => {
    const { fetcher } = server((url) => (url.includes('config') ? json([ENTRY], 200, { 'X-Qraft-Index': '9' })
      : json({ ...NOT_FOUND, message: 'No such key: app/gone' }, 404, { 'X-Qraft-Index': '9' })));
    const api = createApi('/', fetcher);

    const entry = await api.kvEntry('app/config');
    expect(entry.data).toEqual({ ...ENTRY, Session: undefined });
    expect(entry.index).toBe(9);
    expect((await api.kvEntry('app/gone')).data).toBeNull();
  });

  it('list every key under a prefix, as a deletion review counts them', async () => {
    const { calls, fetcher } = server(() => json(['app/config', 'app/db/url']));

    expect((await createApi('/', fetcher).kvKeys('app/')).data).toEqual(['app/config', 'app/db/url']);
    expect(calls[0]?.url).toBe('/v1/kv/app/?keys');
  });

  it('say when the server left out what the token may not read, and only then', async () => {
    const { fetcher } = server((url) => json(['app/'], 200,
      url.includes('filtered') ? { 'X-Qraft-Results-Filtered-By-ACLs': 'true' } : {}));
    const api = createApi('/', fetcher);

    expect((await api.kvLevel('filtered/')).filtered).toBe(true);
    expect((await api.kvLevel('all/')).filtered).toBe(false);
  });

  it('report a refusal other than a missing key as a failure', async () => {
    const { fetcher } = server(() => json({ code: 'permission_denied', error: 'permission_denied',
      message: 'Permission denied: token lacks key:read on app/secret', retryable: false }, 403));

    await expect(createApi('/', fetcher).kvEntry('app/secret')).rejects.toBeInstanceOf(ApiError);
  });
});

describe('a value', () => {
  it('is text when it is UTF-8, laid out as JSON when it is an object or a list', () => {
    expect(valueOf(btoa('hello'))).toEqual({ kind: 'text', text: 'hello', size: 5, json: null });
    expect(valueOf(btoa('{"a":1}'))).toEqual({ kind: 'text', text: '{"a":1}', size: 7, json: '{\n  "a": 1\n}' });
    expect(valueOf(btoa('42'))).toMatchObject({ kind: 'text', json: null });
    expect(valueOf('')).toEqual({ kind: 'text', text: '', size: 0, json: null });
  });

  it('counts its size in bytes, not characters', () => {
    const encoded = btoa(String.fromCharCode(...new TextEncoder().encode('é')));

    expect(valueOf(encoded)).toMatchObject({ kind: 'text', text: 'é', size: 2 });
  });

  it('is binary when it is not UTF-8, with its size and base64', () => {
    const encoded = btoa(String.fromCharCode(0xff, 0xfe, 0x00));

    expect(valueOf(encoded)).toEqual({ kind: 'binary', size: 3, base64: encoded });
  });
});

describe('flags', () => {
  it('are exact up to the browser safe integers, and not above them', () => {
    expect(exactFlags(0)).toBe(true);
    expect(exactFlags(Number.MAX_SAFE_INTEGER)).toBe(true);
    // The server answers 2^64 - 1 as a number; JSON reads it rounded.
    expect(exactFlags(JSON.parse('18446744073709551615') as number)).toBe(false);
    expect(exactFlags(JSON.parse('9007199254740993') as number)).toBe(false);
  });
});

describe('a level of the browser', () => {
  it('names each folder and key below the prefix, folders first', () => {
    expect(levelOf('app/', ['app/config', 'app/db/', 'app/a'])).toEqual([
      { name: 'db/', path: 'app/db/', folder: true },
      { name: 'a', path: 'app/a', folder: false },
      { name: 'config', path: 'app/config', folder: false },
    ]);
  });

  it('leaves out the folder key of the prefix itself', () => {
    expect(levelOf('app/', ['app/', 'app/config'])).toEqual([{ name: 'config', path: 'app/config', folder: false }]);
  });
});

describe('the key/value writes', () => {
  it('send a value as the raw body, in the scope, with its flags and the index it was read at', async () => {
    const { calls, fetcher } = server(() => json(true));

    const written = await createApi('/', fetcher).kvPut('app/a b', { tenant: 'acme', namespace: 'prod' },
      '{"a": "é"}', { flags: 7, cas: 24 });

    expect(written.data).toBe(true);
    expect(calls[0]?.url).toBe('/v1/kv/app/a%20b?flags=7&cas=24');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.body).toBe('{"a": "é"}');
    expect(headerOf(calls[0]!, 'Content-Type')).toBe('application/octet-stream');
    expect(headerOf(calls[0]!, 'X-Qraft-Tenant')).toBe('acme');
  });

  it("read a stale compare-and-set's false as the answer it is", async () => {
    const { fetcher } = server(() => json(false));

    expect((await createApi('/', fetcher).kvPut('app/a', undefined, 'x', { flags: 0, cas: 0 })).data).toBe(false);
  });

  it('delete a key at the index it was read at, and every key under a folder', async () => {
    const { calls, fetcher } = server(() => json(true));
    const api = createApi('/', fetcher);

    expect((await api.kvDelete('app/config', undefined, 24)).data).toBe(true);
    expect((await api.kvDeleteTree('app/')).data).toBe(true);
    expect(calls.map((call) => `${call.init?.method} ${call.url}`)).toEqual([
      'DELETE /v1/kv/app/config?cas=24', 'DELETE /v1/kv/app/?recurse']);
  });

  it('say a write that was sent and never answered has an unknown outcome', async () => {
    const fetcher = (() => Promise.reject(new TypeError('connection reset'))) as typeof fetch;

    await expect(createApi('/', fetcher).kvPut('app/a', undefined, 'x', { flags: 0, cas: 3 })).rejects.toMatchObject({
      code: 'outcome_unknown' });
  });
});

describe('a line difference', () => {
  it('marks the lines only one side holds, keeping the order of both', () => {
    expect(lineDiff('a\nb\nc', 'a\nB\nc\nd')).toEqual([
      { kind: 'same', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'added', text: 'B' },
      { kind: 'same', text: 'c' },
      { kind: 'added', text: 'd' },
    ]);
    expect(lineDiff('same', 'same')).toEqual([{ kind: 'same', text: 'same' }]);
  });
});
