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

const NONCE = Symbol('qraft.runtimeStyleNonce');

type Container = HTMLElement & { [NONCE]?: { nonce: string } };

/**
 * Gives each style element added to `container` without a nonce the request's nonce, so the content security policy
 * allows it.
 *
 * Ant Design passes the nonce to the styles it adds, but rc-util, which it uses, adds one that cannot carry it: every
 * table measures the scrollbar with a style of its own (rc-util `getScrollBarSize`, called from rc-table), and Chrome
 * blocked it on 2026-11-14. Only scripts from the server's own origin run on the page, so a style one of them adds is
 * the interface's own. A style element in markup is not added this way and still needs the nonce.
 */
export function nonceRuntimeStyles(container: HTMLElement, nonce: string): void {
  const target = container as Container;
  const existing = target[NONCE];
  if (existing !== undefined) {
    existing.nonce = nonce;
    return;
  }
  const state = { nonce };
  target[NONCE] = state;
  const append = container.appendChild.bind(container);
  const insert = container.insertBefore.bind(container);
  const withNonce = (node: Node) => {
    if (node instanceof HTMLStyleElement && !node.nonce) node.nonce = state.nonce;
  };
  container.appendChild = <T extends Node>(node: T): T => {
    withNonce(node);
    return append(node);
  };
  container.insertBefore = <T extends Node>(node: T, child: Node | null): T => {
    withNonce(node);
    return insert(node, child);
  };
}
