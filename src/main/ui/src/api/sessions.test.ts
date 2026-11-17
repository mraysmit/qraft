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
import { createApi, MalformedResponseError } from './client';
import { durationMillis, endReason, heldBy, lockDelayText, timeLeftText } from './sessions';

// UI-6 Sessions, Locks and Watches task list, Phase 1: the sessions and locks reads, the session filter on events, and
// how the interface reads Consul's durations.

interface Call {
  url: string;
  init: RequestInit | undefined;
}

function server(answer: (url: string) => Response) {
  const calls: Call[] = [];
  const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return Promise.resolve(answer(url));
  };
  return { calls, fetcher: fetcher as typeof fetch };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

const SESSION = {
  ID: '4f3c2a10-0000-4000-8000-000000000001', Name: 'worker', Node: 'node-a', LockDelay: 15_000_000_000,
  Behavior: 'release', TTL: '30s', CreateIndex: 40, ModifyIndex: 40,
  RenewedAt: null, ExpiresAt: '2026-11-17T12:01:00Z',
};

const HELD = { Tenant: 'acme', Namespace: 'prod', Key: 'app/leader', Session: SESSION.ID, LockIndex: 2,
  CreateIndex: 41, ModifyIndex: 43 };

describe('the sessions read', () => {
  it('reads every session with the leader timers, in the consistency and blocking query asked', async () => {
    const { calls, fetcher } = server(() => json({ Sessions: [SESSION], TtlKnown: true }, 200,
      { 'X-Qraft-Index': '44', 'X-Qraft-Results-Filtered-By-ACLs': 'true' }));

    const sessions = await createApi('/', fetcher).sessions({ consistency: 'consistent', index: 43, wait: '30s' });

    expect(calls[0]?.url).toBe('/v1/internal/ui/sessions?consistent&index=43&wait=30s');
    expect(sessions.data.TtlKnown).toBe(true);
    expect(sessions.data.Sessions[0]).toMatchObject({ ID: SESSION.ID, Behavior: 'release', ExpiresAt: SESSION.ExpiresAt });
    expect(sessions.index).toBe(44);
    expect(sessions.filtered).toBe(true);
  });

  it('refuses an answer not in the form it reads', async () => {
    const { fetcher } = server(() => json({ Sessions: [{ ...SESSION, LockDelay: '15s' }], TtlKnown: true }));

    await expect(createApi('/', fetcher).sessions()).rejects.toBeInstanceOf(MalformedResponseError);
  });
});

describe('the locks read', () => {
  it('reads the held keys and running delays, of one session when asked', async () => {
    const delay = { Tenant: 'default', Namespace: 'default', Key: 'jobs/a', Until: '2026-11-17T12:00:15Z' };
    const { calls, fetcher } = server(() => json({ Held: [HELD], Delays: [delay] }, 200, { 'X-Qraft-Index': '43' }));
    const api = createApi('/', fetcher);

    const locks = await api.locks({ session: SESSION.ID }, { consistency: 'stale', index: 42, wait: '30s' });
    await api.locks();

    expect(calls.map((call) => call.url)).toEqual([
      `/v1/internal/ui/locks?session=${SESSION.ID}&stale&index=42&wait=30s`,
      '/v1/internal/ui/locks',
    ]);
    expect(locks.data.Held).toEqual([HELD]);
    expect(locks.data.Delays).toEqual([delay]);
    expect(locks.index).toBe(43);
  });

  it('counts the keys each session holds', () => {
    expect(heldBy([HELD, { ...HELD, Key: 'other' }, { ...HELD, Session: 'another' }])).toEqual(
      new Map([[SESSION.ID, 2], ['another', 1]]));
  });
});

describe('the event query', () => {
  it("reads a session's events with the session filter", async () => {
    const page = { events: [], next: 'b:0', status: 'complete', capacity: 4096 };
    const { calls, fetcher } = server(() => json(page));

    await createApi('/', fetcher).events({ limit: 1000, session: SESSION.ID });

    expect(calls[0]?.url).toBe(`/v1/operator/events?limit=1000&session=${SESSION.ID}`);
  });

  it('says why a session ended, from its event', () => {
    expect(endReason('destroyed')).toBe('It was destroyed.');
    expect(endReason('ttl')).toBe('Its TTL lapsed without a renewal.');
    expect(endReason('node')).toBe('Its node failed or left the catalog.');
    expect(endReason('other')).toBe('It ended: other.');
  });
});

