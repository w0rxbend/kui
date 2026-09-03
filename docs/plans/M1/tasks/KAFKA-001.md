# KAFKA-001 — `libs/kernel`: the typed cluster connection and security ADT

- **ID:** KAFKA-001
- **Title:** `libs/kernel`: the typed cluster connection and security ADT
- **Milestone / Feature:** M1 / CL-001, CL-007, KU-010
- **Owner role:** Principal Scala Engineer, reviewed by the Chief Architect
- **Context / service:** `libs/kernel` (package `kui.kernel.cluster`)
- **Size:** M
- **Dependencies / blocked by:** none. This is the first task of M1 and four modules are
  written against its output.

## Goal (user value)

An operator describes how KUI reaches a Kafka cluster once — brokers, protocol, mechanism,
certificates — in a shape the compiler checks, and every part of KUI that needs that
description reads the *same* description. No password is representable as a plain `String`,
and nothing that prints a connection can print a secret.

## Scope

1. A new pure package `kui.kernel.cluster`, cross-compiled to the JVM and to Scala.js,
   containing exactly five public types: `BootstrapServers`, `ClusterSecurity` (with its
   supporting types), `ClientProperties`, `AdminTuning` and `ClusterConnection`.
2. Smart constructors that make an invalid connection unrepresentable: a bootstrap list that
   is empty, or an entry without a port, is a `ValidationError`, not a runtime surprise
   inside a Kafka client three layers away.
3. Redaction by construction: every secret-bearing field is `Secret[String]` (KERN-002), and
   `ClientProperties` knows which of its own keys are sensitive so that a rendered property
   map can be logged without editing the log statement at the call site.

## Why this task exists at all (read this before changing the design)

`ARCHITECTURE.md` §4.2 writes every admin port as `describeCluster(profile: ClusterProfile)`.
`ClusterProfile` is a value object of the **cluster domain**. `checkArchitecture` rule A5
forbids `libs/kafka` from depending on a service, and rule A1 forbids a `domain` module from
depending on `libs/kafka-auth`, so that signature cannot be compiled by anybody. ADR-022 says
the ADT lives "in `libs/config` / `ClusterProfile`", which moves the same contradiction one
module over: `libs/kafka` may not depend on `libs/config` either.

DEVPLAN §10 decision **D1** resolves it: the ADT lives in `libs/kernel`, which A1 explicitly
allows the domain to see and which every `libs` module already depends on. `ClusterProfile`
(CLDOM-001) *composes* `ClusterConnection`; `libs/config` decodes it with Ciris (CFGOP-001);
`libs/kafka-auth` renders it (KAFKA-002); `libs/contracts-core` derives the redacted DTO from
it (CLAPI-001). One definition, no mapper, no duplicated redaction rule.

## Non-goals

- **No rendering.** Nothing here produces a `sasl.jaas.config` string or a
  `security.protocol` value; that is KAFKA-002, and it lives in a different module precisely
  so that this one stays free of Kafka.
- **No file or filesystem access.** A keystore is described here, never read; reading is
  KAFKA-003 and is JVM-only.
- **No Ciris decoders.** `libs/kernel` has no configuration dependency. CFGOP-001 writes the
  decoders in `libs/config`.
- **No JSON codecs and no Tapir schemas.** ADR-007 gives `libs/contracts-core` sole ownership
  of how a kernel type is serialised. CLAPI-001 writes them.
- **No `ClusterProfile`**, no display name, no tags, no colour, no enabled flag. Those are
  cluster-domain concerns (CLDOM-001). This package describes a *connection*, nothing else.
- No topic, group, ACL or schema types of any kind (DEVPLAN §3; risk R-11).

## Design references

