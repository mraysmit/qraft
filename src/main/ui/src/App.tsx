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

import { Layout, Space, Typography } from 'antd';
import type { Bootstrap } from './bootstrap';

/**
 * The application shell. Step 4 of the task list adds the status strip and the Stage 1 views; until then it names
 * the product and what the server says about its security, in words rather than colour alone.
 */
export function App({ bootstrap }: { bootstrap: Bootstrap }) {
  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center' }}>
        <Typography.Title level={1} style={{ color: '#fff', margin: 0, fontSize: 20 }}>
          Qraft
        </Typography.Title>
      </Layout.Header>
      <Layout.Content style={{ padding: 24 }}>
        <Space direction="vertical">
          <Typography.Text>Security profile: {bootstrap.securityProfile}</Typography.Text>
          <Typography.Text>Sign-in: {bootstrap.authMode}</Typography.Text>
        </Space>
      </Layout.Content>
    </Layout>
  );
}
