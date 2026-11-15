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
import { createSession, SECRET_KEY, type SecretStorage } from './session';

// UI-5 task list, decision 1: the secret is held in the tab's sessionStorage, and goes on signing out and when the
// server no longer knows it.

function memoryStorage(): SecretStorage & { items: Map<string, string> } {
  const items = new Map<string, string>();
  return {
    items,
    getItem: (key) => items.get(key) ?? null,
    setItem: (key, value) => void items.set(key, value),
    removeItem: (key) => void items.delete(key),
  };
}

describe('session', () => {
  it('keeps the secret signed in with in the storage, and nothing else', () => {
    const storage = memoryStorage();
    const session = createSession(storage);
    expect(session.secret()).toBeNull();

    session.signIn('the-secret');

    expect(session.secret()).toBe('the-secret');
    expect([...storage.items]).toEqual([[SECRET_KEY, 'the-secret']]);
    expect(createSession(storage).secret(), 'a reload of the tab finds it').toBe('the-secret');
  });

  it('forgets the secret on signing out', () => {
    const storage = memoryStorage();
    const session = createSession(storage);
    session.signIn('the-secret');

    session.signOut();

    expect(session.secret()).toBeNull();
    expect(storage.items.size).toBe(0);
    expect(session.ended()).toBeNull();
  });

  it('forgets the secret the server no longer knows, and says why the session ended until the next sign-in', () => {
    const storage = memoryStorage();
    const session = createSession(storage);
    session.signIn('the-secret');

    session.expire('The server no longer knows this token.');

    expect(session.secret()).toBeNull();
    expect(storage.items.size).toBe(0);
    expect(session.ended()).toBe('The server no longer knows this token.');
    session.signIn('another-secret');
    expect(session.ended()).toBeNull();
  });

  it('tells its listeners of each change, until they stop listening', () => {
    const session = createSession(memoryStorage());
    let told = 0;
    const stop = session.subscribe(() => {
      told += 1;
    });

    session.signIn('a');
    session.expire('gone');
    stop();
    session.signIn('b');

    expect(told).toBe(2);
  });

  it('holds the secret for the page when the browser refuses its storage', () => {
    const refusing: SecretStorage = {
      getItem: () => {
        throw new Error('storage is disabled');
      },
      setItem: () => {
        throw new Error('storage is disabled');
      },
      removeItem: () => {
        throw new Error('storage is disabled');
      },
    };
    const session = createSession(refusing);

    session.signIn('the-secret');

    expect(session.secret()).toBe('the-secret');
    session.signOut();
    expect(session.secret()).toBeNull();
  });
});