ADR-022 (the mechanism list this ADT must cover, and the `properties` override layer),
ADR-031 (`ClusterId` is the key; it already exists in `libs/kernel`), ADR-030 (2.8 minimum —
the ADT must not encode version assumptions), ADR-013 (`Secret[A]` semantics), ADR-041 rules
A1 and A5, DEVPLAN §5.2 and §10 decision D1, `ARCHITECTURE.md` §4.1 (the shared-kernel style
these types must match) and §14 ("Secrets: `Secret[A]` everywhere"),
`research/scala/security-research.md` §3 (the mechanism table).

## Files to create

```
libs/kernel/src/kui/kernel/cluster/BootstrapServers.scala
libs/kernel/src/kui/kernel/cluster/ClusterSecurity.scala
libs/kernel/src/kui/kernel/cluster/ClientProperties.scala
libs/kernel/src/kui/kernel/cluster/AdminTuning.scala
libs/kernel/src/kui/kernel/cluster/ClusterConnection.scala
libs/kernel/test/src/kui/kernel/cluster/BootstrapServersSuite.scala
libs/kernel/test/src/kui/kernel/cluster/ClusterSecuritySuite.scala
libs/kernel/test/src/kui/kernel/cluster/ClientPropertiesSuite.scala
libs/kernel/test/src/kui/kernel/cluster/AdminTuningSuite.scala
```

## Files to change

```
libs/testkit/src/kui/testkit/Generators.scala   # add the cluster-connection generators (see "Tests required")
```

`build.mill` is **not** changed by this task: `libs/kernel` already exists and gains no
dependency.

## Public Scala signatures to implement

```scala
package kui.kernel.cluster

/** A comma-separated `host:port` list, exactly as Kafka's `bootstrap.servers` wants it. */
opaque type BootstrapServers = String

object BootstrapServers {
  /** Accepts `host:port[,host:port]*`. Trims surrounding whitespace around each entry, rejects
    * an empty list, an entry with no port, a port outside 1..65535, and a duplicate entry.
    * The error field name is `bootstrapServers`.
    */
  def from(raw: String): Either[ValidationError, BootstrapServers]
  def fromList(entries: List[String]): Either[ValidationError, BootstrapServers]
  def unsafe(raw: String): BootstrapServers

  extension (b: BootstrapServers) {
    def value: String        // the joined form, ready for `bootstrap.servers`
    def hosts: List[String]  // the individual entries, in the configured order
  }

  given Ordering[BootstrapServers]
  given CanEqual[BootstrapServers, BootstrapServers]
}
```

```scala
package kui.kernel.cluster

import kui.kernel.Secret

/** Where the bytes of a keystore or truststore come from.
  *
  * `Inline` carries **base64** rather than `Array[Byte]` for two reasons: an array has
  * reference equality, which would make every case class holding one compare wrongly; and the
  * two places a store can actually come from — a YAML value and an environment variable —
  * both carry text anyway (ADR-013). Decoding happens once, in `libs/kafka-auth`, on the JVM.
  */
enum StoreSource {
  case Inline(base64: Secret[String])
  case FromPath(path: String)
}

enum StoreType { case Jks, Pkcs12, Pem }

final case class TrustStoreRef(
    source: StoreSource,
    password: Option[Secret[String]],
    storeType: StoreType
)

final case class KeyStoreRef(
    source: StoreSource,
    password: Option[Secret[String]],
    keyPassword: Option[Secret[String]],
    storeType: StoreType
)

final case class TlsConfig(
    truststore: Option[TrustStoreRef],
    keystore: Option[KeyStoreRef],
    /** `false` renders `ssl.endpoint.identification.algorithm=""`. It is a case class field
      * rather than a default so that turning verification off is always visible in the
      * configuration file that did it. */
    verifyHostname: Boolean,
    enabledProtocols: Option[List[String]],
    cipherSuites: Option[List[String]]
)

object TlsConfig {
  /** Server certificates from the JVM's default trust store, hostname verification on. */
  val default: TlsConfig
}

enum SaslProtocol { case SaslPlaintext, SaslSsl }

enum SaslMechanism {
  case Plain(username: String, password: Secret[String])
  case ScramSha256(username: String, password: Secret[String])
  case ScramSha512(username: String, password: Secret[String])
  case Gssapi(
      serviceName: String,
      principal: String,
      keyTab: Option[String],
      useTicketCache: Boolean,
      storeKey: Boolean
  )
  case OAuthBearer(
      tokenEndpoint: String,
      clientId: String,
      clientSecret: Secret[String],
      scope: Option[String]
  )
  case AwsMskIam(profile: Option[String], roleArn: Option[String], stsRegion: Option[String])
  case AzureEntra(namespace: String, tokenEndpoint: Option[String])
  case GcpManagedKafka

  /** The value Kafka's `sasl.mechanism` takes: `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`,
    * `GSSAPI`, `OAUTHBEARER`, `AWS_MSK_IAM`, and `OAUTHBEARER` for both Azure Entra and GCP
    * Managed Kafka. It is defined here, on the ADT, so that a new mechanism cannot be added
    * without one.
    */
  def wireName: String
}

enum ClusterSecurity {
  case Plaintext
  case Ssl(tls: TlsConfig)
  case Sasl(protocol: SaslProtocol, mechanism: SaslMechanism, tls: Option[TlsConfig])

  /** The value Kafka's `security.protocol` takes: `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`,
    * `SASL_SSL`. Derived, never configured separately — two fields that must agree are two
    * fields that will eventually disagree. */
  def securityProtocol: String

  /** `true` when the connection uses TLS at all, i.e. `Ssl` or `Sasl(SaslSsl, _, _)`. */
  def usesTls: Boolean

  def tlsConfig: Option[TlsConfig]
}
```

