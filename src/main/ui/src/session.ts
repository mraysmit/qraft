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

/** The key the tab's storage holds the secret under. */
export const SECRET_KEY = 'qraft.token';

/** The part of the Web Storage interface the session uses: the tab's `sessionStorage` in a browser. */
export type SecretStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/**
 * Who is signed in (UI-5 task list, decision 1). The secret is held in the tab's storage: it lasts across reloads of
 * the tab, is not shared with other tabs, and goes when the tab closes. Signing out, and the server no longer knowing
 * the token, forget it at once. It is never put in an address, a cookie or `localStorage`, and never logged.
 */
export interface Session {
  /** The secret signed in with, or null. */
  secret(): string | null;
  signIn(secret: string): void;
  signOut(): void;
  /** The server no longer knows the token: forget it, and say why until the next sign-in. */
  expire(reason: string): void;
  /** Why the last session ended, when the server ended it; null otherwise. */
  ended(): string | null;
  /** Tells `listener` of each change; the returned function stops it. */
  subscribe(listener: () => void): () => void;
}

/**
 * A session held in `storage`. A browser that refuses its storage, as a private window may, still holds the secret for
 * the page, in memory, until it is reloaded.
 */
export function createSession(storage: SecretStorage | null): Session {
  let held: string | null = attempt(() => storage?.getItem(SECRET_KEY) ?? null, null);
  let reason: string | null = null;
  const listeners = new Set<() => void>();
  const tell = () => listeners.forEach((listener) => listener());
  const forget = () => {
    held = null;
    attempt(() => storage?.removeItem(SECRET_KEY), undefined);
  };
  return {
    secret: () => held,
    signIn(secret) {
      held = secret;
      reason = null;
      attempt(() => storage?.setItem(SECRET_KEY, secret), undefined);
      tell();
    },
    signOut() {
      forget();
      reason = null;
      tell();
    },
    expire(why) {
      forget();
      reason = why;
      tell();
    },
    ended: () => reason,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/** The tab's `sessionStorage`, or null where the browser refuses it. */
export function tabStorage(): SecretStorage | null {
  return attempt(() => window.sessionStorage, null);
}

function attempt<T>(run: () => T, otherwise: T): T {
  try {
    return run();
  } catch {
    return otherwise;
  }
}
