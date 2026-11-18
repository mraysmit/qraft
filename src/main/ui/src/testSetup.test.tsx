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

import { getConfig, render } from '@testing-library/react';
import { Table } from 'antd';
import { describe, expect, it } from 'vitest';

// The rules setupTests.ts gives every test.

// Both limits guard against a test that never ends, not against a slow machine: a test's time is CPU alone, since each
// drives time with a scheduler it fires by hand. On 2026-11-18 a machine short of CPU ran waits past 3 s and tests past
// 15 s, and with twelve threads spinning beside the suite one wait in each run passed 3 s, a different test each time.
describe('a wait', () => {
  // A view's first wait spans the whole interface mounting, its sign-in gate, its authorize call and its first table:
  // past Testing Library's 1 s on Jenkins (builds 79 and 83), and past 3 s on a busy machine.
  it('lasts up to 15 s', () => {
    expect(getConfig().asyncUtilTimeout).toBe(15_000);
  });
});

describe("a test's own limit", () => {
  // Longer than several waits, so a wait that fails reports what it was waiting for before the test's own limit ends it.
  it('lasts up to 60 s', ({ task }) => {
    expect(task.timeout).toBe(60_000);
  });
});

const styles = () => document.head.querySelectorAll('style').length;

const table = () => (
  <Table rowKey="key" dataSource={[{ key: 'a' }]} columns={[{ title: 'Key', dataIndex: 'key' }]} pagination={false} />
);

// Jenkins build 83: Ant Design leaves the style elements it added after its components unmount, and jsdom's
// getComputedStyle evaluates every rule of every one, so a table mounted late in a file measured itself against all
// the styles of the tests before it. The tests run in the order written.
describe('a test', () => {
  it('lets Ant Design add its styles to the page', () => {
    render(table());

    expect(styles()).toBeGreaterThan(0);
  });

  it('starts with none of the styles an earlier test added', () => {
    expect(styles()).toBe(0);
  });

  it('has the styles of what it mounts added again', () => {
    render(table());

    expect(styles()).toBeGreaterThan(0);
  });
});
