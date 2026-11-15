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

import { Space, Typography } from 'antd';
import {
  createContext,
  type DependencyList,
  type MutableRefObject,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { isAbort } from './api/client';
import type { ReadState } from './useRead';

// Live updates (UI/UX design section 12; UI-4 task list, decision 5): a read with an index is a blocking query, sent
// again as soon as it answers; a read without one is read again every few seconds. One control pauses them all.

/** How long a read without an index waits before it is read again, and a blocking query after an unchanged answer. */
export const POLL_MILLIS = 5000;
/** How long a failed read waits before it is tried again. */
export const RETRY_MILLIS = 5000;
/** The longest a blocking query waits at the server. */
export const BLOCKING_WAIT = '30s';

/** Runs `run` after `ms` milliseconds; the returned function cancels it. Tests give one they fire by hand. */
export interface Scheduler {
  after(ms: number, run: () => void): () => void;
}

const TIMERS: Scheduler = {
  after(ms, run) {
    const timer = setTimeout(run, ms);
    return () => clearTimeout(timer);
  },
};

interface Live {
  paused: boolean;
  setPaused(paused: boolean): void;
  scheduler: Scheduler;
}

const LiveContext = createContext<Live>({ paused: false, setPaused: () => undefined, scheduler: TIMERS });

/** Live updates for the views inside it, on until paused. */
export function LiveProvider({ scheduler = TIMERS, children }: { scheduler?: Scheduler | undefined; children: ReactNode }) {
  const [paused, setPaused] = useState(false);
  const live = useMemo(() => ({ paused, setPaused, scheduler }), [paused, scheduler]);
  return <LiveContext.Provider value={live}>{children}</LiveContext.Provider>;
}

export function useLive(): Live {
  return useContext(LiveContext);
}

/**
 * The top bar's control: whether the views follow the server, and the button that pauses or resumes them. It is a
 * plain button: Ant Design's button styles made every table mounted after them three times slower to render in the
 * frontend tests' jsdom (UI-4 task list, Phase 2), which have a 5 s limit per test.
 */
export function PauseControl({ color }: { color?: string }) {
  const { paused, setPaused } = useLive();
  const text = color === undefined ? {} : { color };
  return (
    <Space>
      <Typography.Text style={text}>{paused ? 'Live updates paused' : 'Live updates on'}</Typography.Text>
      <button
        type="button"
        onClick={() => setPaused(!paused)}
        style={{
          ...text,
          background: 'transparent',
          border: '1px solid currentColor',
          borderRadius: 4,
          padding: '0 8px',
          height: 24,
          cursor: 'pointer',
          font: 'inherit',
        }}
      >
        {paused ? 'Resume live updates' : 'Pause live updates'}
      </button>
    </Space>
  );
}

/** What a live read is given: for a blocking query the index to wait past and how long; the signal that ends it. */
export interface LiveWait {
  index?: number;
  wait?: string;
  signal: AbortSignal;
}

interface Loop<T> {
  alive: boolean;
  /** Whether the first read has answered: only then do pause and resume stop and start the reads that follow. */
  started: boolean;
  /** The index of the last answer, which the next blocking query waits past. */
  index: number | null;
  reading?: AbortController | undefined;
  cancel?: (() => void) | undefined;
  settings: MutableRefObject<Settings<T>>;
  last: MutableRefObject<T | undefined>;
  publish(state: ReadState<T>): void;
}

interface Settings<T> {
  load: (wait: LiveWait) => Promise<T>;
  indexOf: ((value: T) => number | null) | undefined;
  live: Live;
}

function read<T>(loop: Loop<T>, index: number | undefined): void {
  const reading = new AbortController();
  loop.reading = reading;
  loop.cancel = undefined;
  const wait: LiveWait = index === undefined ? { signal: reading.signal }
    : { index, wait: BLOCKING_WAIT, signal: reading.signal };
  loop.settings.current.load(wait).then(
    (value) => {
      if (!loop.alive || reading.signal.aborted) return;
      const answered = loop.settings.current.indexOf?.(value) ?? null;
      const resynchronized = loop.index !== null && answered !== null && answered < loop.index;
      loop.index = answered;
      loop.started = true;
      loop.last.current = value;
      loop.publish({ status: 'ready', value, resynchronized });
      follow(loop, index !== undefined && answered === index);
    },
    (error: unknown) => {
      if (!loop.alive || reading.signal.aborted || isAbort(error)) return;
      loop.index = null;
      loop.started = true;
      loop.publish({ status: 'failed', error, previous: loop.last.current });
      const { live } = loop.settings.current;
      if (!live.paused) loop.cancel = live.scheduler.after(RETRY_MILLIS, () => read(loop, undefined));
    },
  );
}

/** The read after an answer: a blocking query at once, unless it answered at once with nothing new; else a poll. */
function follow<T>(loop: Loop<T>, unchanged: boolean): void {
  const { indexOf, live } = loop.settings.current;
  if (!loop.alive || live.paused) return;
  const index = loop.index;
  if (indexOf === undefined || index === null || index === 0) {
    loop.cancel = live.scheduler.after(POLL_MILLIS, () => read(loop, undefined));
  } else if (unchanged) {
    // A server that answers a blocking query at once with the same index would otherwise be asked without pause.
    loop.cancel = live.scheduler.after(POLL_MILLIS, () => read(loop, index));
  } else {
    read(loop, index);
  }
}

/**
 * `useRead` made live: runs `load` when the view opens and whenever `deps` change, then follows the server, a blocking
 * query when `indexOf` gives the index an answer reflects, a read every few seconds otherwise. An index lower than the
 * last one, as when another server answers or the state was restored, is a fresh start, and the answer says it was
 * resynchronized. A failed read keeps the last answer and is tried again. Paused, nothing more is read and the last
 * answer stays; resumed, the view is read again at once. The returned function reads again now.
 */
export function useLiveRead<T>(
  load: (wait: LiveWait) => Promise<T>,
  deps: DependencyList,
  indexOf?: (value: T) => number | null,
): [ReadState<T>, () => void] {
  const live = useLive();
  const [state, setState] = useState<ReadState<T>>({ status: 'loading', previous: undefined });
  const [attempt, setAttempt] = useState(0);
  const last = useRef<T | undefined>(undefined);
  const settings = useRef<Settings<T>>({ load, indexOf, live });
  settings.current = { load, indexOf, live };
  const current = useRef<Loop<T> | null>(null);

  useEffect(() => {
    const loop: Loop<T> = { alive: true, started: false, index: null, settings, last, publish: setState };
    current.current = loop;
    setState({ status: 'loading', previous: last.current });
    read(loop, undefined);
    return () => {
      loop.alive = false;
      loop.reading?.abort();
      loop.cancel?.();
    };
    // `load` and `indexOf` are new functions on every render; `deps` name what they read.
  }, [...deps, attempt]);

  useEffect(() => {
    const loop = current.current;
    if (loop === null || !loop.started) return;
    loop.reading?.abort();
    loop.cancel?.();
    loop.cancel = undefined;
    if (!live.paused) read(loop, undefined);
  }, [live.paused]);

  const again = useCallback(() => setAttempt((count) => count + 1), []);
  return [state, again];
}