describe("Consul's durations", () => {
  it('reads a Go duration, as a TTL is written', () => {
    expect(durationMillis('30s')).toBe(30_000);
    expect(durationMillis('1m30s')).toBe(90_000);
    expect(durationMillis('24h0m0s')).toBe(86_400_000);
    expect(durationMillis('1.5s')).toBe(1500);
    expect(durationMillis('250ms')).toBe(250);
    expect(durationMillis('')).toBeNull();
    expect(durationMillis('soon')).toBeNull();
    expect(durationMillis('10')).toBeNull();
  });

  it('writes a lock delay, which the server answers in nanoseconds', () => {
    expect(lockDelayText(15_000_000_000)).toBe('15 s');
    expect(lockDelayText(500_000_000)).toBe('0.5 s');
    expect(lockDelayText(0)).toBe('None');
  });

  it('says how long a session has left', () => {
    expect(timeLeftText(65_000)).toBe('1 min 5 s');
    expect(timeLeftText(9_400)).toBe('9 s');
    expect(timeLeftText(0)).toBe('Due to lapse now');
    expect(timeLeftText(-3_000)).toBe('Due to lapse now');
  });
});

describe('the session writes', () => {
  // Phase 2: creating, renewing and destroying a session (decisions 7 and 8).
  it('create a session with its fields as JSON, and read the ID it is given', async () => {
    const { calls, fetcher } = server(() => json({ ID: SESSION.ID }));

    const created = await createApi('/', fetcher).createSession({ Node: 'node-a', Name: 'job', TTL: '30s',
      LockDelay: '5s', Behavior: 'delete' });

    expect(created.data).toEqual({ ID: SESSION.ID });
    expect(calls[0]?.url).toBe('/v1/session/create');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({ Node: 'node-a', Name: 'job', TTL: '30s',
      LockDelay: '5s', Behavior: 'delete' });
  });

  it('renew and destroy a session by its ID', async () => {
    const { ExpiresAt: _expires, RenewedAt: _renewed, ...consul } = SESSION;
    const { calls, fetcher } = server((url) => json(url.includes('renew') ? [consul] : true));
    const api = createApi('/', fetcher);

    const renewed = await api.renewSession(SESSION.ID);
    const destroyed = await api.destroySession(SESSION.ID);

    expect(renewed.data[0]?.ID).toBe(SESSION.ID);
    expect(destroyed.data).toBe(true);
    expect(calls.map((call) => [call.init?.method, call.url])).toEqual([
      ['PUT', `/v1/session/renew/${SESSION.ID}`],
      ['PUT', `/v1/session/destroy/${SESSION.ID}`],
    ]);
  });

  it('say a creation that was sent and never answered has an unknown outcome', async () => {
    const fetcher = (() => Promise.reject(new TypeError('network'))) as typeof fetch;

    await expect(createApi('/', fetcher).createSession({ Node: 'node-a', Name: '', TTL: '', LockDelay: '15s',
      Behavior: 'release' })).rejects.toMatchObject({ code: 'outcome_unknown' });
  });
});

describe('acquiring and releasing a key', () => {
  // Phase 3: a key acquired and released through a session (decision 9).
  it("send the key's value with its flags and the session, and read true or false", async () => {
    const { calls, fetcher } = server((url) => json(url.includes('acquire')));
    const api = createApi('/', fetcher);
    const scope = { tenant: 'acme', namespace: 'prod' };

    const acquired = await api.kvAcquire('app/a b', scope, 'v', { flags: 7, session: SESSION.ID });
    const released = await api.kvRelease('app/a b', scope, 'v', { flags: 7, session: SESSION.ID });

    expect(acquired.data).toBe(true);
    expect(released.data).toBe(false);
    expect(calls.map((call) => [call.init?.method, call.url, call.init?.body])).toEqual([
      ['PUT', `/v1/kv/app/a%20b?flags=7&acquire=${SESSION.ID}`, 'v'],
      ['PUT', `/v1/kv/app/a%20b?flags=7&release=${SESSION.ID}`, 'v'],
    ]);
    expect((calls[0]?.init?.headers as Record<string, string>)['X-Qraft-Tenant']).toBe('acme');
  });

  it('read the events of one type, as the recent invalidations are', async () => {
    const { calls, fetcher } = server(() => json({ events: [], next: 'b:0', status: 'complete', capacity: 4096 }));

    await createApi('/', fetcher).events({ limit: 1000, type: 'session.destroyed' });

    expect(calls[0]?.url).toBe('/v1/operator/events?limit=1000&type=session.destroyed');
  });
});
