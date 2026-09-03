# STORE-004 — the `kui.store.*` configuration slice

- **ID:** STORE-004
- **Title:** the `kui.store.*` configuration slice
- **Milestone / Feature:** M1 / OT-004, ADR-042 §1, ADR-013
- **Owner role:** Principal Scala Engineer
- **Context / service:** `libs/config`, package `kui.config`
- **Size:** S
- **Dependencies / blocked by:** STORE-001, KAFKA-001

## Goal (user value)

An operator points KUI at a store cluster with a handful of keys, gets told at startup — in one
message, with every problem listed — if any of them is wrong, and never has to guess whether the
store is on or off: `kui.store.kafka.bootstrapServers` present means the Kafka store, absent
means the file adapter, and there is no third state.

## Scope

The `kui.store.*` half of the configuration model, added to `KuiConfig` alongside the existing
`server`, `gateway`, `auth` and `telemetry` sections, in CFG-001's style: one explicit
`ConfigValue` per field (ADR-013, no derivation), each naming its `KUI_*` environment key,
accumulated errors, and `Secret` fields that redact in every message.

## Non-goals

**Not `kui.clusters[]`.** CFGOP-001 owns that slice and the `properties` override layer; this
task must not touch it. The two slices share the `ClusterConnection` decoder that CFGOP-001
writes — see "Cross-area contract" below; whichever of the two lands first writes it, and the
second reuses it. **No store construction**: this task produces a `StoreConfig` value and nothing
opens a socket. Wiring `StoreConfig` into a `ConfigStore[F]` is STORE-005/STORE-006, and choosing
which adapter to build is CLAPI-005's composition root. **No `kui.store.encryptionKey` handling
beyond decoding** — the keyring's rules are STORE-002's.

## Design references

ADR-013 (Ciris, precedence CLI → env → file → default, hand-written loaders, `Secret`),
ADR-042 §1 and §7 (statically configured store cluster; no `kui.store.kafka.*` means the file
adapter), `docs/operations/metadata-store.md` §1 (the YAML block this must accept **verbatim**)
and §4.2 (the encryption-key keys). CFG-001's implementation is the pattern to copy — read
`libs/config/src/kui/config/GatewayConfig.scala` and `KuiConfigSource.scala` before writing a
line. DEVPLAN §7 row "Configuration".

## Files to create

```
libs/config/src/kui/config/StoreConfig.scala
libs/config/test/src/kui/config/StoreConfigSuite.scala
libs/config/test/resources/config/store-valid.yaml
libs/config/test/resources/config/store-invalid.yaml
```

## Files to change

```
libs/config/src/kui/config/KuiConfig.scala           (the `store` field)
libs/config/src/kui/config/KuiConfigSource.scala     (load the slice; keep the unknown-key check honest)
docs/operations/configuration.md                      (the kui.store.* key table)
docs/operations/metadata-store.md                     (§1: the full key table with defaults)
```

`KuiConfigSource`'s unknown-key detection (CFG-001) works from the set of known paths. Adding a
section without registering its paths would make every `kui.store.*` key an unknown-key error, so
the registration is part of this task and `store-valid.yaml` is the test that proves it.

## Public Scala signatures to implement

```scala
package kui.config

import kui.kernel.{PositiveInt, Secret}
import kui.kernel.cluster.{BootstrapServers, ClusterSecurity}   // KAFKA-001
import scala.concurrent.duration.FiniteDuration

/** How KUI reaches the cluster that holds its own metadata. Absent means "no Kafka store". */
final case class StoreKafkaConfig(
    bootstrapServers: BootstrapServers,
    security: ClusterSecurity,
    properties: Map[String, String]      // the same last-wins override layer as a managed cluster
)

final case class StoreEncryptionConfig(
    keys: Map[String, Secret[String]],   // id -> base64 material, still undecoded
    activeKeyId: String
)

final case class StoreConfig(
    topicPrefix: String,
    replicationFactor: Short,
    minInSyncReplicas: PositiveInt,
    maxFileBytes: Long,
    replayTimeout: FiniteDuration,
    writeTimeout: FiniteDuration,
    dir: Option[Path],                        // the file adapter's root
    kafka: Option[StoreKafkaConfig],
    encryption: Option[StoreEncryptionConfig]
):
  /** True when the Kafka adapter should be built. Exactly `kafka.isDefined`; a named method so
    * that no caller re-derives the rule and gets it subtly different. */
  def kafkaEnabled: Boolean

object StoreConfig:
  val Default: StoreConfig
  def configValue: ConfigValue[Effect, StoreConfig]     // in KuiConfigSource's style
```

## The keys, decided here

