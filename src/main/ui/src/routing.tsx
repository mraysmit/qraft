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

// Each view has its own address below the interface's path, so reloading, bookmarking, sharing a link and the back
// and forward buttons work (UI/UX design section 4.4). The server answers every such address with index.html.

export type Route =
  | { view: 'services' }
  | { view: 'service'; name: string }
  | { view: 'nodes' }
  | { view: 'unknown' };

/** The view an address below `uiPath` names. */
export function routeOf(pathname: string, uiPath: string): Route {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  if (pathname === root.slice(0, -1)) return { view: 'services' };
  if (!pathname.startsWith(root)) return { view: 'unknown' };
  const rest = pathname.slice(root.length);
  if (rest === '' || rest === 'services') return { view: 'services' };
  if (rest === 'nodes') return { view: 'nodes' };
  if (rest.startsWith('services/') && rest.length > 'services/'.length && !rest.slice('services/'.length).includes('/')) {
    try {
      return { view: 'service', name: decodeURIComponent(rest.slice('services/'.length)) };
    } catch {
      return { view: 'unknown' };
    }
  }
  return { view: 'unknown' };
}

/**
 * The address of a view. A service name is one encoded segment, its dots included: the server takes a last segment
 * with a dot for a file, and a file that is not there is not found.
 */
export function hrefOf(route: Route, uiPath: string): string {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  switch (route.view) {
    case 'services':
    case 'unknown':
      return root;
    case 'nodes':
      return `${root}nodes`;
    case 'service':
      return `${root}services/${encodeURIComponent(route.name).replaceAll('.', '%2E')}`;
  }
}

interface Router {
  route: Route;
  uiPath: string;
  navigate(to: Route): void;
}

const RouterContext = createContext<Router | null>(null);

export function RouterProvider({ uiPath, children }: { uiPath: string; children: ReactNode }) {
  const [pathname, setPathname] = useState(() => window.location.pathname);

  useEffect(() => {
    const follow = () => setPathname(window.location.pathname);
    window.addEventListener('popstate', follow);
    return () => window.removeEventListener('popstate', follow);
  }, []);

  const navigate = useCallback(
    (to: Route) => {
      const href = hrefOf(to, uiPath);
      window.history.pushState(null, '', href);
      setPathname(window.location.pathname);
    },
    [uiPath],
  );

  const router = useMemo(() => ({ route: routeOf(pathname, uiPath), uiPath, navigate }), [pathname, uiPath, navigate]);
  return <RouterContext.Provider value={router}>{children}</RouterContext.Provider>;
}

export function useRouter(): Router {
  const router = useContext(RouterContext);
  if (router === null) throw new Error('useRouter is used outside a RouterProvider');
  return router;
}

/** A link to a view: a real address, so it can be opened in a new tab, and followed in place on a plain click. */
export function RouteLink({ to, children, style }: { to: Route; children: ReactNode; style?: CSSProperties }) {
  const { uiPath, navigate } = useRouter();
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    navigate(to);
  };
  return (
    <a href={hrefOf(to, uiPath)} onClick={follow} style={style}>
      {children}
    </a>
  );
}
