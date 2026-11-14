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
import { App } from './App';
import { createApi, type QraftApi } from './api/client';
import type { Bootstrap } from './bootstrap';
import { nonceRuntimeStyles } from './csp';

/**
 * Everything the page renders. Ant Design adds its styles at run time; the request's nonce lets the server's content
 * security policy allow them. The API is the server's own unless a test supplies one.
 */
export function Root({ bootstrap, api }: { bootstrap: Bootstrap; api?: QraftApi }) {
  // While rendering, before any child mounts: a child's layout effect, where a table measures the scrollbar, runs
  // before this component's own effects. It is idempotent.
  nonceRuntimeStyles(document.head, bootstrap.cspNonce);
  const client = useMemo(() => api ?? createApi(bootstrap.apiBasePath), [api, bootstrap.apiBasePath]);
  return (
    <StrictMode>
      <ConfigProvider csp={{ nonce: bootstrap.cspNonce }}>
        <App bootstrap={bootstrap} api={client} />
      </ConfigProvider>
    </StrictMode>
  );
}