Every key, its environment variable, its default and its rule. This table is the one
`docs/operations/configuration.md` prints, and `metadata-store.md` §1 must be corrected to match
it where it differs.

| YAML path | Env | Type | Default | Rule |
| --- | --- | --- | --- | --- |
| `kui.store.topicPrefix` | `KUI_STORE_TOPIC_PREFIX` | string | `__kui_` | matches `^[a-z0-9_.-]{1,64}$`; a prefix that is not `__kui_` is legal and logged at INFO, because two KUI installations on one cluster need distinct prefixes |
| `kui.store.replicationFactor` | `KUI_STORE_REPLICATION_FACTOR` | short | `3` | 1…32767; **1 is allowed** and warns, because single-broker development is a supported mode (ADR-042 §7) |
| `kui.store.minInSyncReplicas` | `KUI_STORE_MIN_INSYNC_REPLICAS` | int | `2` | ≥ 1 **and ≤ `replicationFactor`**; a cross-field rule, so it is validated in `StoreConfig`'s own check and reports both values |
| `kui.store.maxFileBytes` | `KUI_STORE_MAX_FILE_BYTES` | long | `4194304` | 1 KiB … 64 MiB |
| `kui.store.replayTimeout` | `KUI_STORE_REPLAY_TIMEOUT` | duration | `30s` | 1s … 10m; the R-2 bound that turns a hang into `KUI-STORE-REPLAY-TIMEOUT` |
| `kui.store.writeTimeout` | `KUI_STORE_WRITE_TIMEOUT` | duration | `10s` | 1s … 2m; how long a write waits to read itself back (STORE-007) |
| `kui.store.dir` | `KUI_STORE_DIR` | path | none | the file adapter's root; ignored when `kafka` is set |
| `kui.store.kafka.bootstrapServers` | `KUI_STORE_KAFKA_BOOTSTRAP_SERVERS` | list | none | comma-separated in the env form; **its presence turns the Kafka store on** |
| `kui.store.kafka.security.*` | `KUI_STORE_KAFKA_SECURITY_*` | `ClusterSecurity` | `PLAINTEXT` | the KAFKA-001 ADT, decoded by the shared decoder |
| `kui.store.kafka.properties` | — | map | empty | raw client properties, applied last; redacted by key pattern in every rendering |
| `kui.store.encryptionKey` | `KUI_STORE_ENCRYPTION_KEY` | secret | none | sugar: one key whose id is `k1` |
| `kui.store.encryptionKeys` | `KUI_STORE_ENCRYPTION_KEYS` | secret | none | `id:base64,id:base64` — the rotation form of `metadata-store.md` §4.2 |
| `kui.store.encryptionKeyId` | `KUI_STORE_ENCRYPTION_KEY_ID` | string | `k1`, or required with `encryptionKeys` | which key new writes use |

### Cross-field rules, each with its own accumulated error

1. `kafka` present **and** `encryption` absent → error at key `kui.store.encryptionKey`:
   *"a Kafka metadata store requires an encryption key; generate one with `openssl rand -base64 32`
   and set KUI_STORE_ENCRYPTION_KEY (see docs/operations/metadata-store.md §4.2)"*. This is
   STORE-002's decision 2 enforced where the operator can act on it.
2. `encryptionKey` **and** `encryptionKeys` both set → error naming both keys. One way to say a
   thing; the sugar exists for the common case, not as an alias to merge.
3. `encryptionKeyId` not among `encryptionKeys`' ids → error listing the ids present.
4. `minInSyncReplicas > replicationFactor` → one error naming both values and both keys.
5. `kafka` absent **and** `dir` absent → **not an error.** `ConfigStore.empty` is used and every
   store-backed write reports `NotConfigured`. This is the default for a first run and for every
   M0-era configuration file, which must keep loading unchanged.
6. `dir` set to a path that does not exist → **not an error** at load time; STORE-003 treats a
   missing root as an empty store, and a Kubernetes volume that mounts a moment after the process
   starts is a real thing.

**Redaction.** `encryptionKey`, `encryptionKeys`, `kafka.security`'s secret fields and any entry
of `kafka.properties` whose key matches `(?i).*(password|secret|key|token|credential).*` render
as `***` in every problem message, in the startup configuration log (CFG-002), and in `toString`.
The `properties` redaction predicate is shared with CFGOP-001 — see below.

## Cross-area contract

Two definitions are needed by both this task and CFGOP-001 (`kui.clusters[]`), and must exist
exactly once:

