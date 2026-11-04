# State fixture from before ACLs

This file freezes a snapshot as the code wrote it before the replicated ACL
state existed (ACL list, Phase 1). It proves that a snapshot written without an
`acl` section still restores, with the initial ACL state: the built-in policies,
the anonymous token, and no bootstrap.

The log entries written before ACLs are the entry fixtures of
`fixtures/node/`, `fixtures/catalog/` and `fixtures/node-identity/`, which their
own manifests describe. `AclFixtureTest` decodes them again.

- Source revision: `1eb1955792845e29eba83fc6b4b64b8c3b5f74db`
- Produced: 2026-11-04
- Producers: the production `ProtobufRaftCommandCodec` and
  `QraftStateStore.takeSnapshot`
- Written by: `AclFixtureWriterFixture`, run once and then deleted, from the
  values that `AclFixtureTest` holds
- Policy: immutable; do not regenerate this file after the state or the schema
  changes

| File | Bytes | SHA-256 | Contents |
|---|---:|---|---|
| `snapshot-before-acl.json` | 1195 | `65584efbc8ab8444ad34cb0fdf6975b411dbf8fb314f38ed6017198c824dca55` | `node-web` with node ID `6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b`, registered at 2026-11-04T08:00:00Z and heartbeating (sequence 1); the service `api-1` on `node-web` in tenant `platform` and namespace `payments` with its declared check `http` observed passing; the metadata key `feature`; last-applied index 5 |
