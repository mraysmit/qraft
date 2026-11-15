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

import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from '../App';
import { type Access, unrestricted } from '../access';
import type { AuthorizationCheck, QraftApi, Read, ReadOptions, Scope } from '../api/client';
import type { KvEntry } from '../api/schemas';
import { BOOTSTRAP, fakeApi, kvEntry, manualScheduler, read } from './testing';

// UI-6 Key/Value task list, Phase 2: creating, updating and deleting keys and folders through the mutation workflow,
// every update and deletion a compare-and-set at the index read (decisions 5 to 7, 9 and 10).

function at(path: string) {
  window.history.pushState(null, '', path);
}

beforeEach(() => at('/ui/'));

const TOKEN: Access = { ...unrestricted('token'), may: { operatorRead: false, operatorWrite: false, aclRead: false,
  aclWrite: false } };

function renderApp(api: QraftApi, access: Access = TOKEN) {
  render(<App bootstrap={BOOTSTRAP} api={api} scheduler={manualScheduler().scheduler} access={access} />);
}

/** An authorize answer allowing `key:write` on the segments given, and nothing else. */
function writing(...segments: string[]) {
  const asked: AuthorizationCheck[] = [];
  const authorize = (checks: AuthorizationCheck[]) => {
    asked.push(...checks);
    return read(checks.map((check) => ({ ...check, allow: check.resource === 'key' && check.access === 'write'
      && segments.includes(check.segment ?? '') })), null);
  };
  return { asked, authorize };
}

interface Put {
  key: string;
  scope: Scope | undefined;
  value: string;
  at: { flags: number; cas: number };
}

const press = (text: string) => fireEvent.click(screen.getByText(text, { selector: 'button, button span' }));
const pressed = async (text: string) => fireEvent.click(await screen.findByText(text, { selector: 'button, button span' }));
const type = (label: string, text: string) => fireEvent.change(screen.getByLabelText(label), { target: { value: text } });
const outcome = () => screen.findByLabelText('Outcome');

describe('changing an entry', () => {
  it('is offered only with key:write on the key in the selected scope', async () => {
    at('/ui/kv?tenant=acme&namespace=prod&path=app%2Fconfig');
    const none = writing();
    renderApp(fakeApi({ authorize: none.authorize }));

    await screen.findByText('app/config', { selector: 'h2' });
    await screen.findByText('Your token may not change this key: it needs key:write.');
    expect(screen.queryByText('Edit', { selector: 'button' })).toBeNull();
    expect(screen.queryByText('Delete', { selector: 'button' })).toBeNull();
    expect(none.asked).toEqual([{ resource: 'key', segment: 'app/config', access: 'write', tenant: 'acme',
      namespace: 'prod' }]);
  });

  it('edits the value and flags, reviews the change, and saves it at the index it was read at', async () => {
    at('/ui/kv?path=app%2Fconfig');
    const puts: Put[] = [];
    renderApp(fakeApi({ authorize: writing('app/config').authorize, kvPut: (key, scope, value, written) => {
      puts.push({ key, scope, value, at: written });
      return read(true, null, { requestId: 'req-9' });
    } }));

    await pressed('Edit');
    expect((screen.getByLabelText('New value') as HTMLTextAreaElement).value).toBe('{"replicas":3}');
    type('New value', 'hello');
    type('Flags', '8');
    press('Review');

    expect(screen.getByText('Value: 14 bytes → 5 bytes')).toBeInTheDocument();
    expect(screen.getByText('Flags: 7 → 8')).toBeInTheDocument();
    expect(screen.getByText('Saved only if the entry is still at index 24, as read.')).toBeInTheDocument();
    press('Save');

    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(puts).toEqual([{ key: 'app/config', scope: { tenant: 'default', namespace: 'default' }, value: 'hello',
      at: { flags: 8, cas: 24 } }]);
  });

  it("shows a stale write's indexes and the difference from the value now held", async () => {
    at('/ui/kv?path=app%2Fconfig');
    const held = [kvEntry('app/config', '{"replicas":3}', { Flags: 7 }),
      kvEntry('app/config', '{"replicas":5}', { Flags: 7, ModifyIndex: 25 })];
    let reads = 0;
    renderApp(fakeApi({
      authorize: writing('app/config').authorize,
      kvEntry: () => read(held[Math.min(reads++, 1)]!, 30),
      kvPut: () => read(false, null),
    }));

    await pressed('Edit');
    type('New value', '{"replicas":4}');
    press('Review');
    press('Save');

    const reported = await outcome();
    expect(reported.textContent).toContain('Rejected: it conflicts with the current state (stale)');
    expect(await screen.findByText('Expected index 24, now at 25.')).toBeInTheDocument();
    expect(document.querySelector('pre[aria-label="Difference"]')?.textContent)
      .toBe('- {"replicas":5}\n+ {"replicas":4}');
  });

  it('refuses a value over 512 KiB and flags outside the safe integers before the review', async () => {
    at('/ui/kv?path=app%2Fconfig');
    renderApp(fakeApi({ authorize: writing('app/config').authorize }));

    await pressed('Edit');
    type('New value', 'x'.repeat(512 * 1024 + 1));
    type('Flags', '-1');
    press('Review');

    expect(screen.getByText('The value is 524289 bytes, over the server limit of 524288.')).toBeInTheDocument();
    expect(screen.getByText('Flags are a whole number from 0 to 9007199254740991.')).toBeInTheDocument();
    expect(screen.queryByText('Save', { selector: 'button, button span' })).toBeNull();
  });

  it('says the entry changed after the editor opened, which a save will meet as stale', async () => {
    at('/ui/kv?path=app%2Fconfig');
    let deliver: (entry: KvEntry) => void = () => undefined;
    const base = fakeApi({ authorize: writing('app/config').authorize });
    const api: QraftApi = {
      ...base,
      kvEntry: (_key, _scope, options?: ReadOptions): Promise<Read<KvEntry | null>> =>
        options?.index === undefined ? read(kvEntry('app/config', '{"replicas":3}', { Flags: 7 }), 30)
          : new Promise((resolve) => {
            deliver = (entry) => resolve({ ...{ index: 31, requestId: null, answeredBy: 'server-1',
              consistency: 'default', knownLeader: true, lastContactMillis: 0, filtered: false }, data: entry });
          }),
    };
    renderApp(api);

    await pressed('Edit');
    type('New value', 'mine');
    await act(async () => deliver(kvEntry('app/config', 'theirs', { ModifyIndex: 31 })));

    expect(await screen.findByText('This entry changed at index 31 after you opened it: saving will be refused as stale.'))
      .toBeInTheDocument();
    expect((screen.getByLabelText('New value') as HTMLTextAreaElement).value).toBe('mine');
  });

  it('deletes the key at the index it was read at', async () => {
    at('/ui/kv?path=app%2Fconfig');
    const deleted: [string, number][] = [];
    renderApp(fakeApi({ authorize: writing('app/config').authorize, kvDelete: (key, _scope, cas) => {
      deleted.push([key, cas]);
      return read(true, null);
    } }));

    await pressed('Delete');
    expect(screen.getByText('The key and its value are removed, only if the entry is still at index 24, as read.'))
      .toBeInTheDocument();
    fireEvent.click(screen.getAllByText('Delete', { selector: 'button, button span' }).at(-1)!);

    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(deleted).toEqual([['app/config', 24]]);
  });

  it('does not edit a binary value or flags it cannot read exactly, and still deletes the entry', async () => {
    at('/ui/kv?path=bin');
    renderApp(fakeApi({ authorize: writing('bin').authorize, kvEntry: () => read({ ...kvEntry('bin', ''),
      Value: btoa(String.fromCharCode(0xff, 0xfe)) }, 30) }));

    expect(await screen.findByText('This value is binary, which the interface does not edit.')).toBeInTheDocument();
    expect(screen.queryByText('Edit', { selector: 'button' })).toBeNull();
    expect(screen.getByText('Delete', { selector: 'button' })).toBeInTheDocument();
  });
});