```scala
package kui.kernel.cluster

/** One value of a Kafka client property, carrying whether it may be printed. */
enum PropertyValue {
  case Plain(value: String)
  case Sensitive(value: Secret[String])

  /** The string a Kafka client needs. Named `unsafe` on purpose: every call site is a place a
    * secret leaves the type that protects it, and a reviewer should be able to grep for them. */
  def unsafeValue: String
  def redacted: String   // the value, or "***" for `Sensitive`
}

/** A Kafka client property map that knows which of its own keys are secret.
  *
  * It is also the type of the ADR-022 **override layer**: `kui.clusters[].properties` is
  * parsed into a `ClientProperties` whose sensitive keys were classified by
  * `ClientProperties.isSensitiveKey`, and the renderer applies it last with `++`.
  */
opaque type ClientProperties = Map[String, PropertyValue]

object ClientProperties {
  val empty: ClientProperties

  def apply(entries: (String, PropertyValue)*): ClientProperties

  /** Builds from raw configuration text, classifying each key with `isSensitiveKey`. */
  def fromRaw(entries: Map[String, String]): ClientProperties

  /** `true` for `sasl.jaas.config`, any key ending in `.password`, any key containing
    * `secret`, `credential` or `token`, and `ssl.key.password`. The list is a `val` on the
    * companion (`sensitiveKeyRules`) so that a test can print it and a reviewer can read it.
    */
  def isSensitiveKey(key: String): Boolean

  extension (p: ClientProperties) {
    /** Right-biased union: the argument wins on a duplicate key. This is what makes "the
      * override layer is applied last and wins" a single line rather than a convention. */
    def ++(that: ClientProperties): ClientProperties
    def get(key: String): Option[PropertyValue]
    def keys: Set[String]
    def unsafeValues: Map[String, String]   // for a Kafka client constructor and nothing else
    def redactedValues: Map[String, String] // for logs, error messages and DTOs
    def render: String                      // "k=v, k2=***", sorted by key; what `toString` shows
  }

  given CanEqual[ClientProperties, ClientProperties]
}
```

