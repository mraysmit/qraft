# Legacy catalog fixtures

These files freeze the catalog command and controller snapshot formats before
composite service-instance identity was introduced.

- Source revision: `3fa41f2aff3d30abe6ff5e76273a758426f876cb`
- Produced: 2026-09-22
- Producers: the production `ProtobufRaftCommandCodec` and
  `QraftStateStore.takeSnapshot`
- Policy: immutable; do not regenerate these files after identity or schema
  changes

| File | Bytes | SHA-256 | Contents |
|---|---:|---|---|
| `register-rich.bin` | 95 | `f4cc4fb7aa5b7e6cc1e3710e0d7dcf8b5e3a47846e24d2d27d9042595545528f` | Register `web-a` on `node-a` with tags, metadata, and `PASSING` health |
| `register-empty.bin` | 65 | `170617b5656cc81f099f7e12cde6c985826c58a8ffc864735e41903f249f52fc` | Register `worker-1` with empty tags and metadata and `FAILING` health |
| `deregister.bin` | 16 | `31dfb4a90cf9eaeddb0be34ef020f7c6abdfae2479cf76f04647c16562eba3fd` | Deregister legacy service ID `legacy-web` |
| `catalog-snapshot.json` | 782 | `26d75e529a639281f78bae6bce7c82120fd22e3ca3480857e926fc533f747423` | Two `web` instances on different nodes, one agent, metadata, and last-applied index 42 |

The task list requested `UNKNOWN` health for the empty registration. The legacy
`ServiceHealth` enum contains only `PASSING`, `WARNING`, and `FAILING`, so the
fixture deliberately captures `FAILING` rather than inventing a value the source
format could not produce. A future server-owned default is a schema decision,
not part of this legacy baseline.
