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

import { createContext, type CSSProperties, type MouseEvent, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { type Consistency, DEFAULT_SCOPE, type Scope } from './api/client';
import { type EventCategory, EventCategorySchema } from './api/schemas';

// Each view has its own address below the interface's path, so reloading, bookmarking, sharing a link and the back
// and forward buttons work (UI/UX design section 4.4). The server answers every such address with index.html. The
// selected scope is the address's query, kept across the interface's links (UI-2 task list, decision 5), and so is the
// consistency chosen (UI-4 task list, decision 4).

export type Route =
  | { view: 'services' }
  | { view: 'service'; name: string }
  | { view: 'nodes' }
  | { view: 'checks' }
  | { view: 'cluster' }
  | { view: 'members' }
  | { view: 'storage' }
  | { view: 'events'; category?: EventCategory; correlation?: string }
  | { view: 'configuration' }
  | { view: 'access' }
  | { view: 'tokens' }
  | { view: 'token'; accessorId: string }
  | { view: 'policies' }
  | { view: 'policy'; id: string }
  | { view: 'explain' }
  /** The Key/Value browser at a folder, empty or ending in `/`, or at a key (UI-6 Key/Value task list, decision 3). */
  | { view: 'kv'; path: string }
  | { view: 'unknown' };

/**
 * The view an address below `uiPath` names. The Events page also takes its category and correlation ID from the
 * query, so that a mutation's outcome can link its audit events (UI-5 task list, decision 6).
 */
export function routeOf(pathname: string, uiPath: string, search = ''): Route {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  if (pathname === root.slice(0, -1)) return { view: 'services' };
  if (!pathname.startsWith(root)) return { view: 'unknown' };
  const rest = pathname.slice(root.length);
  if (rest === '' || rest === 'services') return { view: 'services' };
  if (rest === 'nodes') return { view: 'nodes' };
  if (rest === 'checks') return { view: 'checks' };
  if (rest === 'cluster') return { view: 'cluster' };
  if (rest === 'members') return { view: 'members' };
  if (rest === 'storage') return { view: 'storage' };
  if (rest === 'events') return eventsOf(search);
  if (rest === 'configuration') return { view: 'configuration' };
  // The folder or key is in the query: the server takes an address with an empty segment for none, and one whose
  // last segment has a dot for a file, and a key may have either.
  if (rest === 'kv') return { view: 'kv', path: new URLSearchParams(search).get('path') ?? '' };
  if (rest === 'access') return { view: 'access' };
  if (rest === 'access/tokens') return { view: 'tokens' };
  if (rest === 'access/policies') return { view: 'policies' };
  if (rest === 'access/explain') return { view: 'explain' };
  const token = single(rest, 'access/tokens/');
  if (token !== null) return { view: 'token', accessorId: token };
  const policy = single(rest, 'access/policies/');
  if (policy !== null) return { view: 'policy', id: policy };
  if (rest.startsWith('services/') && rest.length > 'services/'.length && !rest.slice('services/'.length).includes('/')) {
    try {
      return { view: 'service', name: decodeURIComponent(rest.slice('services/'.length)) };
    } catch {
      return { view: 'unknown' };
    }
  }
  return { view: 'unknown' };
}

/** The one segment after `prefix` in `rest`, decoded; null when there is not exactly one. */
function single(rest: string, prefix: string): string | null {
  if (!rest.startsWith(prefix)) return null;
  const value = rest.slice(prefix.length);
  if (value === '' || value.includes('/')) return null;
  try {
    return decodeURIComponent(value);
  } catch {
    return null;
  }
}

function eventsOf(search: string): Route {
  const query = new URLSearchParams(search);
  const route: { view: 'events'; category?: EventCategory; correlation?: string } = { view: 'events' };
  const category = EventCategorySchema.safeParse(query.get('category'));
  if (category.success) route.category = category.data;
  const correlation = query.get('correlation')?.trim();
  if (correlation) route.correlation = correlation;
  return route;
}

/** The scope an address's query names; a tenant or namespace that is missing or blank is the default's. */
export function scopeOf(search: string): Scope {
  const query = new URLSearchParams(search);
  const tenant = query.get('tenant')?.trim();
  const namespace = query.get('namespace')?.trim();
  return {
    tenant: tenant === undefined || tenant === '' ? DEFAULT_SCOPE.tenant : tenant,
    namespace: namespace === undefined || namespace === '' ? DEFAULT_SCOPE.namespace : namespace,
  };
}

/** The consistency an address's query names; anything else is the default. */
export function consistencyOf(search: string): Consistency {
  const chosen = new URLSearchParams(search).get('consistency');
  return chosen === 'consistent' || chosen === 'stale' ? chosen : 'default';
}

/**
 * The address of a view in a scope and consistency. A service name is one encoded segment, its dots included: the server takes a last
 * segment with a dot for a file, and a file that is not there is not found. The default scope and the default consistency
 * are left out of the query.
 */
export function hrefOf(route: Route, uiPath: string, scope: Scope = DEFAULT_SCOPE,
  consistency: Consistency = 'default'): string {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  const parameters = new URLSearchParams();
  if (scope.tenant !== DEFAULT_SCOPE.tenant || scope.namespace !== DEFAULT_SCOPE.namespace) {
    parameters.set('tenant', scope.tenant);
    parameters.set('namespace', scope.namespace);
  }
  if (consistency !== 'default') parameters.set('consistency', consistency);
  if (route.view === 'events') {
    if (route.category !== undefined) parameters.set('category', route.category);
    if (route.correlation !== undefined) parameters.set('correlation', route.correlation);
  }
  if (route.view === 'kv' && route.path !== '') parameters.set('path', route.path);
  const text = parameters.toString();
  const query = text === '' ? '' : `?${text}`;
  switch (route.view) {
    case 'services':
    case 'unknown':
      return root + query;
    case 'nodes':
      return `${root}nodes${query}`;
    case 'checks':
    case 'cluster':
    case 'members':
    case 'storage':
    case 'events':
    case 'configuration':
    case 'access':
    case 'kv':
      return `${root}${route.view}${query}`;
    case 'tokens':
    case 'policies':
    case 'explain':
      return `${root}access/${route.view}${query}`;
    case 'token':
      return `${root}access/tokens/${encodeURIComponent(route.accessorId)}${query}`;
    case 'policy':
      return `${root}access/policies/${encodeURIComponent(route.id)}${query}`;
    case 'service':
      return `${root}services/${encodeURIComponent(route.name).replaceAll('.', '%2E')}${query}`;
  }
}

interface Router {
  route: Route;
  scope: Scope;
  consistency: Consistency;
  uiPath: string;
  /** Opens a view in the current scope and consistency. */
  /** Opens `to` in the current scope, or in `inScope`. */
  navigate(to: Route, inScope?: Scope): void;
  /** Reads the current view in another scope. */
  selectScope(scope: Scope): void;
  /** Reads the current view in another consistency. */
  selectConsistency(consistency: Consistency): void;
}

const RouterContext = createContext<Router | null>(null);

export function RouterProvider({ uiPath, children }: { uiPath: string; children: ReactNode }) {
  const [location, setLocation] = useState(() => ({ pathname: window.location.pathname, search: window.location.search }));

  useEffect(() => {
    const follow = () => setLocation({ pathname: window.location.pathname, search: window.location.search });
    window.addEventListener('popstate', follow);
    return () => window.removeEventListener('popstate', follow);
  }, []);

  const route = useMemo(() => routeOf(location.pathname, uiPath, location.search),
    [location.pathname, location.search, uiPath]);
  const scope = useMemo(() => scopeOf(location.search), [location.search]);
  const consistency = useMemo(() => consistencyOf(location.search), [location.search]);

  const go = useCallback(
    (to: Route, inScope: Scope, inConsistency: Consistency) => {
      window.history.pushState(null, '', hrefOf(to, uiPath, inScope, inConsistency));
      setLocation({ pathname: window.location.pathname, search: window.location.search });
    },
    [uiPath],
  );

  const router = useMemo(
    () => ({
      route,
      scope,
      consistency,
      uiPath,
      navigate: (to: Route, inScope?: Scope) => go(to, inScope ?? scope, consistency),
      selectScope: (next: Scope) => go(route, next, consistency),
      selectConsistency: (next: Consistency) => go(route, scope, next),
    }),
    [route, scope, consistency, uiPath, go],
  );
  return <RouterContext.Provider value={router}>{children}</RouterContext.Provider>;
}

export function useRouter(): Router {
  const router = useContext(RouterContext);
  if (router === null) throw new Error('useRouter is used outside a RouterProvider');
  return router;
}

/**
 * A link to a view in the current scope, or in `scope`, such as a service identity's: a real address, so it can be
 * opened in a new tab, and followed in place.
 */
export function RouteLink({ to, children, style, scope: inScope }: {
  to: Route;
  children: ReactNode;
  style?: CSSProperties;
  scope?: Scope;
}) {
  const { uiPath, scope, consistency, navigate } = useRouter();
  const target = inScope ?? scope;
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(to, target);
  };
  return (
    <a href={hrefOf(to, uiPath, target, consistency)} onClick={follow} style={style}>
      {children}
    </a>
  );
}
