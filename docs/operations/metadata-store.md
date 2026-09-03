# Operating the KUI metadata store

> **None of the `kui.store.*` settings on this page exist yet. Do not copy them into a
> configuration file.**
>
> The metadata store is Milestone 1 work and the configuration loader has no `kui.store` section
> today. Unknown keys are refused rather than ignored, so pasting the YAML block in §1 into a real
> file stops KUI from starting, with eleven errors of the form:
>
> ```
> kui.store.topicPrefix: is not a KUI configuration key
> ```
>
> This page describes the shape the store *will* be configured in, so that the operational
> reasoning — replication factors, in-sync replicas, message size limits — is written down while it
> is being designed. It is not a guide you can follow against the current build. For what does load
> today, see [`configuration.md`](configuration.md) and the examples in `deployment/examples/`.

KUI keeps its own metadata — registered clusters and their connection settings, application
settings, UI-managed roles and user groups, masking policies, uploaded files such as keystores
and protobuf descriptors, and audit events — **in Kafka**, in a handful of internal topics whose
names start with `__kui_`. There is no database to install, back up or migrate. The design and
the reasoning are in [ADR-042](../adr/ADR-042-kafka-backed-metadata-store.md); this page is the
operator's view of it.

If you run KUI purely from static configuration files and never edit anything from the UI, you
do not need the store at all: leave `kui.store.kafka.*` unset and KUI uses the file adapter. See
"Running without the store" below.

## 1. The store cluster

The `__kui_*` topics live on one Kafka cluster, called the **store cluster**. It is configured
**statically** — in the configuration file or through environment variables — and never from the
store itself. That is not an oversight: the connection details of every managed cluster are kept
*in* the store, so the store's own connection has to come from somewhere that does not depend on
it.

The store cluster may be one of the clusters KUI manages, or a separate one. Using a separate
cluster keeps KUI's metadata alive when the cluster people are actually looking at goes down;
using a managed one is simpler and is what the development Compose file does.

```yaml
kui:
  store:
    topicPrefix: "__kui_"          # every topic name below is built from this
    replicationFactor: 3           # 1 for single-broker development
    minInSyncReplicas: 2           # must be < replicationFactor to survive one broker loss
    maxFileBytes: 4194304          # 4 MiB cap on one uploaded file
    encryptionKey: "env:KUI_STORE_ENCRYPTION_KEY"
    kafka:
      bootstrapServers: ["kafka-1:9092", "kafka-2:9092"]
      security:                    # the same typed security model as any managed cluster (ADR-022)
        protocol: SASL_SSL
        mechanism: SCRAM-SHA-512
        username: "kui"
        password: "env:KUI_STORE_PASSWORD"
```

The environment name of a key is its dotted path uppercased with every `.` turned into `_`, so
`kui.store.topicPrefix` is `KUI_STORE_TOPICPREFIX` and `kui.store.kafka.bootstrapServers` is
`KUI_STORE_KAFKA_BOOTSTRAPSERVERS`. The camel-case segment is *not* split — the mapping is
mechanical, so that a new key never needs a naming decision.

