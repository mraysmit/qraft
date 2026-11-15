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

import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { Root } from '../Root';
import type { AuthorizationCheck } from '../api/client';
import { ACL_STATUS } from '../api/fixtures';
import { AclStatusSchema } from '../api/schemas';
import { BOOTSTRAP, fakeApi, memorySession, read, recordingApi } from './testing';

// UI-5 task list, Phase 1: navigation shaped by what the token may do (decision 3), and the Access section's Status
// page (UI/UX design sections 3.5 and 10.2.1).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

/** An authorize answer granting only `granted`, each as `resource:access`. */
function granting(...granted: string[]) {
  return (checks: AuthorizationCheck[]) =>
    read(checks.map((check) => ({ ...check, allow: granted.includes(`${check.resource}:${check.access}`) })), null);
}

/** The interface's least page beside the shell, an address that is no view: tables are slow to mount in jsdom. */
const opened = () => screen.findByText('No such view', { selector: 'h2' });

const nav = () => Array.from(document.querySelectorAll('nav[aria-label="Primary"] a')).map((link) => link.textContent);

describe('navigation', () => {
  it('offers only the views the token may read, and leaves the status strip unread without operator:read', async () => {
    at('/ui/nowhere');
    const { api, calls } = recordingApi(fakeApi({ authorize: granting() }));

    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    await opened();
    expect(nav()).toEqual(['Services', 'Nodes and Clients', 'Health Checks']);
    expect(screen.getByText("The cluster's status needs operator:read.")).toBeInTheDocument();
    expect(calls).not.toContain('raftStatus');
  });

  it('offers the operator views with operator:read, and Access with acl:read', async () => {
    at('/ui/nowhere');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ authorize: granting('operator:read', 'acl:read') })}
      session={memorySession('held')} />);

    await opened();
    expect(nav()).toEqual(['Services', 'Nodes and Clients', 'Health Checks', 'Cluster Overview', 'Raft Members',
      'Storage & Snapshots', 'Events', 'Configuration', 'Access']);
    expect(screen.getByText('Access', { selector: 'nav a' }).getAttribute('href')).toBe('/ui/access');
  });

  it('says which permission a view needs when its address is opened without it, and reads nothing for it', async () => {
    at('/ui/members');
    const { api, calls } = recordingApi(fakeApi({ authorize: granting() }));

    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    expect(await screen.findByText('Your token may not read Raft Members: it needs operator:read.')).toBeInTheDocument();
    expect(calls).not.toContain('members');
  });

  it('lists the client rows of Nodes and Clients without operator:read, and says the server rows need it', async () => {
    at('/ui/nodes');
    const { api, calls } = recordingApi(fakeApi({ authorize: granting() }));

    render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);

    expect(await screen.findByText('client-a')).toBeInTheDocument();
    expect(screen.getByText('The servers are listed with operator:read.')).toBeInTheDocument();
    expect(calls).not.toContain('members');
  });
});

describe('the Status page', () => {
  it('answers whether access control can be trusted, from the security status', async () => {
    at('/ui/access');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ authorize: granting('acl:read') })}
      session={memorySession('held')} />);

    expect(await screen.findByText('Access control', { selector: 'h2' })).toBeInTheDocument();
    const page = await screen.findByLabelText('Security status');
    expect(page.textContent).toContain('Security profile: production');
    expect(page.textContent).toContain('ACLs enabled, default policy deny');
    expect(page.textContent).toContain('This listener serves plain HTTP: tokens cross the network unencrypted.');
    expect(page.textContent).toContain(ACL_STATUS.managementTokens[1]?.accessorId);
    expect(page.textContent).toContain('Installed from the management file');
    expect(page.textContent).toContain('open-services');
    expect(page.textContent).toContain('The anonymous token may write service: every caller without a token can.');
    expect(page.textContent).toContain(
      'Only one token holds acl:write without an expiry: the next change to it would be a lock-out.');
    expect(page.textContent).toContain('ci deploy');
    expect(page.textContent).toContain('Node identity client-b');
    expect(page.textContent).toContain('Holds acl:write');
  });

  it('names HTTPS and a required client certificate, and gives no warning for several writers', async () => {
    at('/ui/access');
    const status = AclStatusSchema.parse({
      ...ACL_STATUS,
      transport: { https: true, clientCertificateRequired: true },
      aclWriters: [...ACL_STATUS.aclWriters, { accessorId: 'f0e1d2c3-b4a5-4697-8899-aabbccddeeff', description: 'ops' }],
      anonymous: { ...ACL_STATUS.anonymous, policies: [], writes: [] },
      expiringSoon: [],
    });
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ authorize: granting('acl:read'), aclStatus: () => read(status, null) })}
      session={memorySession('held')} />);

    const page = await screen.findByLabelText('Security status');
    expect(page.textContent).toContain('HTTPS, client certificate required');
    expect(page.textContent).toContain('2 tokens hold acl:write without an expiry');
    expect(page.textContent).not.toContain('lock-out');
    expect(page.textContent).toContain('The anonymous token holds no policy.');
    expect(page.textContent).toContain('No token expires within 60 minutes.');
  });

  it('says there is no access control on a server without it, reading nothing', async () => {
    at('/ui/access');
    const { api, calls } = recordingApi(fakeApi());
    render(<Root bootstrap={{ ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' }} api={api}
      session={memorySession()} />);

    const page = await screen.findByLabelText('Security status');
    expect(page.textContent).toContain('Security profile: development');
    expect(page.textContent).toContain('No access control: anyone who can reach this API can change the cluster.');
    expect(calls).not.toContain('aclStatus');
  });

  it('offers only the Status page of the Access section without access control, the token pages saying why', async () => {
    const bootstrap = { ...BOOTSTRAP, authMode: 'none' as const, securityProfile: 'development' as const };
    for (const path of ['/ui/access', '/ui/access/tokens', '/ui/access/policies/x', '/ui/access/explain']) {
      at(path);
      const { api, calls } = recordingApi(fakeApi());
      const { unmount } = render(<Root bootstrap={bootstrap} api={api} session={memorySession()} />);

      await screen.findByText('Access control', { selector: 'h2' });
      const links = Array.from(document.querySelectorAll('nav[aria-label="Access pages"] a')).map((link) => link.textContent);
      expect(links, path).toEqual(['Status']);
      if (path !== '/ui/access') {
        expect(screen.getByText('This server has no access control, so it has no tokens or policies to show.')).toBeInTheDocument();
      }
      expect(calls.filter((call) => ['tokens', 'token', 'policies', 'policy', 'explain', 'aclStatus'].includes(call)), path)
        .toEqual([]);
      unmount();
    }
  });
});
