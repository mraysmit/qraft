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
import { ApiError, type AuthorizationCheck, type QraftApi, type TokenBody } from '../api/client';
import { ACL_STATUS, ANONYMOUS_TOKEN, FILE_TOKEN, MANAGEMENT_TOKEN, POLICY, TOKEN } from '../api/fixtures';
import { AclStatusSchema, TokenSchema } from '../api/schemas';
import { BOOTSTRAP, CLONE_SECRET, CREATED_SECRET, fakeApi, memorySession, read } from './testing';

// UI-5 task list, Phase 2: Tokens (UI/UX design section 10.2.3): the list with its marks and filters, the detail with
// its links, create with the one-time secret, update, delete, which is revocation, and replacement.

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/access/tokens'));

const granting = (...granted: string[]) => (checks: AuthorizationCheck[]) =>
  read(checks.map((check) => ({ ...check, allow: granted.includes(`${check.resource}:${check.access}`) })), null);

/** The operator's own token: signed in with it, every token of the list can be changed. */
const OPERATOR = TokenSchema.parse({ ...TOKEN, accessorId: 'b0b00000-0000-4000-8000-000000000001', description: 'operator',
  nodeIdentities: [], serviceIdentities: [] });

const writer = (overrides: Partial<QraftApi> = {}) => fakeApi({
  authorize: granting('acl:read', 'acl:write'),
  self: () => read(OPERATOR, null),
  ...overrides,
});

function show(api: QraftApi, secret = 'held') {
  render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession(secret)} />);
}

