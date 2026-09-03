# CLAPI-001 — Cluster contract DTOs, redaction and golden files

- **ID:** CLAPI-001
- **Title:** Cluster contract DTOs, redaction and golden files
- **Milestone / Feature:** M1 / CL-001, CL-002, CL-003, BR-001, BR-002, BR-005
- **Owner role:** Chief Architect
- **Size:** M
- **Dependencies / blocked by:** CLDOM-002 (the domain topology model exists)

## Goal (user value)

Everything M1 puts on a screen has a wire shape that is written down once, decoded by the
browser from the same source the server encodes it with, and provably free of secrets. This is
the task that decides what a cluster row, a broker row, a broker config entry and a log
directory *are* on the wire; five later tasks and three frontend tasks read those definitions
rather than inventing them.

## Scope

1. The shared cluster DTO fragments in `libs/contracts-core`, under
   `kui/contracts/cluster/**`, cross-compiled JVM/JS. They are shared because two different
   contracts serialise them: the cluster service's own endpoints (CLAPI-002) and the gateway's
   dashboard aggregation (CLAPI-007). One definition, or the dashboard and the brokers page
   disagree about what a broker is.
2. Explicit Circe codecs and Tapir schemas for each (ADR-007: no `deriveCodec`; a derived codec
   is a wire format nobody reviewed).
3. The redaction rule, as a type: **no DTO in this task has a field that can hold a secret.**
   `ClusterSecurityDto` describes a cluster's security *shape* — protocol and mechanism names —
   and carries no username, no password, no keystore bytes and no JAAS string.
4. Golden documents: one committed sample per DTO, as constants in a `GoldenDocuments` object
   plus files under `test/resources/golden/`, following the M0 pattern
   (`libs/contracts-core/test/resources/golden`, `services/cluster/contract/test/.../GoldenDocuments.scala`).
5. A cross-compiled suite that decodes every golden document on the JVM **and** on Scala.js, and
   a JVM-only suite asserting each constant equals the committed file byte for byte.

## Non-goals

No endpoints (CLAPI-002 declares them). No server logic (CLAPI-004). No topic, partition-detail,
consumer-group or ACL DTOs — M1 implements `ClusterAdmin` only (DEVPLAN §3). No metrics fields:
bytes in / bytes out / throughput are `services/metrics` in M8, and this task must not add
nullable placeholder fields for them (see the decision below). No frontend code.

## Design references

- DEVPLAN §5.2 (`libs.contractsCore` "gains the redacted cluster DTO fragments shared by the
  cluster contract and the gateway aggregation"), §6.5 (this area owns
  `libs/contracts-core/src/kui/contracts/cluster/**`), §10 D5 (what the dashboard may show),
  R-12 (the secret-leak paths and how they are tested).
- `research/kafka/admin-capabilities.md` §1 — **the behavioural source for every field here**.
  `describeCluster` gives `clusterId`, `controller` (nullable during failover), `nodes`
  (`rack()` nullable); `describeConfigs` on a broker gives name, value (`null` when sensitive),
  source, `isReadOnly`, `isSensitive`, synonyms and, from 2.6, documentation;
  `describeLogDirs` gives `Map[brokerId, Map[path, LogDirDescription]]` with a **per-directory**
  `error()` and, from 3.3, `totalBytes`/`usableBytes`.
- `research/kafbat/ui-analysis.md` "Dashboard" and "Brokers" — which of those fields a screen
  actually shows: cluster name, version, broker count, partitions, topics; broker id, disk
  usage and segment count, in-sync replicas, replicas, replica skew, leaders, leader skew, port,
  host, rack.
- `research/design/REFERENCE.md` decides how these render, not what they contain. Its sample
  broker hosts and offsets are invented.
- ADR-007 (explicit codecs), ADR-003 (one contract, both sides), ADR-022 (the typed security
  ADT and its redaction), ADR-031 (`ClusterId` is a slug; `KafkaClusterId` is what the brokers
  report), ADR-041 A2 (a contract module may not see `domain` or `application`).

## Files to create

```
libs/contracts-core/src/kui/contracts/cluster/ClusterDtos.scala
libs/contracts-core/src/kui/contracts/cluster/BrokerDtos.scala
libs/contracts-core/src/kui/contracts/cluster/ClusterSecurityDto.scala
libs/contracts-core/test/src/kui/contracts/cluster/ClusterDtosSuite.scala
libs/contracts-core/test/src/kui/contracts/cluster/ClusterGoldenDocuments.scala
libs/contracts-core/test/resources/golden/cluster-summary.json
libs/contracts-core/test/resources/golden/cluster-row.json
libs/contracts-core/test/resources/golden/broker.json
libs/contracts-core/test/resources/golden/broker-config-entry.json
libs/contracts-core/test/resources/golden/log-dir.json
libs/contracts-core/test/resources/golden/cluster-security.json
```

