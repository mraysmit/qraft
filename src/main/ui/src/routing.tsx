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
import { AUDIT_FILTERS, type AuditQuery, type Consistency, DEFAULT_SCOPE, type Scope } from './api/client';
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
  /** The Sessions page, and a session by its ID (UI-6 Sessions, Locks and Watches task list, decision 12). */
  | { view: 'sessions' }
  | { view: 'sessionInfo'; id: string }
  | { view: 'locks' }
  /** The Watches page, and what it watches; the name is in the query, as a key may hold a `/` or a dot. */
  | { view: 'watches'; target?: WatchTarget; name?: string }
  /** The Govern pages (UI-6 Tenants, Namespaces and Audit task list, decision 9). */
  | { view: 'tenants' }
  | { view: 'tenant'; name: string }
  | { view: 'namespaces' }
  | { view: 'namespace'; tenant: string; name: string }
  /** The Audit page, its filters in the query, the scope's as `inTenant` and `inNamespace`. */
  | { view: 'audit'; filter: AuditQuery }
  | { view: 'unknown' };

/** What a watch follows (UI-6 Sessions, Locks and Watches task list, decision 10). */
export type WatchTarget = 'key' | 'prefix' | 'service' | 'sessions' | 'events';

/**
 * The view an address below `uiPath` names. The Events page also takes its category and correlation ID from the
 * query, so that a mutation's outcome can link its audit events (UI-5 task list, decision 6).
 */
export function routeOf(pathname: string, uiPath: string, search = ''): Route {
  const root = uiPath.endsWith('/') ? uiPath : `${uiPath}/`;
  if (pathname === root.slice(0, -1)) return { view: 'services' };
  if (!pathname.startsWith(root)) return { view: 'unknown' };
  const rest = pathname.slice(root.length);
  const plain = PLAIN_VIEWS.get(rest);
  if (plain !== undefined) return plain;
  if (rest === 'events') return eventsOf(search);
  // The folder or key is in the query: the server takes an address with an empty segment for none, and one whose
  // last segment has a dot for a file, and a key may have either.
  if (rest === 'kv') return { view: 'kv', path: new URLSearchParams(search).get('path') ?? '' };
  if (rest === 'watches') return watchesOf(search);
  if (rest === 'audit') return { view: 'audit', filter: auditFilterOf(search) };
  return namespaceOf(rest) ?? oneNamedOf(rest) ?? { view: 'unknown' };
}

/** The pages of one thing named by the one segment after their prefix, its dots included. */
const ONE_NAMED: readonly [string, (name: string) => Route][] = [
  ['sessions/', (id) => ({ view: 'sessionInfo', id })],
  ['tenants/', (name) => ({ view: 'tenant', name })],
  ['access/tokens/', (accessorId) => ({ view: 'token', accessorId })],
  ['access/policies/', (id) => ({ view: 'policy', id })],
  ['services/', (name) => ({ view: 'service', name })],
];

function oneNamedOf(rest: string): Route | null {
  for (const [prefix, route] of ONE_NAMED) {
    const name = single(rest, prefix);
    if (name !== null) return route(name);
  }
  return null;
}

/** The views an address names by its path alone, with nothing read from the path or the query. */
// A Map, not an object: an object would answer `constructor` or `__proto__` with Object's own members.
const PLAIN_VIEWS: ReadonlyMap<string, Route> = new Map<string, Route>([
  ['', { view: 'services' }],
  ['services', { view: 'services' }],
  ['nodes', { view: 'nodes' }],
  ['checks', { view: 'checks' }],
  ['cluster', { view: 'cluster' }],
  ['members', { view: 'members' }],
  ['storage', { view: 'storage' }],
  ['configuration', { view: 'configuration' }],
  ['sessions', { view: 'sessions' }],
  ['locks', { view: 'locks' }],
  ['tenants', { view: 'tenants' }],
  ['namespaces', { view: 'namespaces' }],
  ['access', { view: 'access' }],
  ['access/tokens', { view: 'tokens' }],
  ['access/policies', { view: 'policies' }],
  ['access/explain', { view: 'explain' }],
]);

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

