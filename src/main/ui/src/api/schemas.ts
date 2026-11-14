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

// The shapes of the public API's answers that the interface reads. Fields the interface does not read are not
// named, and are dropped when a response is parsed, so a server that adds fields does not break the interface.
// AdminUiDataContractTest checks a real server's answers against these fields.

const index = z.number().int().nonnegative();
const instant = z.string().min(1);

export const RaftRoleSchema = z.enum(['FOLLOWER', 'CANDIDATE', 'LEADER']);

/** `GET /raft/status`: the answering node's view only, not the cluster's. */
export const RaftStatusSchema = z.object({
  nodeId: z.string(),
  serverId: z.string().nullable(),
  state: RaftRoleSchema,
  term: index,
  leaderId: z.string().nullable(),
  commitIndex: index,
  lastApplied: index,
  lastLogIndex: index,
  snapshotLastIndex: index,
  fenced: z.boolean(),
  fenceReason: z.string().nullable(),
  removed: z.boolean(),
  storageBytes: index.optional(),
  // -1 when the node has no snapshot.
  snapshotAgeSeconds: z.number().int().min(-1).optional(),
});

/** The body of every error answer. */
export const ErrorEnvelopeSchema = z.object({
  code: z.string().min(1),
  message: z.string(),
  retryable: z.boolean(),
  requestId: z.string().nullable().optional(),
  conditions: z.array(z.string()).optional(),
});

export const ReadySchema = z.object({ status: z.literal('ready') });

/** A node's status, set by the servers. It is not service health and must not be shown as one. */
export const NodeStatusSchema = z.enum(['registering', 'healthy', 'unreachable']);

/** `GET /v1/catalog/nodes`. A node has no tenant or namespace. */
export const CatalogNodeSchema = z.object({
  name: z.string(),
  id: z.string().nullable(),
  address: z.string(),
  datacenter: z.string().nullable(),
  region: z.string().nullable(),
  metadata: z.record(z.string(), z.string()),
  status: NodeStatusSchema,
  registrationTime: instant.nullable(),
  lastHeartbeat: instant.nullable(),
});

/** `GET /v1/catalog/services`: each service name with its tags, in the request's scope. */
export const ServiceNamesSchema = z.record(z.string(), z.array(z.string()));

/** The five health states (platform design section 7.6). */
export const HealthSchema = z.enum(['UNKNOWN', 'PASSING', 'WARNING', 'CRITICAL', 'MAINTENANCE']);

export const ServiceInstanceSchema = z.object({
  serviceId: z.string(),
  serviceName: z.string(),
  nodeId: z.string().nullable(),
  address: z.string(),
  port: z.number().int(),
  tags: z.array(z.string()),
  metadata: z.record(z.string(), z.string()),
  health: HealthSchema,
  tenantId: z.string(),
  namespace: z.string(),
  datacenter: z.string().nullable(),
  region: z.string().nullable(),
  enabled: z.boolean(),
  createIndex: index,
  modifyIndex: index,
});

/** A check's latest accepted observation, as the servers hold it. */
export const CheckSchema = z.object({
  checkId: z.string(),
  status: HealthSchema,
  required: z.boolean(),
  sequenceNumber: z.number().int(),
  observedAt: instant,
  acceptedAt: instant,
  deadline: instant,
  expired: z.boolean(),
  output: z.string().nullable(),
  deregisterAfterMillis: z.number().int().nonnegative(),
});

/** `GET /v1/health/service/{name}`: each instance with its checks and its node's status, if the node is known. */
export const HealthEntrySchema = z.object({
  service: ServiceInstanceSchema,
  checks: z.array(CheckSchema),
  nodeStatus: NodeStatusSchema.nullable(),
});

const counts = z.number().int().nonnegative();