const button = (text: string) => screen.getByText(text, { selector: 'button, button span' });
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const rows = () => Array.from(document.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

describe('the token list', () => {
  it('lists every token by accessor ID with its policies, identities and expiry, marking the special ones', async () => {
    show(writer());

    await screen.findByText(TOKEN.accessorId, { selector: 'a' });
    const listed = rows();
    expect(listed).toHaveLength(4);
    expect(listed[0]).toContain('web deployer');
    expect(listed[0]).toContain('web-writer');
    expect(listed[0]).toContain('Node client-a');
    expect(listed[0]).toContain('Service web in default/default');
    expect(listed[0]).toContain('Does not expire');
    expect(listed.find((row) => row.includes(MANAGEMENT_TOKEN.accessorId))).toContain('Management token');
    expect(listed.find((row) => row.includes(FILE_TOKEN.accessorId))).toContain('Installed from the management file');
    expect(listed.find((row) => row.includes(ANONYMOUS_TOKEN.accessorId))).toContain('Anonymous token');
    expect(document.body.textContent).not.toContain('<hidden>');
    expect(screen.getByText(TOKEN.accessorId, { selector: 'a' }).getAttribute('href'))
      .toBe(`/ui/access/tokens/${TOKEN.accessorId}`);
  });

  it('narrows the list by policy, by identity and to those expiring soon', async () => {
    const soon = TokenSchema.parse({ ...TOKEN, accessorId: '1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5e', description: 'ci deploy',
      policies: [], nodeIdentities: [{ nodeName: 'client-b' }], serviceIdentities: [],
      expirationTime: new Date(Date.now() + 20 * 60_000).toISOString() });
    show(writer({ tokens: () => read([TokenSchema.parse(TOKEN), TokenSchema.parse(MANAGEMENT_TOKEN), soon], null) }));
    await screen.findByText(TOKEN.accessorId, { selector: 'a' });

    type('Policy', POLICY.id);
    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toContain('web deployer');
    type('Policy', '');
    type('Identity', 'client-b');
    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toContain('ci deploy');
    type('Identity', '');
    fireEvent.click(screen.getByLabelText('Expiring soon'));
    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toContain('ci deploy');
  });

  it('offers no change to a token without acl:write', async () => {
    show(fakeApi({ authorize: granting('acl:read') }));

    await screen.findByText(TOKEN.accessorId, { selector: 'a' });
    expect(screen.queryByText('Create token', { selector: 'button, button span' })).toBeNull();
  });
});

describe('creating a token', () => {
  it('reviews the token, creates it once, and shows its secret only until the panel closes', async () => {
    const bodies: TokenBody[] = [];
    const base = writer();
    show(writer({ createToken: (body) => {
      bodies.push(body);
      return base.createToken(body);
    } }));
    fireEvent.click(await screen.findByText('Create token', { selector: 'button, button span' }));

    type('Description', 'ci deploy');
    fireEvent.click(screen.getByLabelText('Policy web-writer'));
    type('Node identities', 'client-b');
    type('Service identities', 'billing@acme/prod, web');
    type('Time to live', '30m');
    fireEvent.click(button('Review'));

    const review = await screen.findByLabelText('Create token');
    expect(review.textContent).toContain('Policies: web-writer');
    expect(review.textContent).toContain('Node identities: client-b');
    expect(review.textContent).toContain('Service identities: billing in acme/prod, web in default/default');
    expect(review.textContent).toContain('Expires 30m after it is created');
    expect(review.textContent).toContain('Creating a token is not idempotent');
    fireEvent.click(button('Create'));

    const secret = await screen.findByLabelText('New secret');
    expect(secret.textContent).toContain(CREATED_SECRET);
    expect(secret.textContent).toContain('This secret cannot be shown again. Copy it now: closing this panel clears it.');
    expect(bodies).toEqual([{
      description: 'ci deploy',
      policies: [{ id: POLICY.id }],
      nodeIdentities: [{ nodeName: 'client-b' }],
      serviceIdentities: [{ serviceName: 'billing', tenant: 'acme', namespace: 'prod' },
        { serviceName: 'web', tenant: 'default', namespace: 'default' }],
      expirationTtl: '30m',
    }]);
    fireEvent.click(button('Close'));

    await waitFor(() => expect(document.body.textContent).not.toContain(CREATED_SECRET));
  });

  it('settles an unknown outcome by finding the tokens with its description, and never creates again by itself', async () => {
    let sent = 0;
    const found = TokenSchema.parse({ ...TOKEN, accessorId: 'f0e1d2c3-b4a5-4697-8899-aabbccddeeff', description: 'ci deploy' });
    show(writer({
      createToken: () => {
        sent += 1;
        return Promise.reject(new ApiError('/v1/acl/token', 0, 'outcome_unknown', 'no answer', false, null, null));
      },
      tokens: () => read([TokenSchema.parse(TOKEN), found], null),
    }));
    fireEvent.click(await screen.findByText('Create token', { selector: 'button, button span' }));
    type('Description', 'ci deploy');
    fireEvent.click(button('Review'));
    fireEvent.click(await screen.findByText('Create', { selector: 'button, button span' }));

    await screen.findByText(/Outcome unknown/);
    fireEvent.click(button('Read the current state'));

    expect(await screen.findByText(/1 token has the description ci deploy: it may be the one just created/)).toBeInTheDocument();
    expect(screen.getByText(found.accessorId, { selector: '[aria-label="Outcome"] a' }).getAttribute('href'))
      .toBe(`/ui/access/tokens/${found.accessorId}`);
    expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
    expect(sent).toBe(1);
  });
});

describe('a token', () => {
  it('shows its links to its policies, the node of a node identity and the service of a service identity', async () => {
    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    const scoped = TokenSchema.parse({ ...TOKEN, serviceIdentities: [{ serviceName: 'billing', tenant: 'acme', namespace: 'prod' }] });
    show(writer({ token: () => read(scoped, null) }));

    expect(await screen.findByText('web-writer', { selector: 'a' })).toHaveAttribute('href', `/ui/access/policies/${POLICY.id}`);
    expect(screen.getByText('client-a', { selector: 'a' })).toHaveAttribute('href', '/ui/nodes');
    expect(screen.getByText('billing in acme/prod', { selector: 'a' }))
      .toHaveAttribute('href', '/ui/services/billing?tenant=acme&namespace=prod');
  });

  it('reviews an update by the policies and identities it adds and removes', async () => {
    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    const bodies: [string, TokenBody][] = [];
    const base = writer();
    show(writer({ updateToken: (accessorId, body) => {
      bodies.push([accessorId, body]);
      return base.updateToken(accessorId, body);
    } }));
    fireEvent.click(await screen.findByText('Edit', { selector: 'button, button span' }));

    fireEvent.click(screen.getByLabelText('Policy web-writer'));
    fireEvent.click(screen.getByLabelText('Policy builtin/global-read-only'));
    type('Node identities', 'client-a, client-b');
    fireEvent.click(button('Review'));

    const review = await screen.findByLabelText('Update token');
    expect(review.textContent).toContain('Policies added: builtin/global-read-only');
    expect(review.textContent).toContain('Policies removed: web-writer');
    expect(review.textContent).toContain('Node identities added: client-b');
    expect(screen.queryByLabelText('Time to live'), 'an expiry cannot change').toBeNull();
    fireEvent.click(button('Save'));

    expect(await screen.findByText(/Committed and applied at index 51/)).toBeInTheDocument();
    expect(bodies[0]?.[0]).toBe(TOKEN.accessorId);
    expect(bodies[0]?.[1].policies).toEqual([{ id: '00000000-0000-0000-0000-000000000002' }]);
    expect(bodies[0]?.[1].nodeIdentities).toEqual([{ nodeName: 'client-a' }, { nodeName: 'client-b' }]);
  });

  it('reviews a deletion by what depends on the token, and what revocation does', async () => {
    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    let deleted = '';
    show(writer({ deleteToken: (accessorId) => {
      deleted = accessorId;
      return read(true, 53);
    } }));
    fireEvent.click(await screen.findByText('Delete', { selector: 'button, button span' }));

    const review = await screen.findByLabelText('Delete token');
    expect(review.textContent).toContain('The client of node client-a becomes not ready, and its node unreachable after the node TTL.');
    expect(review.textContent).toContain('Revocation takes effect on the leader at once');
    expect(review.textContent).toContain('a follower may still accept the token for reads');
    expect(review.textContent).toContain('until its cache entry expires, or for as long as it reaches no server');
    fireEvent.click(button('Delete'));

    expect(await screen.findByText(/Committed and applied at index 53/)).toBeInTheDocument();
    expect(deleted).toBe(TOKEN.accessorId);
  });

  it("warns that deleting the only token holding acl:write without an expiry is a lock-out the server refuses", async () => {
    at(`/ui/access/tokens/${MANAGEMENT_TOKEN.accessorId}`);
    show(writer(), 'other');
    fireEvent.click(await screen.findByText('Delete', { selector: 'button, button span' }));

    expect((await screen.findByLabelText('Delete token')).textContent).toContain(
      'This is the only token holding acl:write without an expiry: the server will refuse its deletion (acl_lockout).');
  });

  it('gives the revocation order of a management token installed from the file', async () => {
    at(`/ui/access/tokens/${FILE_TOKEN.accessorId}`);
    const status = AclStatusSchema.parse({ ...ACL_STATUS, aclWriters: [
      { accessorId: MANAGEMENT_TOKEN.accessorId, description: null }, { accessorId: FILE_TOKEN.accessorId, description: null }] });
    show(writer({ aclStatus: () => read(status, null) }));
    fireEvent.click(await screen.findByText('Delete', { selector: 'button, button span' }));

    const review = (await screen.findByLabelText('Delete token')).textContent;
    expect(review).toContain("It returns at the next leadership change while any server's file holds its secret.");
    expect(review).toContain('Replace the secret in every server\'s file, restart the leader so the new token is installed, '
      + 'check it exists, then delete the old one.');
    expect(review).toContain('1 other token holds acl:write without an expiry.');
  });

  it('offers no deletion of the anonymous token, or of the token in use', async () => {
    at(`/ui/access/tokens/${ANONYMOUS_TOKEN.accessorId}`);
    const { unmount } = render(<Root bootstrap={BOOTSTRAP} api={writer()} session={memorySession('held')} />);
    expect(await screen.findByText('The anonymous token cannot be deleted. Its policies can be changed: every caller without a token gets them.'))
      .toBeInTheDocument();
    expect(screen.queryByText('Delete', { selector: 'button, button span' })).toBeNull();
    unmount();

    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    show(writer({ self: () => read(TokenSchema.parse(TOKEN), null) }));
    expect(await screen.findByText('This is the token in use: sign in with another token to delete it.')).toBeInTheDocument();
    expect(screen.queryByText('Delete', { selector: 'button, button span' })).toBeNull();
  });

  it('replaces a token by cloning it, showing the new secret once, then deleting the old one', async () => {
    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    const asked: string[] = [];
    show(writer({
      cloneToken: (accessorId, description) => {
        asked.push(`clone ${accessorId} ${description}`);
        return read(TokenSchema.parse({ ...TOKEN, accessorId: 'c1011e00-0000-4000-8000-000000000001', secretId: CLONE_SECRET }), 52);
      },
      deleteToken: (accessorId) => {
        asked.push(`delete ${accessorId}`);
        return read(true, 53);
      },
    }), 'other');
    fireEvent.click(await screen.findByText('Replace', { selector: 'button, button span' }));

    const review = await screen.findByLabelText('Replace token');
    expect(review.textContent).toContain('1. Clone this token: the clone has its policies, identities and expiry, and a new secret.');
    expect(review.textContent).toContain("2. Move this token's users to the clone's secret.");
    expect(review.textContent).toContain('3. Delete this token.');
    fireEvent.click(button('Clone'));

    expect((await screen.findByLabelText('New secret')).textContent).toContain(CLONE_SECRET);
    fireEvent.click(button("Delete this token, once its users have the clone's secret"));
    fireEvent.click(await screen.findByText('Delete', { selector: '[aria-label="Delete token"] button span' }));

    await waitFor(() => expect(asked).toEqual([`clone ${TOKEN.accessorId} Replacement of web deployer`, `delete ${TOKEN.accessorId}`]));
  });
});
