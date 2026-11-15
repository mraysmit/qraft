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

import { ConfigProvider } from 'antd';
import { StrictMode, useMemo } from 'react';
import { AccessGate, SESSION_ENDED } from './access';
import { App } from './App';
import { createApi, type QraftApi } from './api/client';
import type { Bootstrap } from './bootstrap';
import { nonceRuntimeStyles } from './csp';
import { createSession, type Session, tabStorage } from './session';

/**
 * Everything the page renders. Ant Design adds its styles at run time; the request's nonce lets the server's content
 * security policy allow them. The session is the tab's (UI-5 task list, decision 1), and every request of the API
 * presents its token; a test may supply the session, the API, or the fetch the API sends with.
 */
export function Root({ bootstrap, api, session, fetcher }: {
  bootstrap: Bootstrap;
  api?: QraftApi;
  session?: Session;
  fetcher?: typeof fetch;
}) {
  // While rendering, before any child mounts: a child's layout effect, where a table measures the scrollbar, runs
  // before this component's own effects. It is idempotent.
  nonceRuntimeStyles(document.head, bootstrap.cspNonce);
  const held = useMemo(() => session ?? createSession(tabStorage()), [session]);
  const client = useMemo(() => api ?? createApi(bootstrap.apiBasePath, fetcher ?? ((input, init) => fetch(input, init)), {
    secret: () => held.secret(),
    onTokenNotFound: () => held.expire(SESSION_ENDED),
  }), [api, bootstrap.apiBasePath, fetcher, held]);
  return (
    <StrictMode>
      <ConfigProvider csp={{ nonce: bootstrap.cspNonce }}>
        <AccessGate bootstrap={bootstrap} api={client} session={held}>
          {(access) => <App bootstrap={bootstrap} api={client} access={access} />}
        </AccessGate>
      </ConfigProvider>
    </StrictMode>
  );
}
