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

import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { LiveProvider, PauseControl, POLL_MILLIS, RETRY_MILLIS, type LiveWait, useLiveRead } from './live';
import { manualScheduler } from './views/testing';

// UI-4 task list, decision 5: blocking queries, polling, pause and resume, resynchronization, and retry, with time
// driven by a scheduler the test fires, never a wall-clock wait.

interface Answer {
  value: number;
  index: number;
}

interface Call {
  wait: LiveWait;
  answer(value: Answer): Promise<void>;
  fail(error: unknown): Promise<void>;
}

/** A load whose every call waits until the test answers it, as a blocking query waits at the server. */
function deferredLoad() {
  const calls: Call[] = [];
  const load = (wait: LiveWait) =>
    new Promise<Answer>((resolve, reject) => {
      wait.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      calls.push({
        wait,
        answer: async (value) => act(async () => resolve(value)),
        fail: async (error) => act(async () => reject(error)),
      });
    });
  return { calls, load };
}

function Probe({ load, blocking }: { load: (wait: LiveWait) => Promise<Answer>; blocking: boolean }) {
  const [state] = useLiveRead(load, [], blocking ? (answer) => answer.index : undefined);
  let text = 'loading';
  if (state.status === 'ready') text = `value ${state.value.value}${state.resynchronized === true ? ' resynchronized' : ''}`;
  if (state.status === 'failed') text = `failed, showing ${state.previous?.value ?? 'nothing'}`;
  return <output>{text}</output>;
}

function view(load: (wait: LiveWait) => Promise<Answer>, blocking: boolean) {
  const time = manualScheduler();
  render(
    <LiveProvider scheduler={time.scheduler}>
      <PauseControl />
      <Probe load={load} blocking={blocking} />
    </LiveProvider>,
  );
  const shown = () => document.querySelector('output')?.textContent;
  return { time, shown };
}

describe('live reads', () => {
  it('send a blocking read again at once with the last index and its wait, and show its answer when it comes', async () => {
    const { calls, load } = deferredLoad();
    const { time, shown } = view(load, true);

    expect(calls[0]?.wait.index).toBeUndefined();
    await calls[0]?.answer({ value: 1, index: 40 });
    expect(shown()).toBe('value 1');
    expect(calls[1]?.wait).toMatchObject({ index: 40, wait: '30s' });
    expect(time.pending).toHaveLength(0);

    await calls[1]?.answer({ value: 2, index: 41 });
    expect(shown()).toBe('value 2');
    expect(calls[2]?.wait.index).toBe(41);
  });

  it('start again when the index goes back, and say the view was resynchronized', async () => {
    const { calls, load } = deferredLoad();
    const { shown } = view(load, true);

    await calls[0]?.answer({ value: 1, index: 40 });
    await calls[1]?.answer({ value: 9, index: 3 });

    expect(shown()).toBe('value 9 resynchronized');
    expect(calls[2]?.wait.index).toBe(3);
    await calls[2]?.answer({ value: 10, index: 4 });
    expect(shown()).toBe('value 10');
  });

  it('wait before the next blocking read when one is answered at once with nothing new', async () => {
    const { calls, load } = deferredLoad();
    const { time } = view(load, true);

    await calls[0]?.answer({ value: 1, index: 40 });
    await calls[1]?.answer({ value: 1, index: 40 });

    expect(calls).toHaveLength(2);
    expect(time.pending.map((entry) => entry.ms)).toEqual([POLL_MILLIS]);
    act(() => time.fire());
    expect(calls[2]?.wait.index).toBe(40);
  });

  it('read a read without an index again every few seconds', async () => {
    const { calls, load } = deferredLoad();
    const { time, shown } = view(load, false);

    await calls[0]?.answer({ value: 1, index: 0 });
    expect(calls).toHaveLength(1);
    expect(time.pending.map((entry) => entry.ms)).toEqual([POLL_MILLIS]);

    act(() => time.fire());
    expect(calls[1]?.wait.index).toBeUndefined();
    await calls[1]?.answer({ value: 2, index: 0 });
    expect(shown()).toBe('value 2');
  });

  it('read nothing while paused, keep what it shows, and read again at once on resuming', async () => {
    const { calls, load } = deferredLoad();
    const { time, shown } = view(load, true);
    await calls[0]?.answer({ value: 1, index: 40 });
    const waiting = calls[1];

    fireEvent.click(screen.getByText('Pause live updates'));

    expect(waiting?.wait.signal.aborted).toBe(true);
    expect(screen.getByText('Live updates paused')).toBeInTheDocument();
    expect(shown()).toBe('value 1');
    expect(calls).toHaveLength(2);
    expect(time.pending).toHaveLength(0);

    fireEvent.click(screen.getByText('Resume live updates'));

    expect(calls).toHaveLength(3);
    expect(calls[2]?.wait.index).toBeUndefined();
    await calls[2]?.answer({ value: 2, index: 42 });
    expect(shown()).toBe('value 2');
    expect(screen.getByText('Live updates on')).toBeInTheDocument();
  });

  it('keep the last answer when a read fails, and try again after a few seconds', async () => {
    const { calls, load } = deferredLoad();
    const { time, shown } = view(load, true);
    await calls[0]?.answer({ value: 1, index: 40 });

    await calls[1]?.fail(new Error('connection reset'));

    expect(shown()).toBe('failed, showing 1');
    expect(time.pending.map((entry) => entry.ms)).toEqual([RETRY_MILLIS]);
    act(() => time.fire());
    expect(calls[2]?.wait.index).toBeUndefined();
    await calls[2]?.answer({ value: 3, index: 45 });
    expect(shown()).toBe('value 3');
  });
});
