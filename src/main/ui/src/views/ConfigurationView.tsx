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

import { Alert, Input, Radio, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import type { QraftApi } from '../api/client';
import type { Setting } from '../api/schemas';
import { RouteLink } from '../routing';
import { useRead } from '../useRead';
import { Failure, formatInstant } from './common';

const SOURCES = ['All', 'Configured', 'Default'] as const;
type SourceFilter = (typeof SOURCES)[number];

function value(setting: Setting) {
  const shown = setting.value;
  let text: string | null;
  if (shown === null) text = null;
  else if (typeof shown === 'object') text = Object.entries(shown).map(([name, item]) => `${name}=${item}`).join(', ');
  else text = String(shown);
  if (setting.redacted) {
    return (
      <Space size={4}>
        {text !== null && text !== '' && <span>{text}</span>}
        <Tag>Redacted</Tag>
      </Space>
    );
  }
  return text ?? 'Not set';
}

/**
 * Configuration, read-only (UI/UX design section 9.6; UI-4 task list, decision 3): the answering server's effective
 * settings, each with its source, a secret withheld. Every setting is static, read at startup; the replicated
 * configuration is the Raft server configuration, which Raft Members shows.
 */
export function ConfigurationView({ api }: { api: QraftApi }) {
  const [state, again] = useRead(() => api.configuration(), [api]);
  const [source, setSource] = useState<SourceFilter>('All');
  const [text, setText] = useState('');
  const shown = state.status === 'ready' ? state.value.data : state.previous?.data;
  const needle = text.trim().toLowerCase();
  const settings = (shown?.settings ?? []).filter(
    (setting) =>
      (source === 'All' || (source === 'Configured') === (setting.source === 'configured')) &&
      (needle === '' || setting.key.toLowerCase().includes(needle)),
  );

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Configuration</Typography.Title>
      {state.status === 'failed' && <Failure title="Configuration could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && (
        <Typography.Text>
          {shown.file === null
            ? `Read by ${shown.server ?? 'the answering server'}; the server was given it as a document, not a file, at ${formatInstant(shown.readAt)}.`
            : `Read by ${shown.server ?? 'the answering server'} from ${shown.file}; the server was given it at ${formatInstant(shown.readAt)}.`}
        </Typography.Text>
      )}
      <Alert
        type="info"
        showIcon
        aria-label="Static configuration"
        message="Static configuration"
        description={
          <span>
            Every setting here is static: the server reads it from its configuration file when it starts, and a change
            takes effect only after a restart. Secrets are withheld. The replicated configuration is the Raft server
            configuration: see <RouteLink to={{ view: 'members' }}>Raft Members</RouteLink>.
          </span>
        }
      />
      <Space wrap>
        <Radio.Group
          optionType="button"
          value={source}
          onChange={(event) => setSource(event.target.value as SourceFilter)}
          options={SOURCES.map((option) => ({ label: option, value: option }))}
        />
        <Input
          aria-label="Filter settings"
          placeholder="Setting"
          allowClear
          style={{ width: 260 }}
          value={text}
          onChange={(event) => setText(event.target.value)}
        />
      </Space>
      {state.status !== 'failed' && (
        <Table<Setting>
          rowKey={(setting) => setting.key}
          loading={state.status === 'loading'}
          dataSource={settings}
          pagination={false}
          size="small"
          columns={[
            { title: 'Setting', render: (_, setting) => setting.key },
            { title: 'Value', render: (_, setting) => value(setting) },
            {
              title: 'Source',
              render: (_, setting) => <Tag>{setting.source === 'configured' ? 'Configured' : 'Default'}</Tag>,
            },
            { title: 'Change', render: (_, setting) => (setting.restartRequired ? 'Restart required' : 'Applies at once') },
          ]}
        />
      )}
    </Space>
  );
}
