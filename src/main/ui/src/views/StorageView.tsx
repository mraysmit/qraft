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

import { Alert, Descriptions, Space, Typography } from 'antd';
import type { QraftApi } from '../api/client';
import type { Storage } from '../api/schemas';
import { useRead } from '../useRead';
import { Failure, formatBytes, formatInstant } from './common';

/** The persistent banner of fenced storage (UI/UX design section 3.4): it cannot be dismissed. */
export function FencedBanner({ storage }: { storage: Storage }) {
  return (
    <Alert
      type="error"
      showIcon
      aria-label="Storage fenced"
      message="Storage fenced"
      description={`The storage of ${storage.answeredBy} is fenced: ${storage.fenceReason ?? 'no reason given'}. It writes nothing more until it is restarted.`}
    />
  );
}

function snapshot(storage: Storage): string {
  if (storage.snapshotLastIndex === 0) return 'No snapshot yet';
  const taken =
    storage.snapshotAgeSeconds === null || storage.snapshotAgeSeconds < 0 ? '' : `, taken ${storage.snapshotAgeSeconds} s ago`;
  return `Index ${storage.snapshotLastIndex}, term ${storage.snapshotLastTerm}${taken}`;
}

/**
 * Storage & Snapshots (UI/UX design section 9.3; UI-3): the answering server's storage, its snapshot, the first log
 * index still held, its log's indexes, the transition queue and the last snapshot failure, with fenced storage in a
 * persistent banner.
 */
export function StorageView({ api }: { api: QraftApi }) {
  const [state, again] = useRead(() => api.storage(), [api]);
  const shown = state.status === 'ready' ? state.value.data : state.previous?.data;

  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Typography.Title level={2}>Storage & Snapshots</Typography.Title>
      {state.status === 'failed' && <Failure title="Storage could not be read" error={state.error} onRetry={again} />}
      {shown !== undefined && shown.fenced && <FencedBanner storage={shown} />}
      {shown !== undefined && (
        <Descriptions column={1} bordered size="small" title={`As ${shown.answeredBy} holds it`}>
          <Descriptions.Item label="Size">
            {shown.storageBytes === null ? 'Not known: the server has no storage directory' : formatBytes(shown.storageBytes)}
          </Descriptions.Item>
          <Descriptions.Item label="Snapshot">{snapshot(shown)}</Descriptions.Item>
          <Descriptions.Item label="First log index held">{String(shown.firstLogIndex)}</Descriptions.Item>
          <Descriptions.Item label="Log">
            {`Last ${shown.lastLogIndex}, committed ${shown.commitIndex}, applied ${shown.lastApplied}`}
          </Descriptions.Item>
          <Descriptions.Item label="Transition queue">{`${shown.transitionQueueDepth} queued`}</Descriptions.Item>
          <Descriptions.Item label="Fencing">
            {shown.fenced ? `Fenced: ${shown.fenceReason ?? 'no reason given'}` : 'Not fenced'}
          </Descriptions.Item>
          <Descriptions.Item label="Last snapshot failure">
            {shown.lastSnapshotFailure === null
              ? 'No snapshot has failed since the server started.'
              : `Failed at ${formatInstant(shown.lastSnapshotFailure.at)}: ${shown.lastSnapshotFailure.message}`}
          </Descriptions.Item>
        </Descriptions>
      )}
    </Space>
  );
}
