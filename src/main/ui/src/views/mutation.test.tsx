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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { AccessProvider, unrestricted } from '../access';
import { ApiError, type Read } from '../api/client';
import { RouterProvider } from '../routing';
import { Mutation, outcomeOf } from './Mutation';
import { read } from './testing';

// UI-5 task list, decision 6: every mutation shows what it changes and where, reviews its impact, and reports the
// outcome the server gave, an unknown one never as a failure (UI/UX design section 11).

beforeEach(() => window.history.pushState(null, '', '/ui/access/tokens'));

const refusal = (status: number, code: string, retryable = false) =>
  new ApiError('/v1/acl/token', status, code, `refused: ${code}`, retryable, 'req-42', null);

/** A submit answering each of `answers` in turn: a value is a commit at that index, an error a refusal. */
function submits(...answers: (number | Error)[]) {
  const sent: number[] = [];
  const submit = (): Promise<Read<unknown>> => {
    sent.push(sent.length + 1);
    const answer = answers.shift();
    if (answer instanceof Error) return Promise.reject(answer);
    return read(true, answer ?? null, { requestId: 'req-42' });
  };
  return { sent, submit };
}

function renderMutation(submit: () => Promise<Read<unknown>>, options: {
  mode?: 'none' | 'token';
  idempotent?: boolean;
  refresh?: () => Promise<string>;
  closed?: () => void;
} = {}) {
  render(
    <RouterProvider uiPath="/ui/">
      <AccessProvider value={unrestricted(options.mode ?? 'token')}>
        <Mutation
          title="Delete token"
          identity={[['Accessor ID', '6a1f0c2e'], ['Description', 'web deployer']]}
          review={<p>The client of node client-a becomes not ready.</p>}
          action="Delete"
          idempotent={options.idempotent ?? true}
          submit={submit}
          refresh={options.refresh}
          onClose={options.closed ?? (() => undefined)}
        />
      </AccessProvider>
    </RouterProvider>,
  );
}

const confirm = () => fireEvent.click(screen.getByText('Delete', { selector: 'button, button span' }));
const outcome = () => screen.findByLabelText('Outcome');

