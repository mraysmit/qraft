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
import { DEFAULT_SCOPE, type Scope } from './api/client';

// Each view has its own address below the interface's path, so reloading, bookmarking, sharing a link and the back
// and forward buttons work (UI/UX design section 4.4). The server answers every such address with index.html. The
// selected scope is the address's query, kept across the interface's links (UI-2 task list, decision 5).

export type Route =
  | { view: 'services' }
  | { view: 'service'; name: string }
  | { view: 'nodes' }
  | { view: 'checks' }
  | { view: 'cluster' }
  | { view: 'members' }
  | { view: 'storage' }
  | { view: 'unknown' };

/** The view an address below `uiPath` names. */
export function routeOf(pathname: string, uiPath: string): Route {
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
  if (rest.startsWith('services/') && rest.length > 'services/'.length && !rest.slice('services/'.length).includes('/')) {
    try {
      return { view: 'service', name: decodeURIComponent(rest.slice('services/'.length)) };
    } catch {
      return { view: 'unknown' };
    }
  }
  return { view: 'unknown' };
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

/**
 * The address of a view in a scope. A service name is one encoded segment, its dots included: the server takes a last
 * segment with a dot for a file, and a file that is not there is not found. The default scope is left out of the query.
 */
export function hrefOf(route: Route, uiPath: string, scope: Scope = DEFAULT_SCOPE): string {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  const isDefault = scope.tenant === DEFAULT_SCOPE.tenant && scope.namespace === DEFAULT_SCOPE.namespace;
  const query = isDefault ? '' : `?${new URLSearchParams({ tenant: scope.tenant, namespace: scope.namespace })}`;
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
      return `${root}${route.view}${query}`;
    case 'service':
      return `${root}services/${encodeURIComponent(route.name).replaceAll('.', '%2E')}${query}`;
  }
}

interface Router {
  route: Route;
  scope: Scope;
  uiPath: string;
  /** Opens a view in the current scope. */
  navigate(to: Route): void;
  /** Reads the current view in another scope. */
  selectScope(scope: Scope): void;
}

const RouterContext = createContext<Router | null>(null);

export function RouterProvider({ uiPath, children }: { uiPath: string; children: ReactNode }) {
  const [location, setLocation] = useState(() => ({ pathname: window.location.pathname, search: window.location.search }));

  useEffect(() => {
    const follow = () => setLocation({ pathname: window.location.pathname, search: window.location.search });
    window.addEventListener('popstate', follow);
    return () => window.removeEventListener('popstate', follow);
  }, []);

  const route = useMemo(() => routeOf(location.pathname, uiPath), [location.pathname, uiPath]);
  const scope = useMemo(() => scopeOf(location.search), [location.search]);

  const go = useCallback(
    (to: Route, inScope: Scope) => {
      window.history.pushState(null, '', hrefOf(to, uiPath, inScope));
      setLocation({ pathname: window.location.pathname, search: window.location.search });
    },
    [uiPath],
  );

  const router = useMemo(
    () => ({
      route,
      scope,
      uiPath,
      navigate: (to: Route) => go(to, scope),
      selectScope: (next: Scope) => go(route, next),
    }),
    [route, scope, uiPath, go],
  );
  return <RouterContext.Provider value={router}>{children}</RouterContext.Provider>;
}

export function useRouter(): Router {
  const router = useContext(RouterContext);
  if (router === null) throw new Error('useRouter is used outside a RouterProvider');
  return router;
}

/** A link to a view in the current scope: a real address, so it can be opened in a new tab, and followed in place. */
export function RouteLink({ to, children, style }: { to: Route; children: ReactNode; style?: CSSProperties }) {
  const { uiPath, scope, navigate } = useRouter();
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(to);
  };
  return (
    <a href={hrefOf(to, uiPath, scope)} onClick={follow} style={style}>
      {children}
    </a>
  );
}