describe('changing a folder', () => {
  it('creates a key in the folder, only if no key of its name exists', async () => {
    at('/ui/kv?path=app%2F');
    const puts: Put[] = [];
    renderApp(fakeApi({ authorize: writing('app/').authorize, kvPut: (key, scope, value, written) => {
      puts.push({ key, scope, value, at: written });
      return read(puts.length === 1, null);
    } }));

    await pressed('Create a key');
    type('Name', 'feature/flag');
    type('New value', 'on');
    press('Review');
    expect(screen.getByText('app/feature/flag', { selector: 'dd' })).toBeInTheDocument();
    expect(screen.getByText('Created only if no key of this name exists.')).toBeInTheDocument();
    press('Create');

    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(puts[0]).toEqual({ key: 'app/feature/flag', scope: { tenant: 'default', namespace: 'default' }, value: 'on',
      at: { flags: 0, cas: 0 } });
    expect(screen.getByText('Open app/feature/flag', { selector: 'a' })).toHaveAttribute('href',
      '/ui/kv?path=app%2Ffeature%2Fflag');
  });

  it('refuses a name that is empty or names a folder, before the review', async () => {
    at('/ui/kv?path=app%2F');
    renderApp(fakeApi({ authorize: writing('app/').authorize }));

    await pressed('Create a key');
    type('Name', 'sub/');
    press('Review');

    expect(screen.getByText('A key name is not empty and does not end in /.')).toBeInTheDocument();
    expect(screen.queryByText('Create', { selector: 'button, button span' })).toBeNull();
  });

  it("deletes a folder's keys after counting them, and offers no deletion of the whole store", async () => {
    at('/ui/kv?path=app%2F');
    const trees: string[] = [];
    renderApp(fakeApi({ authorize: writing('app/', '').authorize, kvDeleteTree: (prefix) => {
      trees.push(prefix);
      return read(true, null);
    } }));

    await pressed('Delete this folder');
    expect(await screen.findByText(
      '3 keys under app/ are deleted, and any key written under it before the deletion is applied.')).toBeInTheDocument();
    fireEvent.click(screen.getAllByText('Delete', { selector: 'button, button span' }).at(-1)!);

    expect((await outcome()).textContent).toContain('Committed and applied.');
    expect(trees).toEqual(['app/']);
  });

  it('offers no change without key:write on the folder, and no folder deletion at the top', async () => {
    at('/ui/kv');
    renderApp(fakeApi({ authorize: writing('').authorize }));

    await screen.findByText('Create a key', { selector: 'button' });
    expect(screen.queryByText('Delete this folder', { selector: 'button' })).toBeNull();
  });

  it('offers no change in a folder the token may not write', async () => {
    at('/ui/kv?path=app%2F');
    renderApp(fakeApi({ authorize: writing().authorize }));

    expect(await screen.findByText('Your token may not write under app/: it needs key:write.')).toBeInTheDocument();
    expect(screen.queryByText('Create a key', { selector: 'button' })).toBeNull();
  });
});
