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

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Root } from '../Root';
import { ApiError, type QraftApi } from '../api/client';
import { MANAGEMENT_TOKEN, RAFT_STATUS, TOKEN } from '../api/fixtures';
import { TokenSchema } from '../api/schemas';
import { BOOTSTRAP, fakeApi, memorySession, read, recordingApi } from './testing';

// UI-5 task list, Phase 1: sign-in before any API call, the session in the tab, its end when the server no longer
// knows the token, the token menu, and the management-token banner (UI/UX design sections 3.7, 10.2.1 and 10.2.2).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

/** The interface's least page, beside the shell: an address that is no view. Tables are slow to render in jsdom. */
const opened = () => screen.findByText('No such view', { selector: 'h2' });

const notFound = () => new ApiError('/v1/acl/token/self', 403, 'token_not_found', 'ACL not found', false, 'req-1', null);

async function signIn(secret: string) {
  fireEvent.change(await screen.findByLabelText('Token secret'), { target: { value: secret } });
  fireEvent.click(screen.getByText('Sign in', { selector: 'button span, button' }));
}

describe('sign-in', () => {
  it('asks for a token secret before making any API call', async () => {
    const { api, calls } = recordingApi(fakeApi());

    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession()} />);

    expect(await screen.findByText('Sign in', { selector: 'h2' })).toBeInTheDocument();
    expect(screen.getByText(/held for this tab only/)).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  it('signs in with a secret the server knows, keeps it for the tab, and opens the interface', async () => {
    const secrets: (string | undefined)[] = [];
    const { api, calls } = recordingApi(fakeApi({
      self: (options) => {
        secrets.push(options?.secret);
        return read(TokenSchema.parse(TOKEN), null);
      },
    }));
    const session = memorySession();
    at('/ui/nowhere');

    render(<Root bootstrap={BOOTSTRAP} api={api} session={session} />);
    await signIn('typed-secret');

    expect(await opened()).toBeInTheDocument();
    expect(secrets[0]).toBe('typed-secret');
    expect(calls.slice(0, 2)).toEqual(['self', 'authorize']);
    expect(session.secret()).toBe('typed-secret');
    expect(screen.getByLabelText('Token in use').textContent).toContain('web deployer');
  });

  it('says so when the server does not know the secret, and keeps nothing', async () => {
    const session = memorySession();
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ self: () => Promise.reject(notFound()) })} session={session} />);

    await signIn('mistyped');

    expect(await screen.findByText(/The server does not know this token/)).toBeInTheDocument();
    expect(session.secret()).toBeNull();
    expect(screen.getByText('Sign in', { selector: 'h2' })).toBeInTheDocument();
  });

  it('opens the interface on a reload with the secret the tab holds, once the server knows it', async () => {
    const { api, calls } = recordingApi(fakeApi());
    at('/ui/nowhere');

    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held-secret')} />);

    expect(await opened()).toBeInTheDocument();
    expect(calls[0]).toBe('self');
    expect(screen.queryByText('Sign in', { selector: 'h2' })).toBeNull();
  });

  it('ends the session when any request finds the token unknown, and asks for another', async () => {
    at('/ui/nowhere');
    const session = memorySession('revoked-secret');
    const presented: (string | null)[] = [];
    const fetcher = (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      presented.push(new Headers(init?.headers).get('X-Qraft-Token'));
      const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
      if (url.endsWith('/v1/acl/token/self')) return json(TOKEN);
      if (url.endsWith('/v1/internal/acl/authorize')) {
        const checks = JSON.parse(String(init?.body)) as object[];
        return json(checks.map((check) => ({ ...check, allow: true })));
      }
      if (url.endsWith('/raft/status')) return json(RAFT_STATUS);
      if (url.endsWith('/health/ready')) return json({ status: 'ready' });
      return json({ code: 'token_not_found', message: 'ACL not found', retryable: false, requestId: 'req-7' }, 403);
    };

    render(<Root bootstrap={BOOTSTRAP} session={session} fetcher={fetcher as typeof fetch} />);

    expect(await screen.findByText(/Your session ended: the server no longer knows the token/)).toBeInTheDocument();
    expect(screen.getByText('Sign in', { selector: 'h2' })).toBeInTheDocument();
    expect(session.secret()).toBeNull();
    expect(presented.every((secret) => secret === 'revoked-secret'), 'every request presented the session token').toBe(true);
  });

  it('signs out from the token menu, forgetting the secret', async () => {
    const session = memorySession('held-secret');
    at('/ui/nowhere');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} session={session} />);

    fireEvent.click(await screen.findByLabelText('Token in use'));
    fireEvent.click(await screen.findByText('Sign out', { selector: 'button' }));

    expect(await screen.findByText('Sign in', { selector: 'h2' })).toBeInTheDocument();
    expect(session.secret()).toBeNull();
  });

  it('needs no sign-in on a server without access control, and the token menu says so', async () => {
    at('/ui/nowhere');
    const { api, calls } = recordingApi(fakeApi());
    render(<Root bootstrap={{ ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' }} api={api}
      session={memorySession()} />);

    expect(await opened()).toBeInTheDocument();
    expect(screen.getByText('No access control', { selector: '[aria-label="Token in use"]' })).toBeInTheDocument();
    expect(calls).not.toContain('self');
    expect(calls).not.toContain('authorize');
  });
});

describe('the token menu and the management banner', () => {
  it("shows the token in use: its accessor ID, description, policies, identities and expiry", async () => {
    at('/ui/nowhere');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} session={memorySession('held-secret')} />);

    fireEvent.click(await screen.findByLabelText('Token in use'));

    const menu = await screen.findByLabelText('Token details');
    expect(menu.textContent).toContain(TOKEN.accessorId);
    expect(menu.textContent).toContain('web-writer');
    expect(menu.textContent).toContain('Node identity client-a');
    expect(menu.textContent).toContain('Service identity web in default/default');
    expect(menu.textContent).toContain('Does not expire');
  });

  it('warns when the token in use expires within the hour', async () => {
    const expiring = TokenSchema.parse({ ...TOKEN, expirationTime: new Date(Date.now() + 30 * 60_000 + 20_000).toISOString() });
    at('/ui/nowhere');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ self: () => read(expiring, null) })}
      session={memorySession('held-secret')} />);

    fireEvent.click(await screen.findByLabelText('Token in use'));

    expect(await screen.findByText('Expires in 30 minutes: sign in with another token before then.')).toBeInTheDocument();
  });

  it('shows the management-token banner on every page while it is in use, not dismissible', async () => {
    at('/ui/nowhere');
    const api: QraftApi = fakeApi({ self: () => read(TokenSchema.parse(MANAGEMENT_TOKEN), null) });
    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('management')} />);

    const banner = await screen.findByLabelText('Management token in use');
    expect(banner.textContent).toContain(
      'Signed in with the management token: unrestricted access. Use a scoped token for routine work.');
    expect(banner.querySelector('button'), 'it cannot be dismissed').toBeNull();
    // Another page with no table: Configuration's took the test to 3.6 s on Jenkins build 82, against Vitest's 5 s.
    fireEvent.click(screen.getByText('Access', { selector: 'nav a' }));
    await screen.findByText('Access control', { selector: 'h2' });
    expect(screen.getByLabelText('Management token in use')).toBeInTheDocument();
  });

  it('shows no management-token banner for another token', async () => {
    at('/ui/nowhere');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} session={memorySession('scoped')} />);

    await opened();
    await waitFor(() => expect(screen.getByLabelText('Token in use')).toBeInTheDocument());
    expect(screen.queryByLabelText('Management token in use')).toBeNull();
  });
});