/** A namespace's page, `namespaces/<tenant>/<namespace>`: exactly two segments, neither empty. */
function namespaceOf(rest: string): Route | null {
  if (!rest.startsWith('namespaces/')) return null;
  const parts = rest.slice('namespaces/'.length).split('/');
  if (parts.length !== 2 || parts.includes('')) return null;
  try {
    return { view: 'namespace', tenant: decodeURIComponent(parts[0]!), name: decodeURIComponent(parts[1]!) };
  } catch {
    return null;
  }
}

const WATCH_TARGETS: readonly WatchTarget[] = ['key', 'prefix', 'service', 'sessions', 'events'];

/** The Watches page's target and name from its query; an unknown target is none. */
function watchesOf(search: string): Route {
  const query = new URLSearchParams(search);
  const target = WATCH_TARGETS.find((known) => known === query.get('target'));
  if (target === undefined) return { view: 'watches' };
  const name = query.get('name');
  return name === null ? { view: 'watches', target } : { view: 'watches', target, name };
}

/**
 * Each audit filter's name in an address: the scope's are `inTenant` and `inNamespace`, as `tenant` and `namespace` are
 * the scope selector's (UI-6 Tenants, Namespaces and Audit task list, decision 9).
 */
const AUDIT_ADDRESS: Readonly<Record<(typeof AUDIT_FILTERS)[number], string>> = { accessor: 'accessor',
  requestId: 'requestId', method: 'method', route: 'route', tenant: 'inTenant', namespace: 'inNamespace',
  result: 'result', before: 'before', from: 'from' };

const AUDIT_RESULTS: readonly NonNullable<AuditQuery['result']>[] = ['allowed', 'denied', 'failed'];

/**
 * The Audit page's filters from its query; a blank one, an unknown result, an offset that is not a count of bytes and an
 * unknown parameter are none.
 */
function auditFilterOf(search: string): AuditQuery {
  const query = new URLSearchParams(search);
  const filter: AuditQuery = {};
  for (const name of AUDIT_FILTERS) {
    const value = query.get(AUDIT_ADDRESS[name])?.trim();
    if (value === undefined || value === '') continue;
    if (name === 'result') {
      const result = AUDIT_RESULTS.find((known) => known === value);
      if (result !== undefined) filter.result = result;
    } else if (name !== 'from' || /^\d+$/.test(value)) {
      filter[name] = value;
    }
  }
  return filter;
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

/** What a view keeps in its address's query, after the scope and consistency. */
function viewQuery(route: Route, parameters: URLSearchParams): void {
  if (route.view === 'events') {
    if (route.category !== undefined) parameters.set('category', route.category);
    if (route.correlation !== undefined) parameters.set('correlation', route.correlation);
  } else if (route.view === 'kv' && route.path !== '') {
    parameters.set('path', route.path);
  } else if (route.view === 'watches' && route.target !== undefined) {
    parameters.set('target', route.target);
    if (route.name !== undefined) parameters.set('name', route.name);
  } else if (route.view === 'audit') {
    auditQuery(route.filter, parameters);
  }
}

/** The Audit page's filters in its address, in the order the read sends them. */
function auditQuery(filter: AuditQuery, parameters: URLSearchParams): void {
  for (const name of AUDIT_FILTERS) {
    const value = filter[name];
    if (value !== undefined && value !== '') parameters.set(AUDIT_ADDRESS[name], value);
  }
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
  viewQuery(route, parameters);
  const text = parameters.toString();
  const query = text === '' ? '' : `?${text}`;
  switch (route.view) {
    case 'services':
    case 'unknown':
      return root + query;
    case 'tenants':
    case 'namespaces':
    case 'audit':
      return `${root}${route.view}${query}`;
    case 'tenant':
      return `${root}tenants/${encodeURIComponent(route.name)}${query}`;
    case 'namespace':
      return `${root}namespaces/${encodeURIComponent(route.tenant)}/${encodeURIComponent(route.name)}${query}`;
    case 'locks':
      return `${root}locks${query}`;
    case 'watches':
      return `${root}watches${query}`;
    case 'sessions':
      return `${root}sessions${query}`;
    case 'sessionInfo':
      return `${root}sessions/${encodeURIComponent(route.id)}${query}`;
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
