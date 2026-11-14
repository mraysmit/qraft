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
import { hrefOf, routeOf } from './routing';

describe('routes', () => {
  it('read the views below the interface path', () => {
    expect(routeOf('/ui/', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui/services', '/ui/')).toEqual({ view: 'services' });
    expect(routeOf('/ui/nodes', '/ui/')).toEqual({ view: 'nodes' });
    expect(routeOf('/ui/services/web', '/ui/')).toEqual({ view: 'service', name: 'web' });
    expect(routeOf('/console/nodes', '/console/')).toEqual({ view: 'nodes' });
  });

  it('take anything else for an unknown view', () => {
    for (const path of ['/ui/nowhere', '/ui/services/a/b', '/ui/services/', '/other/', '/ui/services/%E0%A4%A']) {
      expect(routeOf(path, '/ui/'), path).toEqual({ view: 'unknown' });
    }
  });

  it('write a service name as one segment with no dot, so the server never takes it for a file', () => {
    const href = hrefOf({ view: 'service', name: 'team/payments.v2' }, '/ui/');

    expect(href).toBe('/ui/services/team%2Fpayments%2Ev2');
    expect(href.slice(href.lastIndexOf('/') + 1)).not.toContain('.');
    expect(routeOf(href, '/ui/')).toEqual({ view: 'service', name: 'team/payments.v2' });
  });

  it('write the other views below the interface path', () => {
    expect(hrefOf({ view: 'services' }, '/console/')).toBe('/console/');
    expect(hrefOf({ view: 'nodes' }, '/console/')).toBe('/console/nodes');
  });
});
