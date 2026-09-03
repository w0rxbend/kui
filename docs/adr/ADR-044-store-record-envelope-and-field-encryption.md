# ADR-044 — The metadata-store record envelope, secret marking and field-level encryption binding

- Status: Accepted
- Date: 2026-09-03
- Supersedes / amends: extends [ADR-042](ADR-042-kafka-backed-metadata-store.md) §4; does not
  change it.

## Context

ADR-042 decided *where* KUI's own configuration lives — internal compacted Kafka topics
(`__kui_config`, `__kui_files`, later `__kui_audit`) — and stated that secret fields are
envelope-encrypted at rest with a key that is never stored. It did not decide the three things
an implementer immediately needs, and which are impossible to change later without a data
migration:

1. **How a record is framed.** A compacted topic keeps a value forever. Whatever byte shape the
   first release writes is the shape every later release has to read.
2. **How KUI knows which fields are secret.** ADR-042 says "secret fields", but a record is JSON
   written by a section owner, and the store is generic: it holds cluster profiles today, RBAC
   roles in M6, and whatever a later milestone adds.
3. **What a ciphertext is bound to.** AES-GCM authenticates the data it wraps; it does not by
   itself stop a ciphertext being moved from one record to another, or from one field to
   another, by anyone who can write to the topic.

Seven M1 task specs had to assume answers to these. Two of them assumed different ones. That is
the definition of a decision that belongs in an ADR rather than in a task.

## Decision

**1. One versioned envelope, hand-written codecs, golden files.**

Every value in `__kui_config` is a `StoreRecord`:

```
{ "envelopeVersion": 1,
  "key":     "<section>/<id>",
  "version": 7,
  "writtenAt": "2026-09-03T10:11:12Z",
  "writtenBy": "kui-cluster/<instance>",
  "payload": { ... section-owned JSON ... } }
```

A tombstone is a `null` value at the same key, as Kafka compaction requires. An
`envelopeVersion` KUI does not recognise is a **named error** (`KUI-STORE-ENVELOPE`), never a
silent skip: a replica that silently ignores records it cannot read converges on a state no
operator asked for. Codecs are explicit (ADR-007) and pinned by committed golden files, because
these bytes outlive the code that wrote them.

**2. A secret is marked by JSON convention, not by a per-section field list.**

A section owner writes a secret as a one-key object:

```
"password": { "$secret": "hunter2" }
```

and the store rewrites it, before producing, to:

```
"password": { "$enc": { "keyId": "k1", "iv": "<base64>", "ct": "<base64>" } }
```

Decryption is the same walk in reverse. The store never inspects a section's schema.

The rejected alternative was a registry in `libs/config` mapping each section to the paths of
its secret fields. It fails the first time a section gains a field: the owner adds
`saslPassword`, forgets the registry, and the store cheerfully writes a plaintext password to a
topic that is replicated, compacted and backed up. The marker travels with the value, so
forgetting it is a local, visible mistake in the code that has the secret in its hand.

**3. A ciphertext is bound to its record and its field.**

The AES-256-GCM additional authenticated data is `"<key>|<fieldPath>"` — for example
`clusters/prod-eu|security.password`. A 96-bit IV is generated fresh for every encryption and is
never reused. Moving an `$enc` node to another record, or to another field of the same record,
therefore fails authentication and produces `KUI-STORE-CRYPTO` rather than a value that decrypts
into the wrong place.

**4. `keyId` in the envelope; rotation is read-many, write-one.**

Each `$enc` node names the key that produced it. A configured keyring maps `keyId` to key
material, so records written under `k1` stay readable after `k2` becomes the write key.
Re-encryption is a rewrite of the record at a new `version`, through the ordinary write path.

## Consequences

- **Renaming a secret field is a migration**, not a rename: the AAD contains the field path, so
  the old ciphertext will not authenticate under the new path. This is the price of binding, it
  is worth paying, and it must be written in `docs/operations/metadata-store.md` next to the
  field tables so nobody discovers it in production.
- **The file adapter does not decrypt and needs no key.** ADR-042 §7's store-less mode exists so
  that KUI can run with no such risk; requiring the key there would require the thing the mode
  exists to avoid. A `$enc` node found by the file adapter is a configuration error.
- **A wrong key fails loudly.** GCM authentication failure is `KUI-STORE-CRYPTO` with the
  `keyId` it tried; there is no partial plaintext and no fallback to the raw bytes.
- **The leak test is cheap and mandatory.** Because the marker is structural, a test can write a
  profile whose every secret is a distinctive canary token and assert that a raw consumer dump
  of `__kui_config` contains none of them. That is M1's exit criterion, and it stays valid for
  every future section for free.
- **`envelopeVersion` is the migration lever we will need.** Version 2 will be readable and
  writable side by side because version 1 records name themselves.

## Evidence

`docs/adr/ADR-042` §4 and §7; `research/scala/security-research.md` §5 (AES-GCM, IV reuse, key
handling); `docs/adr/ADR-007` (explicit Circe codecs, no automatic derivation on the wire);
`docs/plans/M1/tasks/STORE-001.md`, `STORE-002.md`, `STORE-003.md`, `STORE-009.md`.