## Files to change

```
libs/contracts-core/test/src/kui/contracts/GoldenFilesSuite.scala   (add the new constants to the walk)
```

No `build.mill` change: `libs.contractsCore` already has circe, tapir-core and tapir-json-circe
on both platforms.

## Public Scala signatures to implement

```scala
package kui.contracts.cluster

import java.time.Instant
import kui.kernel.{BrokerId, ClusterId, KafkaClusterId}

/** How a cluster's connection is secured, with nothing secret in it.
  *
  * Two strings and two booleans, because that is everything a screen may know: which protocol,
  * which SASL mechanism, whether a truststore and a keystore were configured. A username is a
  * secret-adjacent value (it identifies a service account) and is deliberately absent.
  */
final case class ClusterSecurityDto(
    protocol: String,          // PLAINTEXT | SSL | SASL_PLAINTEXT | SASL_SSL
    mechanism: Option[String], // PLAIN | SCRAM-SHA-256 | ... ; None when not SASL
    truststoreConfigured: Boolean,
    keystoreConfigured: Boolean
)

/** What the cluster looks like from the outside, as of one scrape. */
final case class ClusterSummaryDto(
    kafkaClusterId: Option[KafkaClusterId],
    version: Option[String],
    controllerId: Option[BrokerId],
    controllerKind: String,          // "kraft" | "zookeeper" | "unknown"
    brokerCount: Int,
    onlinePartitionCount: Option[Int],
    offlinePartitionCount: Option[Int],
    underReplicatedPartitionCount: Option[Int],
    totalDiskUsageBytes: Option[Long],
    features: List[String],          // ClusterFeature names, sorted; [] when not probed yet
    scrapedAt: Instant
)

/** One cluster as a row: identity outside the section, data inside it. */
final case class ClusterRowDto(
    id: ClusterId,
    name: String,
    readOnly: Boolean,
    bootstrapServers: String,        // host:port,host:port — an address, never a credential
    security: ClusterSecurityDto,
    summary: kui.contracts.Section[ClusterSummaryDto]
)

final case class BrokerDto(
    id: BrokerId,
    host: String,
    port: Int,
    rack: Option[String],
    isController: Boolean,
    partitionCount: Option[Int],
    leaderCount: Option[Int],
    inSyncReplicaCount: Option[Int],
    replicaSkewPercent: Option[Double],
    leaderSkewPercent: Option[Double],
    diskUsageBytes: Option[Long],
    segmentCount: Option[Int]
)

final case class BrokerConfigEntryDto(
    name: String,
    value: Option[String],           // None when the broker reports it sensitive
    source: String,                  // DYNAMIC_BROKER_CONFIG | STATIC_BROKER_CONFIG | DEFAULT_CONFIG | ...
    isSensitive: Boolean,
    isReadOnly: Boolean,
    documentation: Option[String],   // None below Kafka 2.6, or when the probe says absent
    synonyms: List[String]
)

final case class LogDirDto(
    brokerId: BrokerId,
    path: String,
    error: Option[String],           // per-directory: KafkaStorageException means the dir is offline
    totalBytes: Option[Long],        // Kafka 3.3+
    usableBytes: Option[Long],
    topicCount: Int,
    partitionCount: Int
)
```

Each companion object provides, written out by hand:

```scala
given Codec[X]                       // io.circe, explicit HCursor / Json.obj
given Schema[X]                      // sttp.tapir, with .description(...)
given CanEqual[X, X] = CanEqual.derived
```

Use `kui.contracts.ErrorEnvelope.given` for the `Instant` codec — it is the only RFC-3339
formatter in KUI and the reason a golden file is reproducible on any machine — and
`kui.contracts.KernelCodecs.given` / `KernelSchemas.given` for `ClusterId`, `KafkaClusterId` and
`BrokerId`.

## Decisions this task takes (no ADR covers them)

1. **Identity lives outside the `Section`, data lives inside it.** `ClusterRowDto` carries
   `id`, `name`, `readOnly`, `bootstrapServers` and `security` unconditionally, and only
   `summary` is a `Section`. The exit criterion says an unavailable row "remains clickable": a
   row whose identity was inside the failed section would have nothing to render and nothing to
   link to. This shape is what makes the criterion implementable rather than aspirational.