```scala
package kui.kernel.cluster

import scala.concurrent.duration.FiniteDuration

/** The knobs `libs/kafka` reads when it builds an admin client and when it splits a large
  * request into chunks. Every default is the number `research/kafka/admin-capabilities.md`
  * §0 records from the reference implementations, not a guess.
  */
final case class AdminTuning(
    requestTimeout: FiniteDuration,   // default 30.seconds  -> request.timeout.ms
    apiTimeout: FiniteDuration,       // default 60.seconds  -> default.api.timeout.ms
    topicChunkSize: Int,              // default 200
    partitionChunkSize: Int,          // default 200
    groupChunkSize: Int,              // default 50
    parallelism: Int,                 // default 4
    metadataRefresh: FiniteDuration,  // default 30.seconds  (ARCHITECTURE.md §9)
    capabilityRefresh: FiniteDuration // default 1.hour      (ARCHITECTURE.md §9)
) {
  /** Rejects a non-positive chunk size, a non-positive parallelism, and a `requestTimeout`
    * larger than `apiTimeout` (which would make the per-request bound meaningless). */
  def validate: Either[NonEmptyList[ValidationError], AdminTuning]
}

object AdminTuning {
  val default: AdminTuning
}
```

```scala
package kui.kernel.cluster

import kui.kernel.ClusterId

/** Everything needed to open a client against one cluster, and nothing else.
  *
  * It exists so that a port method takes one parameter instead of four and so that
  * `ClusterProfile` (CLDOM-001) has exactly one field to compose. It deliberately carries the
  * `ClusterId`: `client.id` is derived from it (KAFKA-004) and the admin client pool is keyed
  * by it, so a connection that does not know which cluster it is would need a second
  * parameter everywhere.
  */
final case class ClusterConnection(
    id: ClusterId,
    bootstrapServers: BootstrapServers,
    security: ClusterSecurity,
    /** The ADR-022 override layer. Applied last by the renderer; wins on every key. */
    overrides: ClientProperties,
    admin: AdminTuning
) {
  override def toString: String  // id, bootstrap servers, `security.securityProtocol`, the
                                 // mechanism's `wireName`, and `overrides.render`. Never a
                                 // secret, never a keystore's bytes.
}
```

## ADRs this task must obey

ADR-022 (every mechanism in its table is a case here, and no mechanism carries a bare
`String` password), ADR-031 (`ClusterId` is reused, not redefined), ADR-041 A1/A5 (the whole
reason for the package's location), ADR-013 (`Secret[A]`), ADR-030 (nothing here branches on
a broker version).

## Library coordinates

None new. `libs/kernel` keeps its two dependencies from `DEPENDENCY_MATRIX.md`:
`org.typelevel::cats-core::2.13.0` and, on the JS side only,
`io.github.cquiroz::scala-java-time::2.7.0`. Nothing in this package may reference
`java.nio.file`, `javax.net.ssl`, `java.util.Base64` or `org.apache.kafka.*` — all four break
the Scala.js build, which is the mechanical check that this package stayed pure.

## Acceptance criteria

```
$ ./mill libs.kernel.jvm.test
$ ./mill libs.kernel.js.test     # separate invocation: CLAUDE.md, a JS and a JVM test module
                                 # in one Mill run crash
$ ./mill libs.kernel.jvm.compile # clean under -Werror
```

Both suites green. The JS run is not a formality: it is the proof that decision D1 holds,
because a package the browser cannot compile cannot be the shared home the decision claims.

These must hold:

```scala
assertEquals(BootstrapServers.from("a:9092, b:9092").map(_.hosts), Right(List("a:9092", "b:9092")))
assert(BootstrapServers.from("a").isLeft)          // no port
assert(BootstrapServers.from("").isLeft)           // empty
assert(BootstrapServers.from("a:9092,a:9092").isLeft) // duplicate

assertEquals(ClusterSecurity.Plaintext.securityProtocol, "PLAINTEXT")
assertEquals(ClusterSecurity.Sasl(SaslProtocol.SaslSsl, m, None).securityProtocol, "SASL_SSL")
assertEquals(SaslMechanism.ScramSha512("u", Secret("p")).wireName, "SCRAM-SHA-512")

val p = ClusterConnection(id, bs, ClusterSecurity.Sasl(SaslSsl, Plain("u", Secret("hunter2")), None), ClientProperties.empty, AdminTuning.default)
assert(!p.toString.contains("hunter2"))
assertEquals(ClientProperties.fromRaw(Map("sasl.jaas.config" -> "x")).redactedValues, Map("sasl.jaas.config" -> "***"))
```