The full key list, with defaults and rules, is in
[the configuration page](configuration.md#kuistore--kuis-own-metadata-store). The three keys that
page carries and the block above does not are worth knowing here:

| Key | Default | What it is for |
| --- | --- | --- |
| `kui.store.replayTimeout` | `30s` | How long startup waits for the log to be replayed to its end before failing with `KUI-STORE-REPLAY-TIMEOUT`. This bound is what makes a store problem a startup error rather than a hang. |
| `kui.store.writeTimeout` | `10s` | How long a write waits to read its own record back from the log. |
| `kui.store.dir` | *(unset)* | The read-only file adapter's root; see §7. Ignored when `kui.store.kafka.*` is set. |

For a key rotation, replace `encryptionKey` with the list form and name the active key:

```yaml
kui:
  store:
    encryptionKeys: "k1:env:KUI_STORE_KEY_K1,k2:env:KUI_STORE_KEY_K2"
    encryptionKeyId: "k2"
```

Every listed key can still *decrypt*, and only `encryptionKeyId` is used to *encrypt*, which is
what makes a rotation a rolling change instead of a flag day. Setting `encryptionKey` and
`encryptionKeys` together fails the load: the single-key form is a shorthand for the common case,
not an alias to merge.

**Startup order.** KUI loads static configuration, opens the store client, replays the whole
`__kui_config` topic into memory, and only then knows which clusters exist and reports itself
Ready. A service that cannot reach the store at startup does not start with an empty registry;
it fails with a clear error naming the store's bootstrap servers.

## 2. The topics

| Topic | Purpose | Partitions | Compaction |
| --- | --- | --- | --- |
| `__kui_config` | clusters, settings, roles, masking policies | **1** | compacted |
| `__kui_files` | uploaded keystores, truststores, protobuf descriptors | **1** | compacted |
| `__kui_audit` | audit events (ADR-023) | 3 by default, keyed by cluster id | **not** compacted |

One partition is deliberate for the two compacted topics: it gives a single total order, which
is what makes concurrent edits from several KUI replicas safe without any lock. Metadata writes
are rare, so a single partition is nowhere near a throughput concern. **Do not add partitions to
`__kui_config` or `__kui_files`.** KUI refuses to start against a multi-partition config topic.

Topic configuration KUI creates, and which of those settings it refuses to differ on:

| Topic | Setting | Class | Value KUI sets | Why it is in that class |
| --- | --- | --- | --- | --- |
| both | partition count | **required** | `1` | more than one destroys the total order every concurrent edit depends on |
| both | `cleanup.policy` | **required** | `compact` | `delete` silently loses records from a topic whose whole design assumes compaction |
| both | `min.insync.replicas` | **required** | `kui.store.minInSyncReplicas` | a lower value means `acks=all` does not mean what you think it means |
| `__kui_files` | `max.message.bytes` | **required**, as a minimum | `kui.store.maxFileBytes` + 1 MiB | a file that cannot be produced fails at runtime with a broker-side message nobody can read. A *larger* limit than KUI needs passes |
| both | `retention.ms` | advisory | `-1` | compaction is what keeps the data, so another value is harmless |
| both | `delete.retention.ms` | advisory | `86400000` | affects only how long a tombstone stays visible to a consumer catching up |
| both | `min.compaction.lag.ms` | advisory | `0` | |
| both | `segment.ms` | advisory | `604800000` | compaction only runs on closed segments |
| both | `min.cleanable.dirty.ratio` | advisory | `0.1` | |
| both | replication factor | **not validated** | `kui.store.replicationFactor` on create | see below |
| `__kui_audit` | everything | not created yet | `cleanup.policy=delete`, `retention.ms=7776000000` | created by the release that first writes an audit record |

A **required** setting that differs stops start-up. An **advisory** setting that differs is logged
once, with both values, and KUI carries on: it is your cluster and your retention policy.

**Replication factor is not validated on an existing topic**, only reported as a warning. If you
ran KUI against a single broker and later grew the cluster, you have a perfectly good RF-1 topic
and an RF-3 setting, and refusing to start would punish you for the upgrade.

**KUI creates `__kui_config` and `__kui_files` only.** `__kui_audit` is described here because it
is part of the design, but nothing writes an audit record yet, and creating a retention-based
topic that nothing produces to would only leave you wondering why it is empty. It is created by
the release that first needs it.

`max.message.bytes` on `__kui_files` is the one that bites: a record has to hold the whole
file plus its JSON envelope plus the encryption overhead, so the broker's topic-level
`max.message.bytes` must exceed `kui.store.maxFileBytes` with room to spare, and the broker's
`replica.fetch.max.bytes` must be at least as large or replication stalls. If your organisation
caps message size centrally, lower `kui.store.maxFileBytes` instead of raising the broker limit.

**Creation and validation.** On startup KUI creates any missing `__kui_*` topic with the settings
above. If a topic already exists, KUI validates it and **never rewrites your settings**. An
incompatible topic is a startup failure, not a warning, and the message names the topic, the
setting, the expected value and the value found:

```
KUI-STORE-TOPIC-INCOMPATIBLE: topic __kui_config has cleanup.policy=delete,
expected compact. KUI will not change an existing topic's configuration.
Fix the topic or point kui.store.topicPrefix at a different prefix.
```

### 2.1 The record format

Every value in `__kui_config` is one line of JSON in a versioned envelope. The Kafka record key is
the same text as the envelope's `key` field, so a dump that loses the key column is still
self-describing:

```json
{"envelopeVersion":1,"key":"cluster/prod-eu","version":3,"updatedAt":"2026-09-03T10:15:30Z","updatedBy":"kui-cluster/7f3a","deleted":false,"payload":{"displayName":"Production EU","bootstrapServers":["kafka-1:9092"],"security":{"protocol":"SASL_SSL","mechanism":"SCRAM-SHA-512","username":"kui","password":{"$enc":{"keyId":"k1","iv":"...","ct":"..."}}}}}
```

`version` starts at 1 and goes up by one per accepted write; `deleted` marks a logical tombstone,
which KUI writes in preference to a `null` value because a `null` carries no timestamp and no
author and so cannot answer "who removed this cluster". A `password` field is never plaintext on
the topic: `{"$enc":{...}}` is an encrypted field, and the key that opens it lives in
`kui.store.encryptionKey`, outside the topic (see §4.2).

Two compatibility rules, which differ on purpose:

- **An unknown envelope field is ignored.** Adding a field is compatible by construction — a
  reader that does not know about it behaves exactly as it did before — so an older KUI keeps
  reading records a newer one writes.
- **An unknown `envelopeVersion` is refused**, with `KUI-STORE-ENVELOPE`. A bumped version number
  is the writer saying "you cannot understand this". Skipping such a record silently would leave
  the older KUI serving a stale view of the world while reporting itself healthy, which is a worse
  failure than refusing to start.

## 3. Sizing

Small. A registered cluster is a few kilobytes of JSON; a hundred clusters, a hundred roles and
a set of masking policies together stay well under a megabyte after compaction. Uploaded files
dominate: budget `number of keystores × kui.store.maxFileBytes` for `__kui_files` before
compaction catches up, and roughly double that for the uncompacted head of the log.

`__kui_audit` is the one that grows. At the default `alterOnly` level it records only mutating
operations, which for most installations is a few thousand records a day. At `all` it records
every read as well; size it against your busiest cluster's page-view rate and shorten
`retention.ms` if 90 days of that is more than you want to keep.

## 4. Securing the topics

**Two things protect this data, and you need both.**

### 4.1 ACLs

Everything in `__kui_config` and `__kui_files` is metadata about how to reach your clusters, and
`__kui_audit` is a record of who did what. Restrict all three to KUI's own principal. Read
access to `__kui_audit` by anyone else lets them read your audit trail; write access lets them
forge it.

```sh
# KUI's principal: full access to its own topics
kafka-acls --bootstrap-server "$STORE" \
  --add --allow-principal User:kui \
  --operation Read --operation Write --operation Describe --operation Create \
  --topic __kui_ --resource-pattern-type prefixed

# KUI needs no consumer group for the config and files topics (it assigns partitions
# directly), but the audit producer and any future consumer group do:
kafka-acls --bootstrap-server "$STORE" \
  --add --allow-principal User:kui --operation Read --group kui- \
  --resource-pattern-type prefixed
```

If your cluster runs with `allow.everyone.if.no.acl.found=true`, adding the ACLs above is not
enough — everyone still has access to topics with no ACL. Add an explicit deny, or turn that
setting off.

KUI additionally refuses to browse or delete its own audit topic through the UI, and the default
RBAC policy carries a deny rule for it (ADR-023). That is defence in depth, not a substitute for
broker ACLs: a user with direct Kafka access bypasses KUI entirely.

### 4.2 The encryption key

A Kafka record is plaintext to anyone who can read the topic. Every secret KUI stores — SASL
passwords, JAAS material, keystore and truststore bytes, OAuth client secrets — is therefore
encrypted with AES-GCM **before** it is produced, under a key you supply:

```
KUI_STORE_ENCRYPTION_KEY=<32 bytes, base64-encoded>
```

Generate one with `openssl rand -base64 32`. Supply it through an environment variable, a
mounted Kubernetes Secret, or your secret manager. It is never written to the store, never
logged and never returned by any API.

> **Back this key up, separately from the Kafka data.** If you lose it, the encrypted fields in
> `__kui_config` and `__kui_files` are unrecoverable, and every cluster registered through the
> UI has to be re-entered by hand. Backing up the topics without the key backs up nothing
> useful.

**Rotating the key.** Each encrypted field carries the `keyId` it was written with, so old and
new keys coexist:

1. Add the new key alongside the old one:
   `KUI_STORE_ENCRYPTION_KEYS=old:<base64>,new:<base64>` and `KUI_STORE_ENCRYPTION_KEY_ID=new`.
2. Restart the store-connected services (cluster, and identity once it exists). New writes use
   `new`; reads still decrypt records written under `old`.
3. Trigger a rewrite so every record is re-encrypted under the new key:
   `POST /internal/v1/store/rekey` on the cluster service (requires `ApplicationConfig.Edit`,
   and is audited). It replays each key and writes it back at its current version.
4. Once the rekey reports zero records remaining under `old`, drop `old` from the configuration
   and restart again.

Do not skip step 3 and remove the old key: compacted topics keep the *latest* record per key, so
a cluster nobody has edited since the rotation is still encrypted under the old one.

## 5. Backup and restore

The store is a Kafka topic, so back it up the way you back up Kafka topics.

- **Replication factor** is the first line of defence. Use at least 3 in production with
  `min.insync.replicas: 2`, so losing one broker costs you nothing and losing two stops writes
  rather than losing them.
- **Mirroring** to a second cluster with MirrorMaker 2 or your vendor's equivalent gives you a
  disaster-recovery copy. Mirror `__kui_config`, `__kui_files` and `__kui_audit`; keep the same
  partition count, which for the first two means one.
- **Point-in-time export.** For an offline copy, consume each topic from the beginning with keys
  included and store the result:

  ```sh
  kafka-console-consumer --bootstrap-server "$STORE" --topic __kui_config \
    --from-beginning --property print.key=true --property key.separator=$'\t' \
    --timeout-ms 30000 > kui-config-backup.tsv
  ```

  Restore by producing the same key/value pairs back into an empty topic with
  `kafka-console-producer --property parse.key=true`. Because every record carries its own
  `version`, restoring a full log restores the version history too; restoring only the compacted
  tail is also fine, since only the latest version per key matters.
- **The encryption key is part of the backup.** See the warning above.

## 6. When the store is unreachable

KUI does not fall over and it does not quietly lose your edits.

| Situation | Behavior |
| --- | --- |
| Store unreachable **at startup** | The service fails to start with an error naming the store's bootstrap servers. It never starts with an empty cluster registry, because an empty registry looks like "you have no clusters" to every user. |
| Store unreachable **while running** | The service keeps serving from the state it last replayed. Reads work. The affected capability turns `Degraded` with a reason, so the UI shows a banner instead of an empty page, and API responses carry the degraded envelope. |
| A write attempted while degraded | Rejected with a store-unavailable error. Nothing is queued and nothing is silently dropped; the operator retries when the store is back. |
| Two operators editing the same cluster at once | One write wins; the other gets `KUI-CONFIG-VERSION-CONFLICT` and is told to reload and retry. Both KUI replicas converge on the winner's record. |
| A write that times out | `KUI-TIMEOUT` (408, retryable). **The write may still have been applied** — what expired is KUI's wait for the record to come back around the log, not the write itself. Re-read the record to find out. Retrying the same edit at the same base version is safe either way: if it landed, the retry is a version conflict and changes nothing; if it did not, the retry is a fresh write. |

Watch `kui.store.replay.lag`, `kui.store.write.errors` and the store capability's state in the
capability registry. A store capability that has been `Degraded` for more than one refresh
interval is worth an alert.

## 7. Running without the store

Leave `kui.store.kafka.*` unset. KUI then uses the **file adapter**: static configuration files
are the whole truth, nothing is written at runtime, and every UI action that would write to the
store reports `NotConfigured` rather than failing. This is the supported way to run KUI
declaratively from GitOps, and it is what the development Compose file uses when no broker is
designated as the store cluster.

A Kubernetes ConfigMap or Secret mounted as a volume is just a path, so the file adapter reads
it with no extra configuration. There is no separate Kubernetes adapter.

**The directory layout.** Point `kui.store.dir` at a directory laid out as
`<root>/<section>/<id>.json`, one record envelope (§2.1) per file:

```
/etc/kui/store/
  cluster/
    prod-eu.json
    staging-us.json
  settings/
    global.json
```

It is one file per key rather than one large document for two reasons. A Kubernetes ConfigMap
mounts as one file per data entry, so this layout is what a ConfigMap already produces. And a file
that will not parse then costs one key instead of the whole store: KUI reads the directory once at
startup, skips a file that is not readable JSON, whose envelope version it does not support, or
whose embedded `key` disagrees with its path, logs a `WARN` naming the file and the reason, and
starts anyway. The skipped keys are reported in the store's health, so an operator can see exactly
which piece of their configuration is missing rather than inferring it from an empty page.

A missing directory is an empty store, not an error — "no directory" and "an empty directory" are
the same statement about a deployment.

**The file adapter does not decrypt.** Its files are plaintext JSON, which is the point: this is
the way to run KUI with no encryption key at all, and so with none of the "lose the key, lose the
secrets" risk of §4.2. A secret in a file is written as `{"$secret":"..."}` and the file's own
confidentiality is the filesystem's job — a mounted Secret with mode 0400 — exactly as it already
is for `kui.clusters[].security` in the static YAML. A file that contains an *encrypted* field
(`{"$enc":{...}}`) is therefore skipped and reported as unreadable rather than handed on, because
passing a ciphertext along as though it were a password fails much later, at connection time, as
an authentication error nobody can explain.

## 8. Migrating from the file store to the Kafka store

You can move at any time; the two are not mutually exclusive during the move, because static
file configuration stays the canonical base and the store only overlays it.

1. **Pick a store cluster** and create the encryption key. Back the key up before you use it.
2. **Grant the ACLs** from §4.1 to KUI's principal on that cluster.
3. **Configure `kui.store.kafka.*`** and restart one cluster-service replica. It creates the
   topics, finds them empty, and serves everything from the static files exactly as before —
   nothing has changed for your users yet.
4. **Import the existing dynamic file**, if you have one, with the one-shot command:

   ```sh
   kui-cluster store import --from ./dynamic-config.yaml --dry-run
   kui-cluster store import --from ./dynamic-config.yaml
   ```

   `--dry-run` prints the keys it would write and the version each would get, and writes
   nothing. The real run is idempotent: re-running it produces no new records if the store
   already matches.
5. **Restart the remaining replicas.** They replay the topic at startup and converge.
6. **Verify** that a UI edit round-trips: change a cluster's display name, confirm the write is
   acknowledged, and confirm a second replica shows the new name.
7. **Keep the file** as the base. Anything you want to be immutable and GitOps-managed stays in
   the static file; only what operators should edit at runtime belongs in the store.

To go back, unset `kui.store.kafka.*` and restart. Whatever was only in the store is then
invisible, so export it first (§5) and fold it into your static configuration.
