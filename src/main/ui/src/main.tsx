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

import { createRoot } from 'react-dom/client';
import { BOOTSTRAP_ELEMENT_ID, type Bootstrap, readBootstrap } from './bootstrap';
import { Root } from './Root';

// `npm run dev` serves the page itself, with no server to render the settings and no policy to satisfy.
const DEVELOPMENT: Bootstrap = {
  apiBasePath: '/',
  uiPath: '/',
  features: {},
  authMode: 'none',
  securityProfile: 'development',
  cspNonce: 'development',
};

const bootstrap =
  import.meta.env.DEV && document.getElementById(BOOTSTRAP_ELEMENT_ID) === null ? DEVELOPMENT : readBootstrap(document);

const root = document.getElementById('root');
if (root === null) throw new Error('index.html has no #root element');

createRoot(root).render(<Root bootstrap={bootstrap} />);