## Tests required

- `BootstrapServersSuite` (unit + property):
  - `acceptsAHostPortList`, `trimsWhitespaceAroundEntries`, `preservesOrder`.
  - `rejectsEmpty`, `rejectsEntryWithoutAPort`, `rejectsPortOutOfRange`, `rejectsDuplicates`
    — each asserting the `ValidationError`'s `fieldName` is `bootstrapServers`.
  - `valueRoundTripsThroughFromForEveryGeneratedList` (ScalaCheck).
- `ClusterSecuritySuite` (unit + property):
  - `securityProtocolTable` — one row per constructor shape; this is the test that fails when
    somebody adds a protocol and forgets the mapping.
  - `wireNameTable` — one row per `SaslMechanism` case. Both tables must be **exhaustive over
    the enum**: assert `SaslMechanism` case count against the table size using a
    `compileErrors`-free approach (a `match` in the test with no default, so a new case is a
    compile error under `-Werror`).
  - `toStringNeverContainsASecret` — property over arbitrary non-empty passwords, keystore
    passwords and client secrets: the rendered `ClusterConnection`, `TlsConfig` and
    `SaslMechanism` strings contain none of them.
  - `usesTlsAgreesWithSecurityProtocol` — property: `usesTls` iff the protocol ends in `SSL`.
- `ClientPropertiesSuite` (unit + property):
  - `overrideLayerWins` — `(a ++ b).get(k) == b.get(k)` for every shared key.
  - `sensitiveKeysAreClassifiedFromRaw` — a table over the documented rules, including the
    negative cases (`ssl.truststore.location` is *not* sensitive; `ssl.key.password` is).
  - `redactedValuesNeverContainASensitiveValue` (property).
  - `renderIsSortedAndStable`.
- `AdminTuningSuite` (unit): `defaultsMatchTheResearchNumbers` (a table asserting 30 s / 60 s /
  200 / 200 / 50 / 4 / 30 s / 1 h — the test that makes a silent default change visible);
  `validateRejectsNonPositiveChunkSize`; `validateRejectsRequestTimeoutLargerThanApiTimeout`.
- All four suites are cross-compiled and run on both platforms (`KuiCrossTests`, as
  `libs/kernel`'s existing suites do).

Generators added to `libs/testkit` (`kui.testkit.ClusterGenerators`), because CFGOP-001,
CLDOM-001 and CLAPI-001 all need them and three copies would drift:
`genBootstrapServers`, `genTlsConfig`, `genSaslMechanism`, `genClusterSecurity`,
`genClientProperties`, `genClusterConnection`, plus `genSecretString` producing awkward
passwords (quotes, backslashes, `=`, `;`, spaces, unicode) — KAFKA-002's injection property
test consumes exactly that generator.

## Observability

None. This package has no effect type and performs no I/O. It does, however, fix the
vocabulary every later log line uses: an admin log line names a cluster by
`ClusterId.value` and a protocol by `securityProtocol`, never by a class name.

## Degraded behavior

Not applicable — there is nothing to degrade. The one behaviour worth naming is that a
*malformed* connection is a `ValidationError` at construction, which CFGOP-001 accumulates
with every other configuration error into the single startup message the milestone's exit
criteria require. Nothing here throws.

## Docs to update

None in this task. `docs/domain/cluster.md` is CLDOM-001's; the ADR-022 amendment recording
where the ADT lives is written by CFGOP-008, from decision D1 — do not edit the ADR here.

## Deviations

*(filled in by the implementer, in the same commit)*
