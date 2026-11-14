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

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { ApiError } from '../api/client';
import { BOOTSTRAP, fakeApi, type ScopesAsked } from './testing';

// UI-2 task list, Step 3: the scope selector, whose choice every view reads in (UI/UX design sections 3.2 and 4.1).

const ACME_PROD = { tenant: 'acme', namespace: 'prod' };

function at(path: string) {
  window.history.pushState(null, '', path);
}

function asked(): ScopesAsked {
  return { services: [], serviceHealth: [], servicesSummary: [], checks: [] };
}

/** Opens one of the selector's lists and chooses `option`. */
async function choose(label: string, option: string) {
  fireEvent.mouseDown((document.querySelector(`input[aria-label=\"${label}\"]`) as HTMLElement));
  const listed = await screen.findAllByTitle(option);
  const item = listed.find((element) => element.classList.contains('ant-select-item-option'));
  if (item === undefined) throw new Error(`no option ${option} in ${label}`);
  fireEvent.click(item);
}

function selected(label: string): string {
  return (document.querySelector(`input[aria-label=\"${label}\"]`) as HTMLElement).closest('.ant-select')?.textContent ?? '';
}

beforeEach(() => at('/ui/'));

describe('scope selector', () => {
  it('reads the scope the address names, shows it, and names it in the view', async () => {
    at('/ui/?tenant=acme&namespace=prod');
    const scopes = asked();

    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);

    expect(await screen.findByText('Read in tenant acme, namespace prod.')).toBeInTheDocument();
    expect(scopes.servicesSummary).toEqual([ACME_PROD]);
    await waitFor(() => expect(selected('Tenant')).toContain('acme'));
    expect(selected('Namespace')).toContain('prod');
  });

  it('reads the default scope without a query, and says so', async () => {
    const scopes = asked();

    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);

    expect(await screen.findByText('Read in the default tenant and namespace.')).toBeInTheDocument();
    expect(scopes.servicesSummary).toEqual([{ tenant: 'default', namespace: 'default' }]);
  });

  it('re-reads the view in the scope chosen, and puts it in the address', async () => {
    const scopes = asked();
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);
    await screen.findByText('web', { selector: 'a' });

    await choose('Tenant', 'acme');
    await waitFor(() => expect(window.location.search).toBe('?tenant=acme&namespace=default'));
    await choose('Namespace', 'prod');

    await waitFor(() => expect(window.location.search).toBe('?tenant=acme&namespace=prod'));
    expect(await screen.findByText('Read in tenant acme, namespace prod.')).toBeInTheDocument();
    expect(scopes.servicesSummary.at(-1)).toEqual(ACME_PROD);
    expect(scopes.servicesSummary).toContainEqual({ tenant: 'acme', namespace: 'default' });
  });

  it('keeps the scope in every link of the interface', async () => {
    at('/ui/?tenant=acme&namespace=prod');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi()} />);

    const service = await screen.findByText('web', { selector: 'a' });
    expect(service.getAttribute('href')).toBe('/ui/services/web?tenant=acme&namespace=prod');
    fireEvent.click(within(screen.getByLabelText('Primary')).getByText('Nodes and Clients', { selector: 'a' }));

    await screen.findByText('Nodes and Clients', { selector: 'h1, h2, h3' });
    expect(window.location.pathname + window.location.search).toBe('/ui/nodes?tenant=acme&namespace=prod');
  });

  // One view per test: the three in one test took 2.2 s, at risk against Vitest's 5 s on Jenkins (build 72).
  it('reads a service in the selected scope', async () => {
    const scopes = asked();
    at('/ui/services/web?tenant=acme&namespace=prod');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);

    await screen.findByText('Service: web', { selector: 'h2' });
    expect(scopes.serviceHealth).toEqual([ACME_PROD]);
    expect(screen.getByText('Read in tenant acme, namespace prod.')).toBeInTheDocument();
  });

  it('reads the checks in the selected scope', async () => {
    const scopes = asked();
    at('/ui/checks?tenant=acme&namespace=prod');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);

    await screen.findByText('Health Checks', { selector: 'h2' });
    expect(scopes.checks).toEqual([ACME_PROD]);
  });

  it('counts the nodes services and failing checks in the selected scope', async () => {
    const scopes = asked();
    at('/ui/nodes?tenant=acme&namespace=prod');
    render(<App bootstrap={BOOTSTRAP} api={fakeApi({}, undefined, scopes)} />);

    await screen.findByText('client-a');
    expect(scopes.services).toEqual([ACME_PROD]);
    expect(screen.getByText(/counted in tenant acme, namespace prod/i)).toBeInTheDocument();
  });

  it('keeps reading the selected scope when the scopes cannot be listed, and says so', async () => {
    at('/ui/?tenant=acme&namespace=prod');
    const api = fakeApi({
      tenants: () => Promise.reject(new ApiError('/v1/tenants', 403, 'permission_denied', 'Permission denied', false, 'r-7', null)),
    });

    render(<App bootstrap={BOOTSTRAP} api={api} />);

    expect(await screen.findByText('web', { selector: 'a' })).toBeInTheDocument();
    expect(await screen.findByText(/the scopes could not be listed: permission denied/i)).toBeInTheDocument();
    expect(selected('Tenant')).toContain('acme');
  });
});
