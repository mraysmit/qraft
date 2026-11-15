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

import { Select } from 'antd';
import type { Consistency } from '../api/client';
import { useRouter } from '../routing';

const CHOICES: { label: string; value: Consistency }[] = [
  { label: 'Default', value: 'default' },
  { label: 'Consistent', value: 'consistent' },
  { label: 'Stale', value: 'stale' },
];

/**
 * The top bar's read-consistency selector (UI/UX design section 4.1; UI-4 task list, decision 4). Choosing one reads the
 * current view again in it, its filters kept; the choice is in the address.
 */
export function ConsistencySelector() {
  const { consistency, selectConsistency } = useRouter();
  return (
    <Select
      aria-label="Consistency"
      virtual={false}
      value={consistency}
      style={{ minWidth: 130 }}
      options={CHOICES}
      onChange={(chosen: Consistency) => selectConsistency(chosen)}
    />
  );
}
