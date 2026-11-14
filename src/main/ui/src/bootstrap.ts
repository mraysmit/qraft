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

import { z } from 'zod';

/**
 * The public settings the server renders into index.html (UiBootstrap and AdminUiHandler on the server; design
 * section 12.4.1). They hold no secret.
 */
export const BootstrapSchema = z.object({
  apiBasePath: z.string(),
  uiPath: z.string(),
  features: z.record(z.string(), z.boolean()),
  authMode: z.enum(['none', 'token']),
  securityProfile: z.enum(['production', 'development']),
  cspNonce: z.string().min(1),
});

export type Bootstrap = z.infer<typeof BootstrapSchema>;

export const BOOTSTRAP_ELEMENT_ID = 'qraft-bootstrap';

/** Reads and checks the settings; a page the server did not render, or settings it would not send, are refused. */
export function readBootstrap(document: Document): Bootstrap {
  const element = document.getElementById(BOOTSTRAP_ELEMENT_ID);
  if (element === null || !element.textContent) {
    throw new Error('The page carries no bootstrap settings: it must be served by a Qraft server');
  }
  return BootstrapSchema.parse(JSON.parse(element.textContent));
}
