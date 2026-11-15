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

import { Alert, Button, Card, Input, Space, Typography } from 'antd';
import { createContext, type ReactNode, useContext, useEffect, useRef, useState } from 'react';
import { ApiError, type AuthorizationCheck, isAbort, type QraftApi } from './api/client';
import type { Token } from './api/schemas';
import type { Bootstrap } from './bootstrap';
import type { Session } from './session';
import { formatInstant } from './views/common';

/** What the token in use may do, as the server answered it; navigation and controls follow it (decision 3). */
export interface Permissions {
  operatorRead: boolean;
  operatorWrite: boolean;
  aclRead: boolean;
  aclWrite: boolean;
}

/** Who the interface acts as: no access control, or a token the server knows, with what it may do. */
export interface Access {
  mode: 'none' | 'token';
  /** The token in use, from `GET /v1/acl/token/self`; null without access control. */
  token: Token | null;
  may: Permissions;
  signOut(): void;
}

const EVERYTHING: Permissions = { operatorRead: true, operatorWrite: true, aclRead: true, aclWrite: true };

/** The access of an interface given no session: every view offered, as before sign-in existed. */
export function unrestricted(mode: Access['mode']): Access {
  return { mode, token: null, may: EVERYTHING, signOut: () => undefined };
}

const AccessContext = createContext<Access>(unrestricted('none'));

export const AccessProvider = AccessContext.Provider;

export function useAccess(): Access {
  return useContext(AccessContext);
}

/** The built-in policy whose tokens are management tokens (decision 5). */
const MANAGEMENT_POLICY_ID = '00000000-0000-0000-0000-000000000001';

/** Whether `token` is a management token: one linked to the built-in `global-management` policy. */
export function isManagement(token: Token | null): boolean {
  return token !== null && token.policies.some((policy) => policy.id === MANAGEMENT_POLICY_ID);
}

/** The checks one authorize call makes after sign-in. */
const PERMISSION_CHECKS: AuthorizationCheck[] = [
  { resource: 'operator', access: 'read' },
  { resource: 'operator', access: 'write' },
  { resource: 'acl', access: 'read' },
  { resource: 'acl', access: 'write' },
];

/** What a session ended by the server says at sign-in. */
export const SESSION_ENDED =
  'Your session ended: the server no longer knows the token you signed in with. It may have expired or been revoked. '
  + 'Sign in again.';

const UNKNOWN_SECRET = 'The server does not know this token: it is unknown, has expired, or was revoked.';

type GateState =
  | { status: 'signed-out' }
  | { status: 'checking' }
  | { status: 'failed'; error: unknown }
  | { status: 'ready'; access: Access };

/**
 * Asks for a token secret before any API call when the server has access control on (decision 2), checks it with
 * `self`, asks what it may do, and gives the interface its access. The session ends, and sign-in returns, when the
 * server no longer knows the token. Without access control there is nothing to ask.
 */
export function AccessGate({ bootstrap, api, session, children }: {
  bootstrap: Bootstrap;
  api: QraftApi;
  session: Session;
  children: (access: Access) => ReactNode;
}) {
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<GateState>(() =>
    session.secret() === null ? { status: 'signed-out' } : { status: 'checking' });
  // The token sign-in has just checked, so that it is not read twice.
  const verified = useRef<{ secret: string; token: Token } | null>(null);

  useEffect(() => session.subscribe(() => setVersion((count) => count + 1)), [session]);

  useEffect(() => {
    if (bootstrap.authMode === 'none') return undefined;
    const secret = session.secret();
    if (secret === null) {
      setState({ status: 'signed-out' });
      return undefined;
    }
    const control = new AbortController();
    setState({ status: 'checking' });
    const known = verified.current?.secret === secret ? verified.current.token : null;
    verified.current = null;
    (async () => {
      const token = known ?? (await api.self({ signal: control.signal })).data;
      const answers = (await api.authorize(PERMISSION_CHECKS, { signal: control.signal })).data;
      const allows = (resource: string, access: string) =>
        answers.some((answer) => answer.resource === resource && answer.access === access && answer.allow);
      const may: Permissions = {
        operatorRead: allows('operator', 'read'),
        operatorWrite: allows('operator', 'write'),
        aclRead: allows('acl', 'read'),
        aclWrite: allows('acl', 'write'),
      };
      if (!control.signal.aborted) {
        setState({ status: 'ready', access: { mode: 'token', token, may, signOut: () => session.signOut() } });
      }
    })().catch((error: unknown) => {
      if (isAbort(error) || control.signal.aborted) return;
      if (error instanceof ApiError && error.code === 'token_not_found') {
        if (session.secret() !== null) session.expire(SESSION_ENDED);
        return;
      }
      setState({ status: 'failed', error });
    });
    return () => control.abort();
  }, [bootstrap.authMode, api, session, version]);

  if (bootstrap.authMode === 'none') return <>{children(unrestricted('none'))}</>;
  switch (state.status) {
    case 'ready':
      return <>{children(state.access)}</>;
    case 'checking':
      return <Centered><Typography.Text>Checking your token…</Typography.Text></Centered>;
    case 'failed':
      return (
        <Centered>
          <Alert
            type="error"
            message="The token could not be checked"
            description={state.error instanceof Error ? state.error.message : String(state.error)}
            action={<Button onClick={() => setVersion((count) => count + 1)}>Try again</Button>}
          />
        </Centered>
      );
    case 'signed-out':
      return (
        <SignIn
          api={api}
          ended={session.ended()}
          onVerified={(secret, token) => {
            verified.current = { secret, token };
            session.signIn(secret);
          }}
        />
      );
  }
}

