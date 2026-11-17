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
import { App } from '../App';
import { type Access, unrestricted } from '../access';
import { ApiError, type QraftApi } from '../api/client';
import { BOOTSTRAP, fakeApi, manualScheduler, read, SESSION_A } from './testing';

// UI-6 Tenants, Namespaces and Audit task list, Phase 2: tenants and namespaces created, described and deleted through
// the mutation workflow, each with operator:write (decisions 3 and 10).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const READER: Access = { ...unrestricted('token'), may: { operatorRead: true, operatorWrite: false, aclRead: false,
  aclWrite: false } };
const OPERATOR: Access = { ...READER, may: { ...READER.may, operatorWrite: true } };

function renderApp(api: QraftApi, access: Access = OPERATOR) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

const press = (text: string) => fireEvent.click(screen.getByText(text, { selector: 'button, button span' }));
const pressed = async (text: string) => fireEvent.click(await screen.findByText(text, { selector: 'button, button span' }));
const type = (label: string, text: string) => fireEvent.change(screen.getByLabelText(label), { target: { value: text } });
const outcome = () => screen.findByLabelText('Outcome');
const submit = (title: string) => async () => {
  const review = await screen.findByLabelText(title);
  fireEvent.click(review.querySelector('button.ant-btn-primary')!);
  return review;
};

describe('creating a tenant', () => {
  it('needs operator:write', async () => {
    at('/ui/tenants');
    renderApp(fakeApi(), READER);

    expect(await screen.findByText('Your token may not change tenants or namespaces: it needs operator:write.'))
      .toBeInTheDocument();
    expect(screen.queryByText('Create a tenant', { selector: 'button' })).toBeNull();
  });

  it('checks the name as the server does, reviews the tenant, creates it once and links to it', async () => {
    at('/ui/tenants');
    const created: [string, string][] = [];
    renderApp(fakeApi({ createTenant: (name, description) => {
      created.push([name, description]);
      return read({ Name: name, Description: description }, null);
    } }));

    await pressed('Create a tenant');
    type('Name', 'Acme_2');
    press('Review');
    expect(screen.getByText('A name is lowercase letters, digits and dashes, starting and ending with a letter or '
      + 'digit, at most 63 characters.')).toBeInTheDocument();
    type('Name', 'acme-2');
    type('Description', 'Second');
    press('Review');
    expect((await screen.findByLabelText('Create tenant acme-2')).textContent).toContain('It is created with its '
      + 'default namespace.');
    await submit('Create tenant acme-2')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(created).toEqual([['acme-2', 'Second']]);
    expect(screen.getByText('Open tenant acme-2', { selector: 'a' })).toHaveAttribute('href', '/ui/tenants/acme-2');
  });

  it('shows a tenant that exists as the conflict it is, and never sends a creation again by itself', async () => {
    at('/ui/tenants');
    let sent = 0;
    renderApp(fakeApi({ createTenant: () => {
      sent += 1;
      return Promise.reject(new ApiError('/v1/tenant', 409, 'tenant_exists', 'The tenant exists', false, 'req-3', null));
    } }));

    await pressed('Create a tenant');
    type('Name', 'acme');
    press('Review');
    await submit('Create tenant acme')();
    expect((await outcome()).textContent).toContain('Rejected: it conflicts with the current state (tenant_exists)');
    expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
    expect(sent).toBe(1);
  });
});

