# Operating the KUI metadata store

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

Environment equivalents follow the usual mapping: `KUI_STORE_TOPIC_PREFIX`,
`KUI_STORE_REPLICATION_FACTOR`, `KUI_STORE_KAFKA_BOOTSTRAP_SERVERS`, and so on.

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

Topic configuration KUI creates and validates:

```
__kui_config, __kui_files
  cleanup.policy         = compact
  min.compaction.lag.ms  = 0
  delete.retention.ms    = 86400000        # 24 h: how long a tombstone stays visible
  segment.ms             = 604800000       # 7 days: compaction only runs on closed segments
  min.cleanable.dirty.ratio = 0.1
  max.message.bytes      = 5242880         # __kui_files only; > kui.store.maxFileBytes + envelope
  min.insync.replicas    = kui.store.minInSyncReplicas
  retention.ms           = -1              # compaction is the only thing that removes data

__kui_audit
  cleanup.policy         = delete
  retention.ms           = 7776000000      # 90 days
  compression.type       = gzip
  min.insync.replicas    = kui.store.minInSyncReplicas
```

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