function Centered({ children }: { children: ReactNode }) {
  return <div style={{ maxWidth: 520, margin: '64px auto', padding: '0 16px' }}>{children}</div>;
}

/**
 * Sign-in (UI/UX design section 10.2.2): the secret is typed or pasted, sent as `X-Qraft-Token` to `self`, and kept
 * only once the server knows it. It is never put in an address and never shown again.
 */
function SignIn({ api, ended, onVerified }: {
  api: QraftApi;
  ended: string | null;
  onVerified: (secret: string, token: Token) => void;
}) {
  const [secret, setSecret] = useState('');
  const [checking, setChecking] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  const submit = () => {
    const typed = secret.trim();
    if (typed === '') {
      setRefusal('Enter the secret of your token.');
      return;
    }
    setChecking(true);
    setRefusal(null);
    api.self({ secret: typed }).then(
      (answer) => onVerified(typed, answer.data),
      (error: unknown) => {
        setChecking(false);
        setRefusal(error instanceof ApiError && error.code === 'token_not_found' ? UNKNOWN_SECRET
          : `The token could not be checked: ${error instanceof Error ? error.message : String(error)}`);
      },
    );
  };

  return (
    <Centered>
      <Card>
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Title level={1} style={{ fontSize: 20, margin: 0 }}>Qraft</Typography.Title>
          <Typography.Title level={2} style={{ marginTop: 0 }}>Sign in</Typography.Title>
          {ended !== null && <Alert type="warning" message={ended} />}
          <Typography.Text>
            This server has access control on. Enter the secret of your token. It is sent as X-Qraft-Token, held for
            this tab only, and never shown again; signing out forgets it.
          </Typography.Text>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            <Space direction="vertical" style={{ width: '100%' }}>
              <Input.Password
                aria-label="Token secret"
                autoComplete="off"
                value={secret}
                onChange={(event) => setSecret(event.target.value)}
              />
              <Button type="primary" htmlType="submit" loading={checking}>Sign in</Button>
            </Space>
          </form>
          {refusal !== null && <Alert type="error" message={refusal} />}
        </Space>
      </Card>
    </Centered>
  );
}

/** Minutes left before `expiry`, rounded down; null when there is none. */
function minutesLeft(expiry: string | null): number | null {
  if (expiry === null) return null;
  return Math.floor((Date.parse(expiry) - Date.now()) / 60_000);
}

/**
 * The token menu (UI/UX design section 10.2.2): the token in use, its accessor ID, description, policies,
 * identities and expiry, with a warning when it expires within the hour, and the means to change token or sign out.
 * A plain button: an Ant Design button on every page slowed every table in the tests (UI-4).
 */
export function TokenMenu({ color }: { color: string }) {
  const access = useAccess();
  const [open, setOpen] = useState(false);
  if (access.mode === 'none') {
    return <Typography.Text aria-label="Token in use" style={{ color }}>No access control</Typography.Text>;
  }
  const { token } = access;
  if (token === null) return null;
  const left = minutesLeft(token.expirationTime);
  return (
    <span style={{ position: 'relative' }}>
      <button
        type="button"
        aria-label="Token in use"
        aria-expanded={open}
        onClick={() => setOpen((shown) => !shown)}
        style={{ color, background: 'transparent', border: `1px solid ${color}`, borderRadius: 4, padding: '2px 8px',
          cursor: 'pointer' }}
      >
        {`Token: ${token.description || token.accessorId}`}
      </button>
      {open && (
        <div
          aria-label="Token details"
          style={{ position: 'absolute', right: 0, top: '100%', zIndex: 10, background: '#fff', color: 'rgba(0,0,0,0.88)',
            border: '1px solid #d9d9d9', borderRadius: 6, padding: 12, minWidth: 360, lineHeight: 1.6 }}
        >
          <div>{`Accessor ID ${token.accessorId}`}</div>
          {token.description && <div>{`Description ${token.description}`}</div>}
          <div>{`Policies: ${token.policies.length === 0 ? 'none' : token.policies.map((p) => p.name ?? p.id).join(', ')}`}</div>
          {token.roles.length > 0 && <div>{`Roles: ${token.roles.map((role) => role.name ?? role.id).join(', ')}`}</div>}
          {token.nodeIdentities.map((identity) => <div key={identity.nodeName}>{`Node identity ${identity.nodeName}`}</div>)}
          {token.serviceIdentities.map((identity) => (
            <div key={`${identity.serviceName}/${identity.tenant}/${identity.namespace}`}>
              {`Service identity ${identity.serviceName} in ${identity.tenant}/${identity.namespace}`}
            </div>
          ))}
          <div>{token.expirationTime === null ? 'Does not expire' : `Expires ${formatInstant(token.expirationTime)}`}</div>
          {left !== null && left < 60 && (
            <Alert type="warning" message={`Expires in ${left} minutes: sign in with another token before then.`} />
          )}
          <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
            <button type="button" onClick={access.signOut}>Change token</button>
            <button type="button" onClick={access.signOut}>Sign out</button>
          </div>
        </div>
      )}
    </span>
  );
}

/** The management token's banner, on every page while it is in use, not dismissible (principle 3.7). */
export function ManagementBanner() {
  const { token } = useAccess();
  if (!isManagement(token)) return null;
  return (
    <Alert
      type="warning"
      banner
      aria-label="Management token in use"
      message="Signed in with the management token: unrestricted access. Use a scoped token for routine work."
    />
  );
}
