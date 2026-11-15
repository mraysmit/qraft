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

import { type DependencyList, type MutableRefObject, useCallback, useEffect, useRef, useState } from 'react';
import { type EventQuery, isAbort, type QraftApi, type WatchEnd } from './api/client';
import type { QraftEvent } from './api/schemas';
import { RETRY_MILLIS, useLive } from './live';

// One server's events, read and followed (UI-4 task list, decision 6): the journal is read from its start in pages,
// then the watch follows from the last cursor. A history gap or another boot reads the journal again from its start;
// an overflowed watch is resumed from the last event it delivered; a watch that fails is tried again from its cursor.

/** The most events a page asks for. */
export const EVENT_PAGE_LIMIT = 1000;

/** Which events a feed follows: a category, a scope and a service; the cursor and limit are the feed's own. */
export type FeedQuery = Omit<EventQuery, 'after' | 'limit'>;

/** Why the feed read the journal again, or resumed a watch. */
export type Missed = 'history_gap' | 'other_boot' | 'overflow';

export interface EventFeed {
  /** The events read, oldest first, at most the journal's capacity. */
  events: QraftEvent[];
  /** The cursor of the last event read, or of the journal's start. */
  cursor: string | null;
  /** How many events the answering server's journal keeps. */
  capacity: number | null;
  answeredBy: string | null;
  phase: 'reading' | 'following' | 'paused' | 'retrying';
  missed: Missed | null;
  /** Why the feed stopped, while it waits to try again. */
  error: unknown;
}

const INITIAL: EventFeed = {
  events: [],
  cursor: null,
  capacity: null,
  answeredBy: null,
  phase: 'reading',
  missed: null,
  error: null,
};

interface Session {
  alive: boolean;
  feed: EventFeed;
  reading?: AbortController | undefined;
  closeWatch?: (() => void) | undefined;
  cancelRetry?: (() => void) | undefined;
}

interface Settings {
  api: QraftApi;
  query: FeedQuery;
  live: ReturnType<typeof useLive>;
  publish(feed: EventFeed): void;
}

function update(session: Session, settings: MutableRefObject<Settings>, changes: Partial<EventFeed>): void {
  session.feed = { ...session.feed, ...changes };
  settings.current.publish(session.feed);
}

function kept(events: QraftEvent[], capacity: number | null): QraftEvent[] {
  return capacity === null || events.length <= capacity ? events : events.slice(events.length - capacity);
}

/** Reads the journal's pages after `after`, or from its start, then follows the watch unless paused. */
async function catchUp(session: Session, settings: MutableRefObject<Settings>, after: string | null): Promise<void> {
  const reading = new AbortController();
  session.reading = reading;
  const { api, query } = settings.current;
  const collected: QraftEvent[] = [];
  let next = after;
  try {
    for (;;) {
      const page = await api.events(
        next === null ? { ...query, limit: EVENT_PAGE_LIMIT } : { ...query, limit: EVENT_PAGE_LIMIT, after: next },
        { signal: reading.signal },
      );
      if (!session.alive || reading.signal.aborted) return;
      const { status } = page.data;
      if (status === 'history_gap' || status === 'other_boot' || status === 'invalid') {
        if (next === null) throw new Error(`The journal answered ${status} from its start`);
        update(session, settings, { missed: status === 'history_gap' ? 'history_gap' : 'other_boot' });
        collected.length = 0;
        after = null;
        next = null;
        continue;
      }
      collected.push(...page.data.events);
      next = page.data.next;
      update(session, settings, { capacity: page.data.capacity, answeredBy: page.answeredBy });
      if (status !== 'more') break;
    }
  } catch (error) {
    if (!session.alive || reading.signal.aborted || isAbort(error)) return;
    retry(session, settings, error);
    return;
  }
  const events = after === null ? collected : [...session.feed.events, ...collected];
  update(session, settings, { events: kept(events, session.feed.capacity), cursor: next, error: null });
  if (settings.current.live.paused) {
    update(session, settings, { phase: 'paused' });
    return;
  }
  follow(session, settings);
}

function follow(session: Session, settings: MutableRefObject<Settings>): void {
  const { api, query } = settings.current;
  const after = session.feed.cursor;
  update(session, settings, { phase: 'following' });
  session.closeWatch = api.watchEvents(after === null ? { ...query } : { ...query, after }, {
    onEvent(event) {
      if (!session.alive) return;
      update(session, settings, {
        events: kept([...session.feed.events, event], session.feed.capacity),
        cursor: event.cursor,
      });
    },
    onEnd(end: WatchEnd) {
      if (!session.alive) return;
      session.closeWatch = undefined;
      if (end.reason === 'overflow') {
        update(session, settings, { missed: 'overflow', phase: 'reading' });
        void catchUp(session, settings, end.resumeAfter);
      } else if (end.reason === 'history_gap' || end.reason === 'other_boot') {
        update(session, settings, { missed: end.reason, phase: 'reading' });
        void catchUp(session, settings, null);
      } else {
        retry(session, settings, new Error(`the server ended it (${end.reason})`));
      }
    },
    onError(error) {
      if (!session.alive) return;
      session.closeWatch = undefined;
      retry(session, settings, error);
    },
  });
}

function retry(session: Session, settings: MutableRefObject<Settings>, error: unknown): void {
  update(session, settings, { phase: 'retrying', error });
  const { live } = settings.current;
  if (live.paused) return;
  session.cancelRetry = live.scheduler.after(RETRY_MILLIS, () => {
    session.cancelRetry = undefined;
    if (session.alive) void catchUp(session, settings, session.feed.cursor);
  });
}

function stop(session: Session): void {
  session.reading?.abort();
  session.closeWatch?.();
  session.closeWatch = undefined;
  session.cancelRetry?.();
  session.cancelRetry = undefined;
}

/**
 * The answering server's events in `query`, read and then followed, for as long as the view shows them. Paused, the
 * watch is closed and the events read stay; resumed, the journal is read from the cursor and then followed again.
 * The returned function reads the journal again from its start.
 */
export function useEventFeed(api: QraftApi, query: FeedQuery, deps: DependencyList): [EventFeed, () => void] {
  const live = useLive();
  const [feed, setFeed] = useState<EventFeed>(INITIAL);
  const [attempt, setAttempt] = useState(0);
  const settings = useRef<Settings>({ api, query, live, publish: setFeed });
  settings.current = { api, query, live, publish: setFeed };
  const current = useRef<Session | null>(null);

  useEffect(() => {
    const session: Session = { alive: true, feed: INITIAL };
    current.current = session;
    setFeed(INITIAL);
    void catchUp(session, settings, null);
    return () => {
      session.alive = false;
      stop(session);
    };
    // `query` is a new object on every render; `deps` name what it holds.
  }, [api, ...deps, attempt]);

  useEffect(() => {
    const session = current.current;
    if (session === null) return;
    if (live.paused) {
      if (session.feed.phase === 'reading') return;
      stop(session);
      update(session, settings, { phase: 'paused' });
    } else if (session.feed.phase === 'paused' || session.feed.phase === 'retrying') {
      stop(session);
      update(session, settings, { phase: 'reading' });
      void catchUp(session, settings, session.feed.cursor);
    }
  }, [live.paused]);

  const again = useCallback(() => setAttempt((count) => count + 1), []);
  return [feed, again];
}
