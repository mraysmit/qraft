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

import { Table, Tag } from 'antd';
import type { Member, Members } from '../api/schemas';
import { formatSince } from './common';

/** What a follower says of a progress field only the leader knows (UI-3 task list, decision 5). */
export const KNOWN_TO_THE_LEADER = 'Known to the leader';

function known<T>(value: T | null, shown: (value: T) => string): string {
  return value === null ? KNOWN_TO_THE_LEADER : shown(value);
}

/**
 * The servers of a membership read, in words, with colour never the only signal (UI/UX design section 3.6). The
 * detailed table adds the next index and health, for Raft Members.
 */
export function MembersTable({ members, detailed }: { members: Members; detailed: boolean }) {
  const columns = [
    { title: 'Server', render: (_: unknown, member: Member) => member.name },
    { title: 'Address', render: (_: unknown, member: Member) => member.address },
    { title: 'Role', render: (_: unknown, member: Member) => <Tag>{member.leader ? 'Leader' : 'Follower'}</Tag> },
    { title: 'Vote', render: (_: unknown, member: Member) => (member.voter ? 'Voter' : 'Non-voter') },
    {
      title: 'Reachability',
      render: (_: unknown, member: Member) =>
        member.reachable === null ? (
          KNOWN_TO_THE_LEADER
        ) : (
          <Tag color={member.reachable ? 'green' : 'red'}>{member.reachable ? 'Reachable' : 'Unreachable'}</Tag>
        ),
    },
    { title: 'Match index', render: (_: unknown, member: Member) => known(member.matchIndex, String) },
    ...(detailed ? [{ title: 'Next index', render: (_: unknown, member: Member) => known(member.nextIndex, String) }] : []),
    { title: 'Lag', render: (_: unknown, member: Member) => known(member.lag, String) },
    {
      title: 'Last contact',
      render: (_: unknown, member: Member) =>
        member.self && members.view === 'leader'
          ? '—'
          : members.view === 'follower'
            ? KNOWN_TO_THE_LEADER
            : member.lastContactMillis === null
              ? 'Never answered'
              : formatSince(member.lastContactMillis),
    },
    ...(detailed
      ? [
          {
            title: 'Health',
            render: (_: unknown, member: Member) =>
              member.healthy === null ? (
                KNOWN_TO_THE_LEADER
              ) : (
                <Tag color={member.healthy ? 'green' : 'red'}>{member.healthy ? 'Healthy' : 'Unhealthy'}</Tag>
              ),
          },
        ]
      : []),
  ];
  return <Table<Member> rowKey={(member) => member.name} dataSource={members.servers} pagination={false} columns={columns} />;
}
