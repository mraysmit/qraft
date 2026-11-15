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

import { Alert, Space, Typography } from 'antd';
import { useState } from 'react';
import type { ExplainQuery, QraftApi } from '../api/client';
import { explanationText } from './rules';

const RESOURCES = ['service', 'node', 'key', 'session', 'client', 'operator', 'acl'] as const;
const SCOPED = new Set(['service', 'key']);
const SINGLE = new Set(['operator', 'acl']);

/**
 * An explanation (UI/UX design section 10.2.5): may this token make this request, and why. The server answers with
 * the authorizer it enforces with; the interface never evaluates a rule. Offered with a token chosen, on a token's
 * page, or for any token on the Explain page and the policy, service and node pages.
 */
export function ExplainPanel({ api, accessorId, defaults = {} }: {
  api: QraftApi;
  accessorId?: string;
  defaults?: Partial<Pick<ExplainQuery, 'resource' | 'name' | 'tenant' | 'namespace'>>;
}) {
  const [accessor, setAccessor] = useState(accessorId ?? '');
  const [resource, setResource] = useState<string>(defaults.resource ?? 'service');
  const [name, setName] = useState(defaults.name ?? '');
  const [access, setAccess] = useState<'read' | 'write'>('read');
  const [tenant, setTenant] = useState(defaults.tenant ?? 'default');
  const [namespace, setNamespace] = useState(defaults.namespace ?? 'default');
  const [answer, setAnswer] = useState<{ text: string; failed: boolean } | null>(null);
  const [asking, setAsking] = useState(false);

  const explain = () => {
    const query: ExplainQuery = { accessorId: accessor.trim(), resource, access };
    if (!SINGLE.has(resource) && name.trim() !== '') query.name = name.trim();
    if (SCOPED.has(resource)) {
      query.tenant = tenant.trim() || 'default';
      query.namespace = namespace.trim() || 'default';
    }
    setAsking(true);
    api.explain(query).then(
      (read) => setAnswer({ text: explanationText(read.data), failed: false }),
      (error: unknown) => setAnswer({ text: `The server could not explain it: ${error instanceof Error ? error.message : String(error)}`,
        failed: true }),
    ).finally(() => setAsking(false));
  };

  return (
    <section aria-label={accessorId === undefined ? 'Explain a decision' : 'Explain what this token may do'}
      style={{ display: 'grid', gap: 8, maxWidth: 640 }}>
      <Typography.Title level={3}>{accessorId === undefined ? 'Explain a decision' : 'Explain what this token may do'}</Typography.Title>
      <Typography.Text type="secondary">The server decides, with the rules it enforces; the interface evaluates none.</Typography.Text>
      <label>
        {'Accessor ID '}
        <input aria-label="Accessor ID" value={accessor} onChange={(event) => setAccessor(event.target.value)} />
      </label>
      <Space wrap>
        <label>
          {'Resource '}
          <select aria-label="Resource" value={resource} onChange={(event) => setResource(event.target.value)}>
            {RESOURCES.map((item) => <option key={item} value={item}>{item}</option>)}
          </select>
        </label>
        {!SINGLE.has(resource) && (
          <label>
            {'Name '}
            <input aria-label="Resource name" value={name} onChange={(event) => setName(event.target.value)} />
          </label>
        )}
        <label>
          {'Access '}
          <select aria-label="Access" value={access} onChange={(event) => setAccess(event.target.value as 'read' | 'write')}>
            <option value="read">read</option>
            <option value="write">write</option>
          </select>
        </label>
        {SCOPED.has(resource) && (
          <>
            <label>
              {'Tenant '}
              <input aria-label="Resource tenant" value={tenant} onChange={(event) => setTenant(event.target.value)} />
            </label>
            <label>
              {'Namespace '}
              <input aria-label="Resource namespace" value={namespace} onChange={(event) => setNamespace(event.target.value)} />
            </label>
          </>
        )}
      </Space>
      <div>
        {/* A plain button: the panel sits beside tables, and Ant Design's slowed them in jsdom (UI-4). */}
        <button type="button" onClick={explain} disabled={asking || accessor.trim() === ''}>Explain</button>
      </div>
      {answer !== null && <Alert type={answer.failed ? 'error' : 'info'} message={answer.text} />}
    </section>
  );
}
