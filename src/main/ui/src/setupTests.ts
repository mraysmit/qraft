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

import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterEach } from 'vitest';

// A view's first wait spans the whole interface mounting: its sign-in gate, its authorize call and its first table. That
// took past Testing Library's 1 s on Jenkins (builds 79 and 83), and past 3 s on a machine short of CPU (2026-11-18).
// Time in a test is driven by hand, so a wait is CPU alone, and its limit guards against what never appears, not
// against a slow machine. A test's own limit, 60 s (vite.config.ts), bounds several such waits.
configure({ asyncUtilTimeout: 15_000 });

// Vitest runs without globals here, so Testing Library cannot register its own cleanup between tests. Ant Design keeps
// the style elements it added after its components unmount, and jsdom's getComputedStyle evaluates every rule of every
// one: a table mounted late in a file spent most of its time measuring itself against the styles of the tests before
// it (Jenkins build 83). Each test starts with a head as a fresh page has it; what it mounts adds its styles again.
afterEach(() => {
  cleanup();
  document.head.querySelectorAll('style').forEach((element) => element.remove());
});

// jsdom has no media queries. Ant Design's table asks for its responsive breakpoints; no query matches here.
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

// jsdom does not compute a pseudo-element's style, and reports it as an error. Ant Design asks for one to measure the
// scrollbar; the element's own style is a sufficient answer in a page that is never laid out.
const elementStyle = window.getComputedStyle.bind(window);
window.getComputedStyle = (element: Element) => elementStyle(element);