```scala
package kui.config

/** Decodes the KAFKA-001 `ClusterSecurity` ADT from YAML/env, in ADR-013's explicit style. */
object ClusterSecurityConfig:
  def configValue(prefix: String): ConfigValue[Effect, ClusterSecurity]

/** The last-wins raw client-property layer, and the key pattern that redacts it. */
object ClientPropertyOverrides:
  val SecretKeyPattern: Regex
  def redact(properties: Map[String, String]): Map[String, String]
  def configValue(prefix: String): ConfigValue[Effect, Map[String, String]]
```

They live in `libs/config/src/kui/config/ClusterSecurityConfig.scala` and
`ClientPropertyOverrides.scala`, which are **CFGOP-001's files** by DEVPLAN §6.5 (the
`kui.clusters[]` slice). Rule for whichever task starts second: if the file exists, use it and
add nothing; if it does not, create it with exactly these two signatures and note in the
Implementation Report that the other task must reuse it. Do not create a second decoder under a
different name — two decoders for one ADT is the thing ADR-013's "hand-written, one loader per
field" discipline is meant to make visible, not to permit twice.

## Library coordinates

None new: `is.cir::ciris::3.15.0`, `is.cir::ciris-circe-yaml::3.15.0`, `io.circe::circe-yaml::0.16.1`.

## Acceptance criteria

```
$ ./mill libs.config.test
$ ./mill libs.config.test.testOnly kui.config.StoreConfigSuite
```

Behavioural acceptance, by hand, once SVC-004 can run the cluster app:

```
$ ./mill services.cluster.app.run -- --config libs/config/test/resources/config/store-invalid.yaml
kui.store.minInSyncReplicas: expected <= kui.store.replicationFactor (1), got 2   (file: store-invalid.yaml)
kui.store.encryptionKey: a Kafka metadata store requires an encryption key; generate one with `openssl rand -base64 32` and set KUI_STORE_ENCRYPTION_KEY (see docs/operations/metadata-store.md §4.2)   (file: store-invalid.yaml)
kui.store.replayTimeout: expected 1s..10m, got 0s   (file: store-invalid.yaml)
# exit code 1, three lines, no stack trace, no key material printed
```

```
$ KUI_STORE_KAFKA_BOOTSTRAP_SERVERS=b:9092 KUI_STORE_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  ./mill libs.config.test.testOnly kui.config.StoreConfigSuite
```
— the precedence test reads the real environment, as CFG-001's `PrecedenceSuite` already does.

## Tests required

- `StoreConfigSuite` (unit + property):
  - `storeValidYamlLoads` — every field of `store-valid.yaml` (which is
    `metadata-store.md` §1's block, copied) reaches `StoreConfig` with the right value.
  - `absentStoreSectionYieldsDefaultsAndNoKafka` — an M0-era file still loads; `kafkaEnabled` is
    false. This is the regression guard for "everything else in M1 still passes with no store".
  - `envBeatsFileForEveryStoreKey` — table over the key table above.
  - `accumulatesEveryCrossFieldError` — `store-invalid.yaml` yields exactly the three problems of
    the acceptance block, in that order.
  - `kafkaWithoutEncryptionKeyIsRejected` and its message contains the `openssl` hint.
  - `encryptionKeyAndEncryptionKeysTogetherAreRejected`.
  - `encryptionKeyIdMustBeAmongTheKeys` — the message lists the ids and no material.
  - `minIsrAboveReplicationFactorIsRejected` — both values in one message.
  - `singleKeySugarGetsTheIdK1`.
  - `unknownStoreKeyIsRejected` — `kui.store.topicPrefx` names the key (CFG-001's rule still
    holds for the new section).
  - `everyRenderingRedactsSecrets` (property): for arbitrary key material and arbitrary
    `properties` maps containing a key matching `SecretKeyPattern`, neither
    `StoreConfig.toString`, nor any accumulated problem message, nor
    `ClientPropertyOverrides.redact`'s output contains the value.
  - `replicationFactorOneWarnsAndLoads`.

## Observability

The startup configuration log (CFG-002) gains the store section, with `encryption` rendered as
`keys=[k1,k2] active=k1` — ids, never material — and `kafka.properties` passed through
`ClientPropertyOverrides.redact`. One INFO line at startup when `topicPrefix` is not the default,
and one WARN when `replicationFactor` is 1.

## Degraded behavior

None: an invalid configuration is a startup failure, per CFG-001. The one thing this task must
**not** do is treat an unreachable store cluster as a configuration error — reachability is not
knowable at load time and is STORE-005's business.

## Docs to update

`docs/operations/configuration.md`: the `kui.store.*` rows of the key table, in the existing
format. `docs/operations/metadata-store.md` §1: replace the YAML sketch with the exact key table
above, including `replayTimeout`, `writeTimeout` and `dir`, which the page does not currently
mention, and correct the `encryptionKeys` form to the one implemented here.
