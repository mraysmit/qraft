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

import { Alert, Button, Card, Space, Typography } from 'antd';
import { type ReactNode, useState } from 'react';
import { useAccess } from '../access';
import { ApiError, MalformedResponseError, type Read } from '../api/client';
import { RouteLink } from '../routing';

/** How a mutation ended, as the interface tells it (UI/UX design section 11). */
export type Outcome =
  | { kind: 'committed'; index: number | null }
  | { kind: 'validation' | 'conflict' | 'integrity' | 'permission' | 'not-found' | 'session'; code: string; message: string }
  | { kind: 'retryable'; code: string; message: string }
  | { kind: 'unknown'; detail: string };

const INTEGRITY = new Set(['acl_lockout', 'node_name_reserved', 'refused', 'self_deletion']);

/**
 * The outcome a refusal or failure stands for. A request sent and never answered, a gateway's answer without the
 * server's envelope, an internal error and an answer in a form the interface does not read are all unknown: the
 * change may have been applied. A refusal the server marks retryable was not applied, and may be sent again.
 */
export function outcomeOf(error: unknown): Outcome {
  if (error instanceof MalformedResponseError) return { kind: 'unknown', detail: error.message };
  if (!(error instanceof ApiError)) return { kind: 'unknown', detail: error instanceof Error ? error.message : String(error) };
  const { code, message } = error;
  if (error.status === 0 || error.code.startsWith('http_5') || error.code === 'internal_error') {
    return { kind: 'unknown', detail: message };
  }
  if (error.retryable) return { kind: 'retryable', code, message };
  if (error.status === 403) return { kind: code === 'token_not_found' ? 'session' : 'permission', code, message };
  if (INTEGRITY.has(code)) return { kind: 'integrity', code, message };
  if (error.status === 404) return { kind: 'not-found', code, message };
  if (error.status === 409) return { kind: 'conflict', code, message };
  // A body over the server's limit is refused before it is read (UI-6 Key/Value task list, decision 7).
  if (error.status === 400 || error.status === 413) return { kind: 'validation', code, message };
  return { kind: 'retryable', code, message };
}

function outcomeText(outcome: Outcome): string {
  switch (outcome.kind) {
    case 'committed':
      return outcome.index === null ? 'Committed and applied.' : `Committed and applied at index ${outcome.index}.`;
    case 'validation':
      return `Rejected by validation: ${outcome.message}`;
    case 'conflict':
      return `Rejected: it conflicts with the current state (${outcome.code}): ${outcome.message}`;
    case 'integrity':
      return `Refused to protect the cluster's integrity (${outcome.code}): ${outcome.message}`;
    case 'permission':
      return `Refused: your token lacks the permission. ${outcome.message}`;
    case 'not-found':
      return `Not found: ${outcome.message}`;
    case 'session':
      return 'The server no longer knows your token: sign in again.';
    case 'retryable':
      return `Not applied: ${outcome.message}. Retrying may succeed.`;
    case 'unknown':
      return 'Outcome unknown: the request was sent and no answer came. The change may or may not have been applied.';
  }
}

type Phase =
  | { status: 'review' }
  | { status: 'submitting' }
  | { status: 'done'; outcome: Outcome; requestId: string | null; read: Read<unknown> | null };

/**
 * The mutation workflow (UI-5 task list, decision 6): the resource and its scope, a review of the impact, the
 * submission, and the structured outcome with a link to the request's audit events. An unknown outcome is never shown
 * as a failure: the interface offers to read the current state, and then to submit again only an idempotent change.
 */
