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
import { ApiError, createApi, MalformedResponseError, type WatchEnd } from './client';
import type { QraftEvent } from './schemas';
import { CONFIGURATION, EVENT_PAGE, SERVICE_EVENT, SYSTEM_EVENT, cursor } from './fixtures';

// UI-4 task list, Phase 1: the read options and metadata, the configuration and event reads, and the event watch.

interface Call {
  url: string;
  init: RequestInit | undefined;
}

function server(answer: (url: string, init?: RequestInit) => Response | Promise<Response>) {
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

/** A stream the test writes server-sent event text into, a piece at a time, and ends. */
function stream() {
  const encoder = new TextEncoder();
  let control!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({
    start(source) {
      control = source;
    },
  });
  return {
    response: () => new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    write: (text: string) => control.enqueue(encoder.encode(text)),
    end: () => control.close(),
  };
}

/** Lets the stream's pieces be read: each read of the body takes a turn of the microtask queue. */
async function settle(): Promise<void> {
  for (let turn = 0; turn < 20; turn += 1) await Promise.resolve();
}

describe('read options and metadata', () => {
  it('send the consistency chosen on every read that takes it', async () => {
    const { calls, fetcher } = server((url) => json(url.includes('/catalog/services') ? {} : []));
    const api = createApi('/', fetcher);
    const consistent = { consistency: 'consistent' } as const;

    await api.nodes(consistent);
    await api.services(undefined, { consistency: 'stale' });
    await api.serviceHealth('web', undefined, consistent);
    await api.servicesSummary(undefined, consistent);
    await api.checks('any', undefined, consistent);
    await api.tenants(consistent);
    await api.namespaces('acme', consistent);
    await api.nodes({ consistency: 'default' });

    expect(calls.map((call) => call.url)).toEqual([
      '/v1/catalog/nodes?consistent',
      '/v1/catalog/services?stale',
      '/v1/health/service/web?consistent',
      '/v1/internal/ui/services?consistent',
      '/v1/health/state/any?consistent',
      '/v1/tenants?consistent',
      '/v1/namespaces?consistent',
      '/v1/catalog/nodes',
    ]);
  });

  it('send a blocking query its index and wait, and pass the signal that ends it', async () => {
    const { calls, fetcher } = server(() => json([]));
    const signal = new AbortController().signal;

    await createApi('/', fetcher).servicesSummary(undefined, { consistency: 'stale', index: 41, wait: '30s', signal });

    expect(calls[0]?.url).toBe('/v1/internal/ui/services?stale&index=41&wait=30s');
    expect(calls[0]?.init?.signal).toBe(signal);
  });

  it('read the answering server, the consistency applied and the leader contact from each answer', async () => {
    const { fetcher } = server(() =>
      json([], 200, {
        'X-Qraft-Index': '42',
        'X-Qraft-Answered-By': 'server-2',
        'X-Qraft-Consistency': 'stale',
        'X-Qraft-Known-Leader': 'true',
        'X-Qraft-Last-Contact': '120',
      }),
    );

    const read = await createApi('/', fetcher).nodes();

    expect(read).toMatchObject({ index: 42, answeredBy: 'server-2', consistency: 'stale', knownLeader: true,
      lastContactMillis: 120 });
  });

  it('read metadata the server did not send as unknown', async () => {
    const { fetcher } = server(() => json([]));

    expect(await createApi('/', fetcher).nodes()).toMatchObject({ answeredBy: null, consistency: null,
      knownLeader: null, lastContactMillis: null });
  });

  it('report a read ended by its signal as an abort, not a server failure', async () => {
    const fetcher = ((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      })) as typeof fetch;
    const control = new AbortController();

    const read = createApi('/', fetcher).nodes({ signal: control.signal }).catch((error: unknown) => error);
    control.abort();

    expect(await read).toBeInstanceOf(DOMException);
  });
});

