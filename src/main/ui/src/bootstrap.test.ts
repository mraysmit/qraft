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
import { ZodError } from 'zod';
import { readBootstrap } from './bootstrap';

// The element the server renders into index.html (AdminUiHandler).
function page(json: string | null): Document {
  const document = window.document.implementation.createHTMLDocument('Qraft');
  if (json !== null) {
    const element = document.createElement('script');
    element.id = 'qraft-bootstrap';
    element.type = 'application/json';
    element.textContent = json;
    document.head.append(element);
  }
  return document;
}

const SERVED = {
  apiBasePath: '/',
  uiPath: '/ui/',
  features: { events: true },
  authMode: 'token',
  securityProfile: 'production',
  cspNonce: 'bm9uY2U=',
};

describe('readBootstrap', () => {
  it('reads the settings the server renders into the page', () => {
    expect(readBootstrap(page(JSON.stringify(SERVED)))).toEqual(SERVED);
  });

  it('refuses a page the server did not render', () => {
    expect(() => readBootstrap(page(null))).toThrow(/served by a Qraft server/);
  });

  it('refuses settings that are not JSON', () => {
    expect(() => readBootstrap(page('{apiBasePath'))).toThrow(SyntaxError);
  });

  it('refuses an authentication mode or profile it does not know', () => {
    expect(() => readBootstrap(page(JSON.stringify({ ...SERVED, authMode: 'basic' })))).toThrow(ZodError);
    expect(() => readBootstrap(page(JSON.stringify({ ...SERVED, securityProfile: 'staging' })))).toThrow(ZodError);
  });

  it('refuses settings without a nonce, which the styles need', () => {
    expect(() => readBootstrap(page(JSON.stringify({ ...SERVED, cspNonce: '' })))).toThrow(ZodError);
  });
});