2. **No metrics fields at all, rather than fields that are always `null`.** DEVPLAN §3 forbids
   metrics in M1. A `bytesInPerSec: Option[Double]` that is always `None` is a promise on the wire
   that nothing keeps, and it would have to be removed (a breaking change) or kept forever. The
   UI renders `—` for a column with no field behind it (CLUI-004), which is the same pixel and
   no wire debt. `topicCount` is absent for the same reason: filling it needs the
   `describeTopics` sweep that belongs to M2 (D5).
3. **`controllerKind` is a string, not an enum, on the wire.** Kafbat models it as
   `ZOOKEEPER | KRAFT | UNKNOWN` and a client that meets a fourth value from a newer KUI must
   degrade rather than fail to decode — the same argument `ReasonCode.fromWire` already makes in
   `contracts-core`. `features` is a `List[String]` for the identical reason, and because
   `ClusterFeature` is a `libs/kafka` type this module must not see (A10).
4. **Skew is a percentage `Double`, computed server-side.** Kafbat computes it in the browser
   from the partition assignment; doing it in the service means one implementation, one rounding
   rule, and a CSV export that matches the table. `None` means "not computable" (a cluster with
   no partitions), which the UI renders `—`, never `0.00%`.
5. **`bootstrapServers` is on the wire; every credential is not.** An operator debugging a
   dead row needs to see the address KUI is dialling. ADR-022's `properties` override map is
   **not** exposed by any DTO in M1: it can contain arbitrary keys, including
   `sasl.jaas.config`, and the only safe redaction of an arbitrary map is not to send it.

## Acceptance criteria

```
$ ./mill libs.contractsCore.jvm.test.testOnly 'kui.contracts.cluster.*'
+ every golden document decodes
+ every DTO round-trips
+ no secret token appears in any encoded document
$ ./mill libs.contractsCore.js.test.testOnly 'kui.contracts.cluster.*'
   # separate invocation: a Scala.js and a JVM test module in one Mill run crash (CLAUDE.md)
$ ./mill libs.contractsCore.jvm.compile libs.contractsCore.js.compile
$ ./mill checkArchitecture
```

## Tests required

`ClusterDtosSuite` (cross-compiled, MUnit + ScalaCheck):

- `everyGoldenDocumentDecodes` — one case per file; a decode failure names the file.
- `everyDtoRoundTrips` (property, generators written in the suite): `decode(encode(x)) == x`.
- `anUnknownControllerKindDecodesRatherThanFailing` — `"quorum-thing"` decodes as itself.
- `absentOptionalFieldsDecodeAsNone` — a minimal document with only the required keys.
- `sectionOkAndSectionUnavailableBothDecodeInAClusterRow` — the two shapes CLUI-003 renders.
- `noSecretFieldExistsOnAnyClusterDto` — encode a `ClusterSecurityDto` built from a profile
  whose every secret field is the distinctive token `kui-secret-canary`, and assert the token
  appears in no encoded document. This is R-12's first of three assertions; the other two are in
  CLAPI-003 and STORE-009.
- `instantsAreRfc3339WithExactlyThreeFractionalDigits` — the golden files' reproducibility rule.

`GoldenFilesSuite` (JVM only, extended): each new constant equals its committed file.

## Observability

None. This module is pure data; it has no logger and no metric, and adding one here would put
logging on both sides of a browser boundary.

## Degraded behaviour

The DTOs *are* the degraded behaviour: every field a failing cluster cannot supply is an
`Option`, and the section wrapper carries the reason. A worker who finds themselves wanting a
sentinel value (`-1`, `""`, `0`) has found a field that should be an `Option`.

## Docs to update

`docs/domain/cluster.md` is **not** this task's (CLDOM owns it). Nothing else: the OpenAPI
document is regenerated by CLAPI-010 once the endpoints that carry these DTOs exist.

## Deviations

1. **`GoldenFilesSuite` walks two constant lists rather than one.** The cluster documents live in
   `ClusterGoldenDocuments` beside the DTOs rather than in `kui.contracts.GoldenDocuments`, and the
   JVM suite concatenates the two. One 400-line constant file per module reads worse than two
   files that each sit next to what they describe.
2. **The `noSecretFieldExistsOnAnyClusterDto` test asserts on field *names* as well as on the
   canary token.** A `ClusterSecurityDto` built entirely from the token would legitimately contain
   it (a protocol *is* a string the caller chose), so the assertion that carries the weight is that
   no field exists that could hold a credential.
3. Everything else is as specified. Six DTOs, six golden documents, explicit codecs, JVM and
   Scala.js suites green.
