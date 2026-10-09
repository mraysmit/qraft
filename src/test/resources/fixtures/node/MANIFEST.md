# Legacy node fixtures

These files freeze the node commands, and a snapshot holding nodes, as the code
wrote them before the node model was reduced.

- Source revision: `5a00975bdc9fc0cb186a82e0b8c52a1a282e7965`
- Produced: 2026-10-09
- Producers: the production `ProtobufRaftCommandCodec` and
  `QraftStateStore.takeSnapshot`. The two job-system files were built from the
  generated protocol classes, because the codec reads those statuses and never
  writes them.
- Written by: `LegacyNodeFixtureWriterFixture`, run once and then deleted, from
  the values that `LegacyNodeFixtureTest` holds
- Policy: immutable; do not regenerate these files after the node model or the
  schema changes

| File | Bytes | SHA-256 | Contents |
|---|---:|---|---|
| `node-register-rich.bin` | 372 | `09c7072673ccde332f74977a2e21b0a2062fdb877aeccb6e55197c80486fc870` | Register `node-rich` with every field set: capabilities, system and network details, metadata, status, and both times |
| `node-register-minimal.bin` | 50 | `f2608b63decac97d09e8b976505fb51f0d8431b79bd5214323fdb731b9b794dd` | Register `node-minimal` with an identifier, a status, and a registration time only |
| `node-deregister.bin` | 22 | `f9d94e6d1be9fb243583bfbfe6e063e877a55c84e4379f65fb7e9005af91baa3` | Deregister `node-rich` |
| `node-update-status.bin` | 26 | `cbc9bf6135d6d6688b36ccc065d38b2cbf5de3662242b3cdef61895935322cdf` | Change `node-rich` from `HEALTHY` to `DEGRADED` |
| `node-update-capabilities.bin` | 251 | `8fc258865b87a326b2b63cf108a4b86a59f52266382c50c7bc17c0abeb49cb01` | Replace the capabilities of `node-rich`, with every capability field set |
| `node-heartbeat.bin` | 33 | `7da00f0eed4149123bb4c65461c42b56ac1de5cd3e46401ebf8dbce4f7188d65` | Heartbeat of `node-rich` with status `HEALTHY`, sequence 7, and registration identifier `reg-1` |
| `node-heartbeat-plain.bin` | 25 | `499cf4a1ed012fb0d8fa5df2473fb8b627e218217dca35e221a171bceb2843e5` | Heartbeat of `node-minimal` with no status, no sequence, and no registration identifier |
| `node-expire.bin` | 31 | `a42b133cd8578ab193c7020101530e5f78ea0c25056cf79bd5fc648d8ea0c088` | Reap `node-rich`, expecting its last contact |
| `node-update-status-job-a.bin` | 25 | `d86c2f311647a4ef20adbacb4cebd7c8038c6c2e4242ccbc5e8eebc0a0313f1e` | Status update of `node-job` holding the job system's `ACTIVE` (3) and `IDLE` (4) |
| `node-update-status-job-b.bin` | 25 | `032b600465b939a500987b98af126f43089301e8b84a2b7135cdee70892b9437` | Status update of `node-job` holding the job system's `OVERLOADED` (6) and `DRAINING` (8) |
| `node-snapshot.json` | 1504 | `182245ec349f387d9284bddffdd0bde7924995871f63b72e307f70dbc1a411f0` | `node-rich` after a registration and a heartbeat, and `node-minimal` after a registration; heartbeat sequence 7; last-applied index 9 |

The snapshot uses the key names of its day, `clients` and `clientId`. The key
names from before 2026-10-09 are in the catalog fixtures' snapshot, whose node
has no capabilities.

In a snapshot, only the job system's `active` status has a fixture, the catalog
one. The code of this revision cannot write the other three into a snapshot,
and none was made up.
