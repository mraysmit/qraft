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
import { ApiError, type AuthorizationCheck, type ExplainQuery, type PolicyBody, type QraftApi } from '../api/client';
import { EXPLANATION, MANAGEMENT_POLICY_ID, POLICY, TOKEN } from '../api/fixtures';
import { ExplanationSchema, PolicySchema, TokenSchema } from '../api/schemas';
import { BOOTSTRAP, fakeApi, memorySession, read } from './testing';

// UI-5 task list, Phase 2: Policies (UI/UX design section 10.2.4), with the tokens linked to each, the editor with its
// structured rules and precedence, the change and deletion reviews; and explanations (section 10.2.5), which the
// server decides: the interface evaluates no rule.

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/access/policies'));

const granting = (...granted: string[]) => (checks: AuthorizationCheck[]) =>
  read(checks.map((check) => ({ ...check, allow: granted.includes(`${check.resource}:${check.access}`) })), null);

const OPERATOR = TokenSchema.parse({ ...TOKEN, accessorId: 'b0b00000-0000-4000-8000-000000000001', description: 'operator' });

const writer = (overrides: Partial<QraftApi> = {}) => fakeApi({
  authorize: granting('acl:read', 'acl:write'),
  self: () => read(OPERATOR, null),
  ...overrides,
});

function show(api: QraftApi) {
  render(<Root bootstrap={BOOTSTRAP} api={api} session={memorySession('held')} />);
}

const button = (text: string) => screen.getByText(text, { selector: 'button, button span' });
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const rows = () => Array.from(document.querySelectorAll('tbody tr')).map((row) => row.textContent ?? '');

const SCOPED = PolicySchema.parse({
  ...POLICY,
  rules: {
    node_prefix: { '': { policy: 'read' } },
    tenant: { acme: { namespace_prefix: { 'team-': { service_prefix: { 'billing-': { policy: 'deny' } } } } } },
    operator: 'read',
  },
});

describe('the policy list', () => {
  it('lists each policy with a summary of its rules and how many tokens link to it, marking the built-ins', async () => {
    show(writer());

    await screen.findByText('web-writer', { selector: 'a' });
    const listed = rows();
    expect(listed).toHaveLength(3);
    const ours = listed.find((row) => row.includes('web-writer')) ?? '';
    expect(ours).toContain('service web: write');
    expect(ours).toContain('1 token');
    expect(listed.find((row) => row.includes('global-management'))).toContain('Built-in');
    expect(screen.getByText('web-writer', { selector: 'a' }).getAttribute('href')).toBe(`/ui/access/policies/${POLICY.id}`);
  });

  it('creates a policy from its name, description and rules', async () => {
    const bodies: PolicyBody[] = [];
    const base = writer();
    show(writer({ createPolicy: (body) => {
      bodies.push(body);
      return base.createPolicy(body);
    } }));
    fireEvent.click(await screen.findByText('Create policy', { selector: 'button, button span' }));

    type('Name', 'kv-reader');
    type('Description', 'reads keys');
    type('Rules (JSON)', '{"key_prefix": {"app/": {"policy": "read"}}}');
    fireEvent.click(button('Review'));
    const review = await screen.findByLabelText('Create policy');
    expect(review.textContent).toContain('key_prefix "app/": read');
    fireEvent.click(button('Create'));

    expect(await screen.findByText(/Committed and applied at index 54/)).toBeInTheDocument();
    expect(bodies).toEqual([{ name: 'kv-reader', description: 'reads keys', rules: { key_prefix: { 'app/': { policy: 'read' } } } }]);
  });
});

