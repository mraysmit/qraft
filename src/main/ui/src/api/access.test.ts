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
import { ApiError, createApi, type Credentials } from './client';
import { ACL_STATUS, AUTHORIZATIONS, MANAGEMENT_TOKEN, TOKEN } from './fixtures';

// UI-5 task list, Phase 1: every request presents the session's token, a token the server no longer knows ends the
// session, and the reads sign-in, the token menu, navigation and the Status page make.

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

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function refused(code: string, status = 403): Response {
  return json({ code, message: `refused: ${code}`, retryable: false, requestId: 'req-9' }, status);
}

/** Credentials holding `secret`, recording each refusal that ended the session. */
function credentials(secret: string | null) {
  const ended: ApiError[] = [];
  const held: Credentials = {
    secret: () => secret,
    onTokenNotFound: (error) => ended.push(error),
  };
  return { held, ended };
}

const headerOf = (call: Call | undefined, name: string): string | null =>
  new Headers(call?.init?.headers).get(name);

describe('the session token', () => {
  it('is presented on every read and on the event watch', async () => {
    const { calls, fetcher } = server((url) => (url.includes('watch') ? new Response(null, { status: 503 }) : json([])));
    const api = createApi('/', fetcher, credentials('secret-of-the-session').held);

    await api.nodes();
    await api.tenants();
    const close = api.watchEvents({}, { onEvent: () => undefined, onEnd: () => undefined, onError: () => undefined });
    await Promise.resolve();
    close();

    expect(calls).toHaveLength(3);
    for (const call of calls) expect(headerOf(call, 'X-Qraft-Token'), call.url).toBe('secret-of-the-session');
  });

  it('is not presented without one', async () => {
    const { calls, fetcher } = server(() => json([]));

    await createApi('/', fetcher, credentials(null).held).nodes();
    await createApi('/', fetcher).nodes();

    expect(calls.map((call) => headerOf(call, 'X-Qraft-Token'))).toEqual([null, null]);
  });

  it('ends the session when the server no longer knows it, and only then', async () => {
    const answers = [refused('permission_denied'), refused('token_not_found')];
    const { fetcher } = server(() => answers.shift() as Response);
    const { held, ended } = credentials('revoked-secret');
    const api = createApi('/', fetcher, held);

    const denied = await api.nodes().catch((error: unknown) => error);
    expect(denied).toBeInstanceOf(ApiError);
    expect((denied as ApiError).code).toBe('permission_denied');
    expect(ended, 'a missing permission is the outcome of one request').toEqual([]);

    const unknown = await api.nodes().catch((error: unknown) => error);
    expect((unknown as ApiError).code).toBe('token_not_found');
    expect(ended).toHaveLength(1);
    expect(ended[0]?.requestId).toBe('req-9');
  });
});

describe('the access reads', () => {
  it('reads the token in use, or a secret being signed in with in its place', async () => {
    const { calls, fetcher } = server(() => json(TOKEN));
    const api = createApi('/', fetcher, credentials('session-secret').held);

    const self = await api.self();
    const candidate = await api.self({ secret: 'typed-secret' });

    expect(calls.map((call) => call.url)).toEqual(['/v1/acl/token/self', '/v1/acl/token/self']);
    expect(headerOf(calls[0], 'X-Qraft-Token')).toBe('session-secret');
    expect(headerOf(calls[1], 'X-Qraft-Token')).toBe('typed-secret');
    expect(self.data.accessorId).toBe(TOKEN.accessorId);
    expect(self.data.serviceIdentities[0]?.serviceName).toBe('web');
    expect(candidate.data.expirationTime).toBeNull();
  });

  it('does not end the session when a secret being signed in with is unknown', async () => {
    const { fetcher } = server(() => refused('token_not_found'));
    const { held, ended } = credentials(null);

    const error = await createApi('/', fetcher, held).self({ secret: 'mistyped' }).catch((failure: unknown) => failure);

    expect((error as ApiError).code).toBe('token_not_found');
    expect(ended, 'no session was signed in with it').toEqual([]);
  });

  it('asks what the token in use may do, in one call', async () => {
    const { calls, fetcher } = server(() => json(AUTHORIZATIONS));
    const api = createApi('/', fetcher);

    const answer = await api.authorize([
      { resource: 'operator', access: 'read' },
      { resource: 'operator', access: 'write' },
      { resource: 'acl', access: 'read' },
      { resource: 'acl', access: 'write' },
    ]);

    expect(calls[0]?.url).toBe('/v1/internal/acl/authorize');
    expect(calls[0]?.init?.method).toBe('POST');
    expect(headerOf(calls[0], 'Content-Type')).toBe('application/json');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual([
      { resource: 'operator', access: 'read' },
      { resource: 'operator', access: 'write' },
      { resource: 'acl', access: 'read' },
      { resource: 'acl', access: 'write' },
    ]);
    expect(answer.data.map((check) => check.allow)).toEqual([true, false, true, false]);
  });

  it('reads the security status', async () => {
    const { calls, fetcher } = server(() => json(ACL_STATUS));

    const status = await createApi('/', fetcher).aclStatus();

    expect(calls[0]?.url).toBe('/v1/internal/ui/acl/status');
    expect(status.data.managementTokens.map((token) => token.installedFromFile)).toEqual([false, true]);
    expect(status.data.anonymous?.writes).toEqual(['service']);
    expect(status.data.expiringSoon[0]?.holdsAclWrite).toBe(true);
    expect(status.data.transport.https).toBe(false);
  });

  it('refuses a token answer that is not in the expected form', async () => {
    const { fetcher } = server(() => json({ ...MANAGEMENT_TOKEN, policies: 'global-management' }));

    await expect(createApi('/', fetcher).self()).rejects.toThrow(/not in the expected form/);
  });
});
