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
import { BOOTSTRAP, entry, fakeApi } from './views/testing';

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
      render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} />);
      await screen.findByRole('link', { name: 'web' });
    });

    expect(inserted.some((style) => style.css.includes('::-webkit-scrollbar')), 'the scrollbar measurement ran').toBe(true);
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
  });

  it('renders the application', async () => {
    window.history.pushState(null, '', '/ui/');
    render(<Root bootstrap={BOOTSTRAP} api={fakeApi()} />);

    expect(screen.getByRole('heading', { name: 'Qraft' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'web' })).toBeInTheDocument();
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
    render(<Root bootstrap={BOOTSTRAP} api={api()} />);
    for (const tab of ['Instances', 'Health Checks', 'Metadata']) {
      fireEvent.click(await screen.findByText(tab, { selector: '.ant-tabs-tab-btn' }));
      await screen.findByText(tab, { selector: '.ant-tabs-tab-active .ant-tabs-tab-btn' });
    }
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style element Ant Design adds the request nonce, on Nodes and Clients', async () => {
    window.history.pushState(null, '', '/ui/nodes');
    render(<Root bootstrap={BOOTSTRAP} api={api()} />);
    await screen.findByText('client-a');
    assertEveryStyleCarriesTheNonce();
  });

  it('gives every style inserted on Health Checks and in the open scope list the request nonce', async () => {
    // UI-2: the Health Checks page, and the scope selector's open list.
    window.history.pushState(null, '', '/ui/checks?tenant=acme&namespace=prod');
    const inserted = await stylesInsertedDuring(async () => {
      render(<Root bootstrap={BOOTSTRAP} api={api()} />);
      await screen.findByText('web-1');
      fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Tenant' }));
      await screen.findAllByTitle('acme');
    });
    expect(inserted.filter((style) => style.nonce !== BOOTSTRAP.cspNonce)).toEqual([]);
    assertEveryStyleCarriesTheNonce();
  });
});
