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
import { nonceRuntimeStyles } from './csp';

describe('nonceRuntimeStyles', () => {
  it('gives a style added without a nonce the request nonce, by either insertion', () => {
    const head = document.createElement('div');
    nonceRuntimeStyles(head, 'bm9uY2U=');

    const appended = head.appendChild(document.createElement('style'));
    const inserted = head.insertBefore(document.createElement('style'), appended);

    expect(appended.nonce).toBe('bm9uY2U=');
    expect(inserted.nonce).toBe('bm9uY2U=');
    expect(Array.from(head.children)).toEqual([inserted, appended]);
  });

  it('keeps a nonce a style already has, and leaves other elements alone', () => {
    const head = document.createElement('div');
    nonceRuntimeStyles(head, 'bm9uY2U=');
    const own = document.createElement('style');
    own.nonce = 'b3du';
    const link = document.createElement('link');

    head.appendChild(own);
    head.appendChild(link);

    expect(own.nonce).toBe('b3du');
    expect(link.getAttribute('nonce')).toBeNull();
  });

  it('wraps a container once, and uses the latest nonce', () => {
    const head = document.createElement('div');
    nonceRuntimeStyles(head, 'Zmlyc3Q=');
    const wrapped = head.appendChild;
    nonceRuntimeStyles(head, 'c2Vjb25k');

    expect(head.appendChild).toBe(wrapped);
    expect(head.appendChild(document.createElement('style')).nonce).toBe('c2Vjb25k');
  });
});