/** `GET /v1/internal/ui/services`: a service in the read's scope, summarised from the instances the token may read. */
export const ServiceSummarySchema = z.object({
  name: z.string(),
  tenantId: z.string(),
  namespace: z.string(),
  tags: z.array(z.string()),
  instanceCount: counts,
  health: z.object({ unknown: counts, passing: counts, warning: counts, critical: counts, maintenance: counts }),
  datacenters: z.array(z.string()),
  regions: z.array(z.string()),
  modifyIndex: index,
});

/** `GET /v1/health/state/{state}`: an accepted check with its instance, service and node. */
export const CheckEntrySchema = CheckSchema.extend({
  serviceId: z.string(),
  serviceName: z.string(),
  nodeId: z.string().nullable(),
  nodeStatus: NodeStatusSchema.nullable(),
  tenantId: z.string(),
  namespace: z.string(),
});

/** `GET /v1/tenants`, in Consul's field names. */
export const TenantSchema = z.object({ Name: z.string().min(1), Description: z.string() });

/** `GET /v1/namespaces`, of the tenant the header names. */
export const NamespaceSchema = z.object({ Name: z.string().min(1), Tenant: z.string().min(1), Description: z.string() });

/** A server in the membership read; the progress fields are null where the answering server does not lead. */
export const MemberSchema = z.object({
  serverId: z.string(),
  name: z.string(),
  address: z.string(),
  voter: z.boolean(),
  leader: z.boolean(),
  self: z.boolean(),
  matchIndex: index.nullable(),
  nextIndex: index.nullable(),
  lag: index.nullable(),
  lastContactMillis: z.number().int().nonnegative().nullable(),
  reachable: z.boolean().nullable(),
  healthy: z.boolean().nullable(),
});

/** `GET /v1/operator/raft/members`: the leader's view with the quorum, or a follower's labelled partial view. */
export const MembersSchema = z.object({
  view: z.enum(['leader', 'follower']),
  answeredBy: z.string(),
  leaderId: z.string().nullable(),
  term: index,
  commitIndex: index,
  lastLogIndex: index,
  quorum: z
    .object({
      voters: counts,
      quorumSize: counts,
      reachableVoters: counts,
      failureTolerance: counts,
      healthy: z.boolean(),
    })
    .nullable(),
  servers: z.array(MemberSchema),
});

/** `GET /v1/operator/raft/storage`: the answering server's log and snapshot state. */
export const StorageSchema = z.object({
  answeredBy: z.string(),
  storageBytes: index.nullable(),
  // -1 when the server knows its storage and holds no snapshot; null when it knows no storage directory.
  snapshotAgeSeconds: z.number().int().min(-1).nullable(),
  snapshotLastIndex: index,
  snapshotLastTerm: index,
  firstLogIndex: index,
  lastLogIndex: index,
  commitIndex: index,
  lastApplied: index,
  fenced: z.boolean(),
  fenceReason: z.string().nullable(),
  transitionQueueDepth: counts,
  lastSnapshotFailure: z.object({ message: z.string(), at: instant }).nullable(),
});

export type Member = z.infer<typeof MemberSchema>;
export type Members = z.infer<typeof MembersSchema>;
export type Storage = z.infer<typeof StorageSchema>;
export type ServiceSummary = z.infer<typeof ServiceSummarySchema>;
export type CheckEntry = z.infer<typeof CheckEntrySchema>;
export type Tenant = z.infer<typeof TenantSchema>;
export type Namespace = z.infer<typeof NamespaceSchema>;
export type RaftRole = z.infer<typeof RaftRoleSchema>;
export type RaftStatus = z.infer<typeof RaftStatusSchema>;
export type NodeStatus = z.infer<typeof NodeStatusSchema>;
export type CatalogNode = z.infer<typeof CatalogNodeSchema>;
export type ServiceNames = z.infer<typeof ServiceNamesSchema>;
export type Health = z.infer<typeof HealthSchema>;
export type ServiceInstance = z.infer<typeof ServiceInstanceSchema>;
export type Check = z.infer<typeof CheckSchema>;
export type HealthEntry = z.infer<typeof HealthEntrySchema>;