describe('the mutation workflow', () => {
  it('reviews the resource, its identity and the impact, and submits nothing until confirmed', () => {
    const { sent, submit } = submits(7);
    let closed = 0;
    renderMutation(submit, { closed: () => (closed += 1) });

    const review = screen.getByLabelText('Delete token');
    expect(review.textContent).toContain('Accessor ID6a1f0c2e');
    expect(review.textContent).toContain('The client of node client-a becomes not ready.');
    expect(review.textContent).not.toContain('No access control');
    fireEvent.click(screen.getByText('Cancel', { selector: 'button, button span' }));

    expect(sent).toEqual([]);
    expect(closed).toBe(1);
  });

  it('repeats the no-access-control warning in the review on a server without access control', () => {
    renderMutation(submits(7).submit, { mode: 'none' });

    expect(screen.getByLabelText('Delete token').textContent).toContain(
      'No access control: anyone who can reach this API can change the cluster.');
  });

  it('reports a commit with its applied index and links its audit events', async () => {
    renderMutation(submits(41).submit);

    confirm();

    const reported = await outcome();
    expect(reported.textContent).toContain('Committed and applied at index 41.');
    const audit = screen.getByText('Audit events of this request', { selector: 'a' });
    expect(audit.getAttribute('href')).toBe('/ui/events?category=audit&correlation=req-42');
    // UI-6 Tenants, Namespaces and Audit task list, Phase 3: and its durable records, in the audit file.
    expect(screen.getByText('Audit records of this request', { selector: 'a' }).getAttribute('href'))
      .toBe('/ui/audit?requestId=req-42');
  });

  it('tells a validation refusal, an integrity refusal and a missing permission apart', async () => {
    for (const [error, text] of [
      [refusal(400, 'invalid_token'), 'Rejected by validation: refused: invalid_token'],
      [refusal(409, 'acl_lockout'), "Refused to protect the cluster's integrity (acl_lockout): refused: acl_lockout"],
      [refusal(409, 'accessor_in_use'), 'Rejected: it conflicts with the current state (accessor_in_use): refused: accessor_in_use'],
      [refusal(403, 'permission_denied'), 'Refused: your token lacks the permission. refused: permission_denied'],
      [refusal(404, 'acl_not_found'), 'Not found: refused: acl_not_found'],
    ] as const) {
      renderMutation(submits(error).submit);
      confirm();
      expect((await outcome()).textContent, error.code).toContain(text);
      expect(screen.getByText('Audit events of this request', { selector: 'a' })).toBeInTheDocument();
      expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
      cleanup();
    }
  });

  it('offers to submit again when the server says retrying may succeed', async () => {
    const { sent, submit } = submits(refusal(503, 'leader_unavailable', true), 43);
    renderMutation(submit, { idempotent: false });

    confirm();
    expect((await outcome()).textContent).toContain('Not applied: refused: leader_unavailable. Retrying may succeed.');
    fireEvent.click(screen.getByText('Submit again', { selector: 'button, button span' }));

    expect((await screen.findByText(/Committed and applied at index 43/)).textContent).toBeTruthy();
    expect(sent).toEqual([1, 2]);
  });

  it('never shows an unknown outcome as a failure, and offers to submit an idempotent change again only after reading the state', async () => {
    const { sent, submit } = submits(new ApiError('/v1/acl/token', 0, 'outcome_unknown', 'no answer', false, null, null), 44);
    renderMutation(submit, { refresh: () => Promise.resolve('The token still exists.') });

    confirm();
    const reported = await outcome();
    expect(reported.textContent).toContain(
      'Outcome unknown: the request was sent and no answer came. The change may or may not have been applied.');
    expect(reported.textContent).not.toContain('failed');
    expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
    fireEvent.click(screen.getByText('Read the current state', { selector: 'button, button span' }));
    expect(await screen.findByText('The token still exists.')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Submit again', { selector: 'button, button span' }));

    expect(await screen.findByText(/Committed and applied at index 44/)).toBeInTheDocument();
    expect(sent).toEqual([1, 2]);
  });

  it('never offers to submit a change that is not idempotent again after an unknown outcome', async () => {
    const { submit } = submits(new ApiError('/v1/acl/token', 502, 'http_502', 'Bad Gateway', false, null, null));
    renderMutation(submit, { idempotent: false, refresh: () => Promise.resolve('One token has this description.') });

    confirm();
    expect((await outcome()).textContent).toContain('Outcome unknown');
    fireEvent.click(screen.getByText('Read the current state', { selector: 'button, button span' }));
    await screen.findByText('One token has this description.');

    expect(screen.queryByText('Submit again', { selector: 'button, button span' })).toBeNull();
  });

  // UI-6 Key/Value task list, decision 5: a stale compare-and-set answers false, 200, and was not applied.
  it('tells an answer that refuses the change as the outcome it stands for, with what the page says of it', async () => {
    render(
      <RouterProvider uiPath="/ui/">
        <AccessProvider value={unrestricted('token')}>
          <Mutation
            title="Update app/config"
            identity={[['Key', 'app/config']]}
            action="Save"
            idempotent
            submit={() => read(false, null, { requestId: 'req-43' })}
            judge={(answer) => (answer.data === false
              ? { kind: 'conflict', code: 'stale', message: 'the entry changed after it was read' } : null)}
            detail={() => <p>Expected index 24, now at 25.</p>}
            onClose={() => undefined}
          />
        </AccessProvider>
      </RouterProvider>,
    );

    fireEvent.click(screen.getByText('Save', { selector: 'button, button span' }));
    const reported = await outcome();
    expect(reported.textContent).toContain(
      'Rejected: it conflicts with the current state (stale): the entry changed after it was read');
    expect(reported.textContent).toContain('Expected index 24, now at 25.');
    expect(reported.textContent).not.toContain('Committed');
    expect(screen.getByText('Audit events of this request')).toBeInTheDocument();
  });

  it('reads a request body over the server limit as a validation refusal, which was not applied', () => {
    expect(outcomeOf(new ApiError('/v1/kv/a', 413, 'request_too_large', 'The request body is longer than 524288 bytes',
      false, 'req-44', null))).toMatchObject({ kind: 'validation', code: 'request_too_large' });
  });
});