export function Mutation<T>({ title, identity, review, action, idempotent, submit, refresh, committed, judge, detail,
  onClose }: {
  title: string;
  /** The resource's identity and scope, each a label and value. */
  identity: [string, string][];
  /** The impact: what changes, and what depends on it. */
  review?: ReactNode;
  action: string;
  /** Whether sending the change twice is the same as once. */
  idempotent: boolean;
  submit: () => Promise<Read<T>>;
  /** Reads the current state, for an unknown outcome; answers what it shows. */
  refresh?: (() => Promise<ReactNode>) | undefined;
  /** What a commit shows beside its index, such as a new secret. */
  committed?: (read: Read<T>) => ReactNode;
  /**
   * The outcome an answer stands for when it refuses the change, as a stale compare-and-set's `false` does; null for
   * a commit (UI-6 Key/Value task list, decision 5).
   */
  judge?: ((read: Read<T>) => Outcome | null) | undefined;
  /** What the page says of an outcome that is not a commit, such as a stale write's difference. */
  detail?: ((outcome: Outcome) => ReactNode) | undefined;
  onClose: () => void;
}) {
  const { mode } = useAccess();
  const [phase, setPhase] = useState<Phase>({ status: 'review' });
  const [state, setState] = useState<{ reading: boolean; shown: ReactNode | null }>({ reading: false, shown: null });

  const send = () => {
    setPhase({ status: 'submitting' });
    setState({ reading: false, shown: null });
    submit().then(
      (answer) => setPhase({ status: 'done', outcome: judge?.(answer) ?? { kind: 'committed', index: answer.index },
        requestId: answer.requestId, read: answer }),
      (error: unknown) => setPhase({ status: 'done', outcome: outcomeOf(error),
        requestId: error instanceof ApiError || error instanceof MalformedResponseError ? error.requestId : null, read: null }),
    );
  };

  const readState = () => {
    if (refresh === undefined) return;
    setState({ reading: true, shown: null });
    refresh().then(
      (shown) => setState({ reading: false, shown }),
      (error: unknown) => setState({ reading: false,
        shown: `The current state could not be read: ${error instanceof Error ? error.message : String(error)}` }),
    );
  };

  return (
    <Card>
      <section aria-label={title}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>{title}</Typography.Title>
        <dl style={{ display: 'grid', gridTemplateColumns: 'max-content auto', columnGap: 16, rowGap: 4 }}>
          {identity.map(([label, value]) => (
            <div key={label} style={{ display: 'contents' }}>
              <dt style={{ fontWeight: 600 }}>{label}</dt>
              <dd style={{ margin: 0 }}>{value}</dd>
            </div>
          ))}
        </dl>
        {review}
        {mode === 'none' && (
          <Alert type="warning" message="No access control: anyone who can reach this API can change the cluster." />
        )}
        {phase.status !== 'done' && (
          <Space style={{ marginTop: 12 }}>
            <Button type="primary" danger={action === 'Delete' || action === 'Remove' || action === 'Destroy'}
              loading={phase.status === 'submitting'}
              onClick={send}>
              {action}
            </Button>
            <Button disabled={phase.status === 'submitting'} onClick={onClose}>Cancel</Button>
          </Space>
        )}
      </section>
      {phase.status === 'done' && (
        <section aria-label="Outcome" style={{ marginTop: 12 }}>
          <Alert
            type={phase.outcome.kind === 'committed' ? 'success' : phase.outcome.kind === 'unknown' ? 'warning' : 'error'}
            message={outcomeText(phase.outcome)}
            description={phase.outcome.kind === 'unknown' ? phase.outcome.detail : undefined}
          />
          {phase.outcome.kind === 'committed' && phase.read !== null && committed?.(phase.read as Read<T>)}
          {phase.outcome.kind !== 'committed' && detail?.(phase.outcome)}
          {phase.requestId !== null && (
            <p>
              <RouteLink to={{ view: 'events', category: 'audit', correlation: phase.requestId }}>
                Audit events of this request
              </RouteLink>
            </p>
          )}
          {state.shown !== null && <p>{state.shown}</p>}
          <Space>
            {phase.outcome.kind === 'unknown' && refresh !== undefined && (
              <Button loading={state.reading} onClick={readState}>Read the current state</Button>
            )}
            {(phase.outcome.kind === 'retryable'
              || (phase.outcome.kind === 'unknown' && idempotent && state.shown !== null)) && (
              <Button onClick={send}>Submit again</Button>
            )}
            <Button onClick={onClose}>Close</Button>
          </Space>
        </section>
      )}
    </Card>
  );
}