describe('a policy', () => {
  it('shows its rules structured, with the precedence, and the tokens that link to it', async () => {
    at(`/ui/access/policies/${POLICY.id}`);
    const asked: (string | undefined)[] = [];
    const base = writer();
    show(writer({
      policy: () => read(SCOPED, null),
      tokens: (filter) => {
        asked.push(filter?.policy);
        return base.tokens(filter);
      },
    }));

    const rules = await screen.findByLabelText('Rules');
    expect(rules.textContent).toContain('node_prefix ""');
    expect(rules.textContent).toContain('service_prefix "billing-"');
    expect(rules.textContent).toContain('deny');
    expect(rules.textContent).toContain('Tenant acme, namespace team-*');
    expect(rules.textContent).toContain('operator');
    expect(screen.getByText(/An exact name beats a prefix, a longer prefix beats a shorter one, and where two rules are equally specific deny wins, then write, then read./))
      .toBeInTheDocument();
    expect(await screen.findByText(TOKEN.accessorId, { selector: '[aria-label="Linked tokens"] a' }))
      .toHaveAttribute('href', `/ui/access/tokens/${TOKEN.accessorId}`);
    expect(asked).toContain(POLICY.id);
  });

  it('checks the rules are JSON before review, lists the linked tokens a change affects, and shows a refusal from the server', async () => {
    at(`/ui/access/policies/${POLICY.id}`);
    const sent: [string, PolicyBody][] = [];
    show(writer({ updatePolicy: (id, body) => {
      sent.push([id, body]);
      return Promise.reject(new ApiError(`/v1/acl/policy/${id}`, 400, 'invalid_policy', 'Unknown ACL resource "intentions" at intentions',
        false, 'req-5', null));
    } }));
    fireEvent.click(await screen.findByText('Edit', { selector: 'button, button span' }));

    type('Rules (JSON)', '{"service": ');
    fireEvent.click(button('Review'));
    expect(await screen.findByText(/The rules are not valid JSON/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Update policy')).toBeNull();

    type('Rules (JSON)', '{"intentions": "read"}');
    fireEvent.click(button('Review'));
    const review = await screen.findByLabelText('Update policy');
    await waitFor(() => expect(review.textContent).toContain(`Tokens affected: web deployer (${TOKEN.accessorId})`));
    fireEvent.click(button('Save'));

    expect(await screen.findByText('Rejected by validation: Unknown ACL resource "intentions" at intentions')).toBeInTheDocument();
    expect(sent[0]?.[0]).toBe(POLICY.id);
    expect(sent[0]?.[1].rules).toEqual({ intentions: 'read' });
  });

  it('reviews a deletion by the linked tokens, which lose what it granted', async () => {
    at(`/ui/access/policies/${POLICY.id}`);
    let deleted = '';
    show(writer({ deletePolicy: (id) => {
      deleted = id;
      return read(true, 56);
    } }));
    fireEvent.click(await screen.findByText('Delete', { selector: 'button, button span' }));

    const review = await screen.findByLabelText('Delete policy');
    await waitFor(() => expect(review.textContent).toContain(`web deployer (${TOKEN.accessorId})`));
    expect(review.textContent).toContain('The tokens linked to it lose what it granted');
    expect(review.textContent).toContain('A client may go on honouring the policy until its cache entry expires.');
    fireEvent.click(button('Delete'));

    expect(await screen.findByText(/Committed and applied at index 56/)).toBeInTheDocument();
    expect(deleted).toBe(POLICY.id);
  });

  it('offers no deletion of a built-in policy', async () => {
    at(`/ui/access/policies/${MANAGEMENT_POLICY_ID}`);
    show(writer());

    expect(await screen.findByText('A built-in policy cannot be deleted.')).toBeInTheDocument();
    expect(screen.queryByText('Delete', { selector: 'button, button span' })).toBeNull();
  });
});

describe('explanations', () => {
  it('asks the server whether a token may make a request, and says what decided it', async () => {
    at('/ui/access/explain');
    const asked: ExplainQuery[] = [];
    show(writer({ explain: (query) => {
      asked.push(query);
      return read(ExplanationSchema.parse(EXPLANATION), null);
    } }));

    type(await screen.findByLabelText('Accessor ID').then(() => 'Accessor ID'), TOKEN.accessorId);
    type('Resource', 'service');
    type('Resource name', 'web');
    type('Access', 'write');
    fireEvent.click(button('Explain'));

    expect(await screen.findByText('Allowed: decided by the rule service web: write of policy web-writer.')).toBeInTheDocument();
    expect(asked).toEqual([{ accessorId: TOKEN.accessorId, resource: 'service', name: 'web', access: 'write',
      tenant: 'default', namespace: 'default' }]);
  });

  it('names an identity, or the default policy, when one decides', async () => {
    at('/ui/access/explain');
    const answers = [
      ExplanationSchema.parse({ accessorId: TOKEN.accessorId, allowed: true, defaultPolicy: 'deny', decidedBy: 'rule',
        nodeIdentity: { nodeName: 'client-a' }, rule: { resource: 'node', name: 'client-a', prefix: false, access: 'write' } }),
      ExplanationSchema.parse({ accessorId: TOKEN.accessorId, allowed: false, defaultPolicy: 'deny', decidedBy: 'default' }),
    ];
    show(writer({ explain: () => read(answers.shift() as typeof answers[number], null) }));

    type(await screen.findByLabelText('Accessor ID').then(() => 'Accessor ID'), TOKEN.accessorId);
    type('Resource', 'node');
    type('Resource name', 'client-a');
    fireEvent.click(button('Explain'));
    expect(await screen.findByText('Allowed: decided by the rule node client-a: write of the node identity client-a.'))
      .toBeInTheDocument();
    fireEvent.click(button('Explain'));
    expect(await screen.findByText('Denied: no rule matches, so the default policy, deny, decides.')).toBeInTheDocument();
  });

  it("is offered on a token's page for that token", async () => {
    at(`/ui/access/tokens/${TOKEN.accessorId}`);
    show(writer());

    const panel = await screen.findByLabelText('Explain what this token may do');
    expect((panel.querySelector('input[aria-label="Accessor ID"]') as HTMLInputElement).value).toBe(TOKEN.accessorId);
  });

  it("is offered on a service's Access tab for that service in its scope, and on Nodes and Clients for a node", async () => {
    at('/ui/services/web?tenant=acme&namespace=prod');
    const { unmount } = render(<Root bootstrap={BOOTSTRAP} api={writer()} session={memorySession('held')} />);

    fireEvent.click(await screen.findByText('Access', { selector: '.ant-tabs-tab-btn' }));
    const service = await screen.findByLabelText('Explain a decision');
    const field = (panel: HTMLElement, label: string) =>
      (panel.querySelector(`[aria-label="${label}"]`) as HTMLInputElement).value;
    expect(field(service, 'Resource')).toBe('service');
    expect(field(service, 'Resource name')).toBe('web');
    expect(field(service, 'Resource tenant')).toBe('acme');
    expect(field(service, 'Resource namespace')).toBe('prod');
    unmount();

    at('/ui/nodes');
    render(<Root bootstrap={BOOTSTRAP} api={writer()} session={memorySession('held')} />);
    const node = await screen.findByLabelText('Explain a decision');
    expect(field(node, 'Resource')).toBe('node');
  });

  it('is not offered on the service and node pages without acl:read', async () => {
    at('/ui/services/web');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ authorize: granting() })} session={memorySession('held')} />);

    await screen.findByText('Instances', { selector: '.ant-tabs-tab-btn' });
    expect(screen.queryByText('Access', { selector: '.ant-tabs-tab-btn' })).toBeNull();
  });
});
