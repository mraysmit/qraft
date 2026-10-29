# Node fixtures from before the node ID

These files freeze a node registration, and a snapshot holding nodes, a service
and its health check, as the code wrote them before a node had an ID (client
list Phase 2, decision 8). They prove that entries and snapshots written
without a node ID still load, with the node's ID absent.

- Source revision: `4c31c22d071b0ca945df62ced92e202c4e78438d`
- Produced: 2026-10-29
- Producers: the production `ProtobufRaftCommandCodec` and
  `QraftStateStore.takeSnapshot`
- Written by: `NodeIdentityFixtureWriterFixture`, run once and then deleted,
  from the values that `NodeIdentityFixtureTest` holds
- Policy: immutable; do not regenerate these files after the node model or the
  schema changes

| File | Bytes | SHA-256 | Contents |
|---|---:|---|---|
| `node-register-before-node-id.bin` | 133 | `a648698ccce36ca35624764b3fc1153bbb98f0aa3d44d4ca6a6879bcfd523030` | Register `node-web` with an address, a datacenter, a region and metadata holding a registration identifier, a version and a rack, at 2026-10-29T08:00:00Z |
| `node-snapshot-before-node-id.json` | 1363 | `4372e806054859ec2a7ec2df24156818c73f9946c21f8e9e091488426c7bd4f1` | `node-web` registered and heartbeating (sequence 1), `node-idle` registered only, the service `web-1` on `node-web` in tenant `platform` and namespace `payments` with its declared check `http` observed passing; last-applied index 5 |
