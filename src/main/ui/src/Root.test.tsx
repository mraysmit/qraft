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

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Root } from './Root';
import { MANAGEMENT_TOKEN } from './api/fixtures';
import { TokenSchema } from './api/schemas';
import { BOOTSTRAP, entry, fakeApi, memorySession, read, storage } from './views/testing';

// The server's policy allows inline styles only with the request's nonce, and no inline script at all: a style
// element without the nonce is blocked, and the page renders unstyled in a browser while every other test passes.
function assertEveryStyleCarriesTheNonce() {
  const styles = Array.from(document.querySelectorAll('style'));
  expect(styles.length).toBeGreaterThan(0);
  for (const style of styles) {
    expect(style.nonce, style.outerHTML.slice(0, 120)).toBe(BOOTSTRAP.cspNonce);
  }
  expect(document.querySelectorAll('script')).toHaveLength(0);
}

/**
 * Every style element inserted into the document while `run` runs, with the nonce it had when inserted. A library may
 * insert a style and remove it at once, as rc-util does to measure the scrollbar: Chrome blocked such a style on
 * 2026-11-14, and a look at the document afterwards does not see it.
 */
async function stylesInsertedDuring(run: () => Promise<void>): Promise<{ css: string; nonce: string }[]> {
  const inserted: { css: string; nonce: string }[] = [];
  const record = (records: MutationRecord[]) => {
    for (const change of records) {
      change.addedNodes.forEach((added) => {
        if (added instanceof HTMLStyleElement) inserted.push({ css: (added.textContent ?? '').slice(0, 80), nonce: added.nonce ?? '' });
      });
    }
  };
  const observer = new MutationObserver(record);
  observer.observe(document, { childList: true, subtree: true });
  await run();
  record(observer.takeRecords());
  observer.disconnect();
  return inserted;
}

describe('Root', () => {
  it('gives every style inserted while a table mounts the nonce, including one removed at once', async () => {
    window.history.pushState(null, '', '/ui/');

    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} session={memorySession('held')} />);
      // The first Ant Design table of the file mounts cold, as it must for the scrollbar to be measured during it: 0.5 s
      // alone, 1.4 s on Jenkins before UI-5 (build 79), 1.6 s under the whole local suite after UI-5's sign-in gate,
      // past the 1 s default wait. The test's own 5 s limit still bounds it.
      await screen.findByText('web', { selector: 'a' }, { timeout: 3000 });
    });

    expect(inserted.some((style) => style.css.includes('::-webkit-scrollbar')), 'the scrollbar measurement ran').toBe(true);
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
  });

  it('renders the application', async () => {
    window.history.pushState(null, '', '/ui/');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} session={memorySession('held')} />);

    // The token is checked first (UI-5), then the shell renders.
    expect(await screen.findByText('web', { selector: 'a' })).toBeInTheDocument();
    expect(screen.getByText('Qraft', { selector: 'h1' })).toBeInTheDocument();
  });

  // One test per view: walking every view in one test took 4.8 s on Jenkins build 72, against Vitest's 5 s limit.
  const api = () => fakeApi({}, { web: [entry({ health: 'CRITICAL' }, [{ status: 'CRITICAL', expired: true }])] });

  it('gives every style element Ant Design adds the request nonce, on Services with the development banner', async () => {
    window.history.pushState(null, '', '/ui/');
    render(<Root bootstrap={{ ...BOOTSTRAP, authMode: 'none', securityProfile: 'development' }} api={api()} />);
    await screen.findByText('web', { selector: 'a' });
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style element Ant Design adds the request nonce, on each tab of a service', async () => {
    window.history.pushState(null, '', '/ui/services/web');
    render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
    for (const tab of ['Instances', 'Health Checks', 'Metadata']) {
      fireEvent.click(await screen.findByText(tab, { selector: '.ant-tabs-tab-btn' }));
      await screen.findByText(tab, { selector: '.ant-tabs-tab-active .ant-tabs-tab-btn' });
    }
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style element Ant Design adds the request nonce, on Nodes and Clients', async () => {
    window.history.pushState(null, '', '/ui/nodes');
    render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
    await screen.findByText('client-a');
    assertEveryStyleCarriesTheNonce();
  });

  // UI-2: the Health Checks page, and the scope selector's open list, in tests of their own: in one, the test passed
  // Vitest's 5 s in the Docker stage of Jenkins build 75.
  it('gives every style inserted on Health Checks the request nonce', async () => {
    window.history.pushState(null, '', '/ui/checks?tenant=acme&namespace=prod');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
      await screen.findByText('web-1');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style inserted for the open scope list the request nonce', async () => {
    // The selector is in the top bar of every page; an address that is no view renders the least beside it.
    window.history.pushState(null, '', '/ui/nowhere?tenant=acme&namespace=prod');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
      await screen.findByText('No such view', { selector: 'h2' });
      fireEvent.mouseDown(document.querySelector('input[aria-label="Tenant"]') as Element);
      await screen.findAllByTitle('acme');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });
  // UI-5: sign-in, before any session, and the Access section's Status page.
  it('gives every style inserted on the sign-in page the request nonce', async () => {
    window.history.pushState(null, '', '/ui/');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession()} />);
      await screen.findByText('Sign in', { selector: 'h2' });
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style inserted on the Status page, with the management banner, the request nonce', async () => {
    window.history.pushState(null, '', '/ui/access');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ self: () => read(TokenSchema.parse(MANAGEMENT_TOKEN), null) })}
        session={memorySession('held')} />);
      await screen.findByLabelText('Security status');
      await screen.findByLabelText('Management token in use');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });

  it("gives every style inserted on Tokens, with a token's review and its outcome, the request nonce", async () => {
    window.history.pushState(null, '', '/ui/access/tokens/2b7e4f1a-9c3d-4e8f-a1b2-c3d4e5f6a7b8');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
      fireEvent.click(await screen.findByText('Delete', { selector: 'button, button span' }));
      fireEvent.click(await screen.findByText('Delete', { selector: '[aria-label="Delete token"] button span' }));
      await screen.findByLabelText('Outcome');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style inserted on a policy, with its editor open, the request nonce', async () => {
    window.history.pushState(null, '', '/ui/access/policies/5e0b8a3c-1d2f-4a6b-8c9d-0e1f2a3b4c5d');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} session={memorySession('held')} />);
      fireEvent.click(await screen.findByText('Edit', { selector: 'button, button span' }));
      await screen.findByLabelText('Rules (JSON)');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });

  // UI-3: the Operate pages, each in a test of its own; the cluster page with fenced storage shows its banner.
  for (const [path, waitFor] of [
    ['/ui/cluster', 'Cluster Overview'],
    ['/ui/members', 'Raft Members'],
    ['/ui/storage', 'Storage & Snapshots'],
  ] as const) {
    it(`gives every style inserted on ${waitFor} the request nonce`, async () => {
      window.history.pushState(null, '', path);
      const fenced = storage({ fenced: true, fenceReason: 'disk full' });
      const inserted = await stylesInsertedDuring(async () => {
        render(<Root bootstrap={BOOTSTRAP} api={fakeApi({ storage: () => read(fenced) })} session={memorySession('held')} />);
        await screen.findByText(waitFor, { selector: 'h2' });
        await screen.findAllByText(/server-1/);
      });
      expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
      assertEveryStyleCarriesTheNonce();
    });
  }
});