describe('the configuration and event reads', () => {
  it('read the configuration with each setting source', async () => {
    const { calls, fetcher } = server(() => json(CONFIGURATION));

    const read = await createApi('/', fetcher).configuration();

    expect(calls[0]?.url).toBe('/v1/operator/configuration');
    expect(read.data.settings.find((setting) => setting.key === 'server.operator.token')).toMatchObject({
      value: null,
      redacted: true,
    });
    expect(read.data.settings.find((setting) => setting.key === 'server.raft.nodes')?.value).toEqual({
      'server-1': '10.0.0.1:9080',
    });
  });

  it('refuse a configuration answer that is not the expected shape', async () => {
    const { fetcher } = server(() => json({ ...CONFIGURATION, settings: [{ key: 'a', source: 'guessed' }] }));

    await expect(createApi('/', fetcher).configuration()).rejects.toBeInstanceOf(MalformedResponseError);
  });

  it('query the events after a cursor, in the filters given', async () => {
    const { calls, fetcher } = server(() => json(EVENT_PAGE));
    const api = createApi('/', fetcher);

    const page = await api.events({ after: cursor(0), limit: 1000, category: 'domain' });
    await api.events({ service: 'pay v2', tenant: 'acme', namespace: 'prod' });

    expect(calls.map((call) => call.url)).toEqual([
      `/v1/operator/events?after=${encodeURIComponent(cursor(0))}&limit=1000&category=domain`,
      '/v1/operator/events?tenant=acme&namespace=prod&service=pay+v2',
    ]);
    expect(page.data.events.map((event) => event.type)).toEqual(['raft.leadership_gained', 'service.registered']);
    expect(page.data.events[1]?.committedIndex).toBe(7);
    expect(page.data.events[0]?.tenant).toBeUndefined();
    expect(page.data).toMatchObject({ next: cursor(2), status: 'complete', capacity: 4096 });
  });
});

describe('the event watch', () => {
  it('delivers each event as it arrives, whatever pieces the stream comes in, and its end', async () => {
    const body = stream();
    const { calls, fetcher } = server(() => body.response());
    const events: QraftEvent[] = [];
    const ends: WatchEnd[] = [];

    createApi('/', fetcher).watchEvents({ after: cursor(0), category: 'domain' }, {
      onEvent: (event) => events.push(event),
      onEnd: (end) => ends.push(end),
      onError: (error) => {
        throw error;
      },
    });
    body.write(': watching\n\n');
    body.write(`id: ${cursor(1)}\nevent: raft.leadership_gained\ndata: ${JSON.stringify(SYSTEM_EVENT)}\n\n`);
    const second = `id: ${cursor(2)}\nevent: service.registered\ndata: ${JSON.stringify(SERVICE_EVENT)}\n\n`;
    body.write(second.slice(0, 30));
    await settle();
    expect(events.map((event) => event.cursor)).toEqual([cursor(1)]);
    body.write(second.slice(30));
    body.write(':\n\n');
    body.write(`event: end\ndata: ${JSON.stringify({ reason: 'history_gap', resumeAfter: cursor(9) })}\n\n`);
    await settle();

    expect(calls[0]?.url).toBe(`/v1/operator/events/watch?after=${encodeURIComponent(cursor(0))}&category=domain`);
    expect(new Headers(calls[0]?.init?.headers).get('Accept')).toBe('text/event-stream');
    expect(events.map((event) => event.cursor)).toEqual([cursor(1), cursor(2)]);
    expect(ends).toEqual([{ reason: 'history_gap', resumeAfter: cursor(9) }]);
  });

  it('reports a stream that closes without its end, and a refusal, as errors', async () => {
    const body = stream();
    const { fetcher } = server((url) =>
      url.includes('category=audit') ? json({ code: 'permission_denied', message: 'No', retryable: false }, 403)
        : body.response(),
    );
    const errors: unknown[] = [];
    const api = createApi('/', fetcher);
    const handlers = { onEvent: () => undefined, onEnd: () => undefined, onError: (error: unknown) => errors.push(error) };

    api.watchEvents({}, handlers);
    body.write(': watching\n\n');
    body.end();
    api.watchEvents({ category: 'audit' }, handlers);
    await settle();

    expect(errors).toHaveLength(2);
    expect(errors.some((error) => error instanceof ApiError && error.code === 'permission_denied')).toBe(true);
    expect(errors.some((error) => error instanceof ApiError && error.code === 'watch_closed')).toBe(true);
  });

  it('says nothing more once it is closed', async () => {
    const body = stream();
    const { calls, fetcher } = server(() => body.response());
    const seen: string[] = [];

    const close = createApi('/', fetcher).watchEvents({}, {
      onEvent: () => seen.push('event'),
      onEnd: () => seen.push('end'),
      onError: () => seen.push('error'),
    });
    await settle();
    close();
    await settle();

    expect(calls[0]?.init?.signal?.aborted).toBe(true);
    expect(seen).toEqual([]);
  });
});
