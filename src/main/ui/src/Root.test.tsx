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

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Bootstrap } from './bootstrap';
import { Root } from './Root';

const BOOTSTRAP: Bootstrap = {
  apiBasePath: '/',
  uiPath: '/ui/',
  features: {},
  authMode: 'none',
  securityProfile: 'production',
  cspNonce: 'cm9vdC1ub25jZQ==',
};

describe('Root', () => {
  it('renders the application', () => {
    render(<Root bootstrap={BOOTSTRAP} />);

    expect(screen.getByRole('heading', { name: 'Qraft' })).toBeInTheDocument();
  });

  // The server's policy allows inline styles only with the request's nonce: a style element without it is blocked,
  // and the page renders unstyled in a browser while every other test passes.
  it('gives every style element Ant Design adds the request nonce', () => {
    render(<Root bootstrap={BOOTSTRAP} />);

    const styles = Array.from(document.querySelectorAll('style'));
    expect(styles.length).toBeGreaterThan(0);
    for (const style of styles) {
      expect(style.nonce, style.outerHTML.slice(0, 120)).toBe(BOOTSTRAP.cspNonce);
    }
  });
});
