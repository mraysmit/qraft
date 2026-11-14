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

import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';

/**
 * A read in progress, done, or failed. While a read is repeated, the last result it gave is kept as `previous`, so a
 * view can go on showing it, marked as possibly out of date (UI/UX design section 13).
 */
export type ReadState<T> =
  | { status: 'loading'; previous: T | undefined }
  | { status: 'ready'; value: T }
  | { status: 'failed'; error: unknown; previous: T | undefined };

/** Runs `load` when the view opens and whenever `deps` change; the returned function runs it again. */
export function useRead<T>(load: () => Promise<T>, deps: DependencyList): [ReadState<T>, () => void] {
  const [state, setState] = useState<ReadState<T>>({ status: 'loading', previous: undefined });
  const [attempt, setAttempt] = useState(0);
  const last = useRef<T | undefined>(undefined);

  useEffect(() => {
    let current = true;
    setState({ status: 'loading', previous: last.current });
    load().then(
      (value) => {
        if (!current) return;
        last.current = value;
        setState({ status: 'ready', value });
      },
      (error: unknown) => {
        if (current) setState({ status: 'failed', error, previous: last.current });
      },
    );
    return () => {
      current = false;
    };
    // `load` is a new function on every render; `deps` name what it reads.
  }, [...deps, attempt]);

  const again = useCallback(() => setAttempt((count) => count + 1), []);
  return [state, again];
}