describe("a tenant's changes", () => {
  it('create a namespace in it', async () => {
    at('/ui/tenants/acme');
    const created: string[][] = [];
    renderApp(fakeApi({ createNamespace: (tenant, name, description) => {
      created.push([tenant, name, description]);
      return read({ Name: name, Tenant: tenant, Description: description }, null);
    } }));

    await pressed('Create a namespace');
    type('Name', 'staging');
    press('Review');
    await submit('Create namespace acme/staging')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(created).toEqual([['acme', 'staging', '']]);
    expect(screen.getByText('Open namespace acme/staging', { selector: 'a' }))
      .toHaveAttribute('href', '/ui/namespaces/acme/staging');
  });

  it('change its description after a review', async () => {
    at('/ui/tenants/acme');
    const updated: string[][] = [];
    renderApp(fakeApi({ updateTenant: (name, description) => {
      updated.push([name, description]);
      return read({ Name: name, Description: description }, null);
    } }));

    await pressed('Edit the description');
    type('Description', 'Acme Holdings');
    press('Review');
    expect((await screen.findByLabelText('Update tenant acme')).textContent).toContain('Description: Acme Ltd → Acme Holdings');
    await submit('Update tenant acme')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(updated).toEqual([['acme', 'Acme Holdings']]);
  });

  it('delete it after a review that sums what its namespaces hold', async () => {
    at('/ui/tenants/acme');
    const deleted: string[] = [];
    renderApp(fakeApi({ deleteTenant: (name) => {
      deleted.push(name);
      return read(true, null);
    } }));

    await pressed('Delete this tenant');
    const review = await screen.findByLabelText('Delete tenant acme');
    expect(review.textContent).toContain('Its 2 namespaces hold 2 services with 3 instances, and 3 keys: all are removed.');
    expect(review.textContent).toContain(`1 key is held, by session ${SESSION_A}: the session goes on, holding nothing here.`);
    await submit('Delete tenant acme')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(deleted).toEqual(['acme']);
  });

  it('offer no deletion of the default tenant', async () => {
    at('/ui/tenants/default');
    renderApp(fakeApi());

    expect(await screen.findByText('The default tenant cannot be deleted.')).toBeInTheDocument();
    expect(screen.queryByText('Delete this tenant', { selector: 'button' })).toBeNull();
  });
});

describe("a namespace's changes", () => {
  it('change its description', async () => {
    at('/ui/namespaces/acme/prod');
    const updated: string[][] = [];
    renderApp(fakeApi({ updateNamespace: (tenant, name, description) => {
      updated.push([tenant, name, description]);
      return read({ Name: name, Tenant: tenant, Description: description }, null);
    } }));

    await pressed('Edit the description');
    type('Description', 'live');
    press('Review');
    await submit('Update namespace acme/prod')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(updated).toEqual([['acme', 'prod', 'live']]);
  });

  it('delete it after a review of its services, keys and held keys, and say a second deletion found it gone', async () => {
    at('/ui/namespaces/acme/prod');
    let answers = [() => read(true, null), () => Promise.reject(new ApiError('/v1/namespace/prod', 404,
      'namespace_not_found', 'No such namespace', false, 'req-5', null))];
    renderApp(fakeApi({
      servicesSummary: () => read([{ name: 'web', tenantId: 'acme', namespace: 'prod', tags: [], instanceCount: 2,
        health: { unknown: 0, passing: 2, warning: 0, critical: 0, maintenance: 0 }, datacenters: [], regions: [],
        modifyIndex: 9 }]),
      deleteNamespace: () => {
        const [next, ...rest] = answers;
        answers = rest;
        return next!();
      },
    }));

    await pressed('Delete this namespace');
    const review = await screen.findByLabelText('Delete namespace acme/prod');
    expect(review.textContent).toContain('It holds 2 services with 3 instances, and 3 keys: all are removed.');
    await waitFor(() => expect(review.textContent).toContain('Services removed: web (2 instances).'));
    expect(review.textContent).toContain(`1 key is held, by session ${SESSION_A}: the session goes on, holding nothing here.`);
    await submit('Delete namespace acme/prod')();
    expect((await outcome()).textContent).toContain('Committed and applied.');
    press('Close');
    await pressed('Delete this namespace');
    await submit('Delete namespace acme/prod')();
    expect((await outcome()).textContent).toContain('Not found');
    expect(screen.getByText('It was deleted already.')).toBeInTheDocument();
  });

  it('offer no deletion of a default namespace', async () => {
    at('/ui/namespaces/acme/default');
    renderApp(fakeApi());

    expect(await screen.findByText('A default namespace is deleted only with its tenant.')).toBeInTheDocument();
    expect(screen.queryByText('Delete this namespace', { selector: 'button' })).toBeNull();
  });
});
