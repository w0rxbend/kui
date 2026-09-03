# KAFKA-002 — `libs/kafka-auth`: client property and JAAS rendering with quoting

- **ID:** KAFKA-002
- **Title:** `libs/kafka-auth`: client property and JAAS rendering with quoting
- **Milestone / Feature:** M1 / CL-007, KU-010, KU-011
- **Owner role:** Principal Scala Engineer, reviewed by the Security Engineer
- **Context / service:** `libs/kafka-auth` (new module)
- **Size:** L
- **Dependencies / blocked by:** KAFKA-001

## Goal (user value)

A password with a quote in it works. That sentence is the whole task: Kouncil builds its JAAS
strings with `String.format`, so a `"` in a password either breaks the connection or injects
extra login-module options, and Kafbat hands raw properties (including `sasl.jaas.config`)
straight through and then returns them from an HTTP endpoint. KUI assembles every Kafka client
property in exactly one function, quotes correctly, and knows which of the properties it
produced may never be printed.

## Scope

1. Create the Mill module `libs.kafkaAuth` (JVM only).
2. `ClientPropertyRenderer.render` — the single function that turns a `ClusterConnection`
   (KAFKA-001) into a `ClientProperties` a Kafka client can be constructed from: the
   `security.*`, `sasl.*` and `ssl.*` keys, the `client.id`, the mechanism-specific login
   module, and the ADR-022 override layer applied last.
3. `Jaas` — the JAAS grammar, correctly: option values quoted, backslash and double quote
   escaped, and values the grammar genuinely cannot carry rejected rather than mangled.
4. The mechanism table: PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, GSSAPI, OAUTHBEARER, AWS MSK IAM,
   Azure Entra and GCP Managed Kafka each render the property set its vendor documents.
5. Redaction: every property this module marks `Sensitive` is a set a reviewer can read in one
   place, and a property test asserts no rendered secret survives `redactedValues`.

## Non-goals

- **No file I/O.** Materializing an inline keystore to disk and probing for the optional cloud
  login handlers is KAFKA-003. This task renders the properties as if the store were already
  available at a path, and leaves `StoreSource.Inline` to KAFKA-003's `Resource`. Keeping this
  module's core pure — string in, string out — is what makes the injection property test
  cheap, and it is what lets M8's configuration wizard render a candidate profile without
  starting a Kafka client (DEVPLAN §5.1).
- **No Kafka client.** `libs/kafka-auth` does not depend on `kafka-clients` at compile scope
  and never constructs an `Admin`, a consumer or a producer. It depends on it in **test scope
  only**, to parse what it renders with Kafka's own parser.
- **No consumer or producer settings** beyond the security ones (`max.poll.records`,
  `isolation.level` and friends belong to their own factories in `libs/kafka`).
- No Schema Registry, Connect or ksqlDB auth. ADR-022 gives those endpoints their own typed
  `auth`; they arrive with M3–M4.

## Design references

ADR-022 (the decision this task implements, in full), `research/scala/security-research.md` §3
(the mechanism table, Kafbat's raw-property leak, Kouncil's `String.format` injection — the two
defects this task exists not to repeat), ADR-013 (`Secret[A]`), DEVPLAN §5.1 (why this module
is separate from `libs/kafka`), DEVPLAN §7 (the property-rendering suite row), DEVPLAN §8 risk
R-1 (mechanisms that cannot be integration-tested locally), and the Apache Kafka security
documentation for each mechanism's property set.

## Files to create

```
libs/kafka-auth/src/kui/kafka/auth/ClientPropertyRenderer.scala
libs/kafka-auth/src/kui/kafka/auth/ClientPurpose.scala
libs/kafka-auth/src/kui/kafka/auth/Jaas.scala
libs/kafka-auth/src/kui/kafka/auth/LoginModules.scala
libs/kafka-auth/test/src/kui/kafka/auth/ClientPropertyRendererSuite.scala
libs/kafka-auth/test/src/kui/kafka/auth/JaasSuite.scala
libs/kafka-auth/test/src/kui/kafka/auth/MechanismTableSuite.scala
libs/kafka-auth/test/resources/golden/properties-sasl-ssl-scram512.properties
libs/kafka-auth/test/resources/golden/properties-aws-msk-iam.properties
libs/kafka-auth/test/resources/golden/properties-azure-entra.properties
libs/kafka-auth/test/resources/golden/properties-gcp-managed-kafka.properties
libs/kafka-auth/test/resources/golden/properties-gssapi.properties
```

## Files to change

```
build.mill    # add `object kafkaAuth` inside `object libs`, and one entry in `object Versions`
```

Add exactly this module object, immediately after `object config`:

```scala
  /** Kafka client properties assembled from the typed security ADT (ADR-022).
    *
    * Pure by design and separate from `libs/kafka` on purpose: it renders strings, holds no
    * Kafka client, and is therefore unit-testable as string-in / string-out. That is what
    * makes the JAAS injection property test cheap, and what will let M8's configuration
    * wizard validate a candidate profile without dragging a Kafka client into the process
    * doing the validating.
    */
  object kafkaAuth extends KuiPureModule with KuiJvmModule {
    override def moduleDir = mill.api.BuildCtx.workspaceRoot / "libs" / "kafka-auth"

    def moduleDeps = Seq(kernel.jvm)

    def mvnDeps = Seq(
      mvn"org.typelevel::cats-core::${Versions.cats}",
      mvn"org.typelevel::cats-effect::${Versions.catsEffect}",
      mvn"co.fs2::fs2-core::${Versions.fs2}",
      mvn"co.fs2::fs2-io::${Versions.fs2}",
      mvn"org.typelevel::log4cats-core::${Versions.log4cats}"
    )

    object test extends ScalaTests with KuiTests {
      def moduleDeps = super.moduleDeps ++ Seq(testkit.jvm)
      // Test scope only, and it is the point of the suite: what this module renders is parsed
      // back by Kafka's own JAAS parser, not by a reimplementation that could share its bug.
      def mvnDeps =
        super.mvnDeps() ++ Seq(mvn"org.apache.kafka:kafka-clients:${Versions.kafkaClients}")
    }
  }
```

and add `val kafkaClients = "4.3.1"` to `object Versions` — the number `DEPENDENCY_MATRIX.md`
records for `org.apache.kafka:kafka-clients`. `fs2-io` is declared now because KAFKA-003 needs
`Files[F]` and adding it in the next task would mean editing this object twice.

## Public Scala signatures to implement

```scala
package kui.kafka.auth

/** Which client the properties are for. It changes only the `client.id` prefix and which
  * non-security defaults are added, but it is an explicit parameter because a shared
  * `client.id` across an admin client and a consumer makes broker-side quotas and broker logs
  * unattributable (`research/kafka/admin-capabilities.md` §0, "Client id").
  */
enum ClientPurpose {
  case Admin, Consumer, Producer
  def prefix: String   // "kui-admin", "kui-consumer", "kui-producer"
}
```

```scala
package kui.kafka.auth

import cats.data.NonEmptyList
import kui.kernel.ValidationError
import kui.kernel.cluster.*

object ClientPropertyRenderer {

  /** The single place in KUI where a Kafka client property map is assembled.
    *
    * Order of assembly, and it is binding:
    *   1. `bootstrap.servers` and `client.id`
    *   2. `security.protocol`, from `security.securityProtocol`
    *   3. the TLS block, when the security setting uses TLS at all
    *   4. the SASL block: `sasl.mechanism`, `sasl.jaas.config`, and the mechanism-specific
    *      keys (callback handler class, token endpoint, Kerberos service name)
    *   5. the per-purpose non-security defaults
    *   6. `connection.overrides`, applied last, winning on every key (ADR-022)
    *
    * An inline keystore renders `ssl.truststore.location` / `ssl.keystore.location` pointing
    * at the path `materialized` supplies. When `materialized` has no entry for a store that is
    * `StoreSource.Inline` and not PEM, the render fails rather than emitting a property with
    * an empty path. KAFKA-003 owns filling that map.
    */
  def render(
      connection: ClusterConnection,
      purpose: ClientPurpose,
      clientId: String,
      materialized: Map[StoreRole, String] = Map.empty
  ): Either[NonEmptyList[ValidationError], ClientProperties]

  enum StoreRole { case TrustStore, KeyStore }
}
```

```scala
package kui.kafka.auth

import kui.kernel.{Secret, ValidationError}

/** The JAAS grammar, and only it.
  *
  * `sasl.jaas.config` is parsed by Kafka with the `javax.security.auth.login` grammar, read by
  * a `java.io.StreamTokenizer` configured with the double quote as its quote character. That
  * tokenizer understands an escaped backslash and an escaped quote inside a quoted value, and
  * it does not understand a raw line break inside one. Both facts are encoded here rather than
  * discovered by an operator whose password happens to contain a quote.
  */
object Jaas {

  /** Renders `<loginModule> <flag> k="v" k2="v2";` — one module, terminated by a semicolon,
    * which is the shape `sasl.jaas.config` takes for a client.
    *
    * Returns `Left` when any option value contains a character the grammar cannot carry: the
    * C0 control characters, line feed, carriage return and tab among them. See "Deviations" —
    * silently mangling such a password is worse than refusing it at startup with a named
    * error that does not echo the value.
    */
  def module(
      loginModule: String,
      flag: String,                              // "required" everywhere in M1
      options: List[(String, JaasValue)]
  ): Either[ValidationError, Secret[String]]

  enum JaasValue { case Plain(v: String); case Hidden(v: Secret[String]) }

  /** Backslash becomes two backslashes, a double quote becomes an escaped double quote, and
    * the result is wrapped in double quotes. Public because `JaasSuite` tests it directly and
    * because a reviewer should be able to read the escaping rule as three lines. */
  def quote(value: String): String

  /** The characters the grammar cannot carry, exposed so an error message can name them. */
  val forbiddenCharacters: Set[Char]
}
```

```scala
package kui.kafka.auth

/** The fully qualified login-module and callback-handler class names, in one object.
  *
  * They are strings rather than `classOf[...]` deliberately: naming the class would put
  * `kafka-clients` and the optional cloud SDKs on this module's compile classpath, which is
  * exactly what rule A10 and ADR-022's "optional runtime modules" exist to prevent. KAFKA-003
  * checks at runtime whether a name resolves and reports a usable error when it does not.
  */
object LoginModules {
  val Plain: String           = "org.apache.kafka.common.security.plain.PlainLoginModule"
  val Scram: String           = "org.apache.kafka.common.security.scram.ScramLoginModule"
  val Gssapi: String          = "com.sun.security.auth.module.Krb5LoginModule"
  val OAuthBearer: String     = "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule"
  val AwsMskIam: String       = "software.amazon.msk.auth.iam.IAMLoginModule"
  val GcpManagedKafka: String = "com.google.cloud.hosted.kafka.auth.GcpLoginModule"

  val AwsMskIamCallbackHandler: String = "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
  val OAuthBearerCallbackHandler: String =
    "org.apache.kafka.common.security.oauthbearer.secured.OAuthBearerLoginCallbackHandler"
}
```

### The mechanism table this task implements

Every row is what the mechanism's own documentation specifies. A row without an integration
test is still a row with a golden-file test (risk R-1): no mechanism is claimed as supported
without at least a string-level assertion.

| Mechanism | `sasl.mechanism` | Login module | Other keys | Integration-tested in M1 |
| --- | --- | --- | --- | --- |
| `Plain` | `PLAIN` | `PlainLoginModule` | JAAS `username`, `password` | yes (CFGOP-004) |
| `ScramSha256` / `ScramSha512` | `SCRAM-SHA-256` / `SCRAM-SHA-512` | `ScramLoginModule` | JAAS `username`, `password` | yes (CFGOP-004) |
| `Gssapi` | `GSSAPI` | `Krb5LoginModule` | `sasl.kerberos.service.name`; JAAS `useKeyTab`, `keyTab`, `principal`, `storeKey`, `useTicketCache`, `refreshKrb5Config=true` | no — golden file only |
| `OAuthBearer` | `OAUTHBEARER` | `OAuthBearerLoginModule` | `sasl.login.callback.handler.class` = `OAuthBearerCallbackHandler`, `sasl.oauthbearer.token.endpoint.url`; JAAS `clientId`, `clientSecret`, `scope` | no — golden file only |
| `AwsMskIam` | `AWS_MSK_IAM` | `IAMLoginModule` | `sasl.client.callback.handler.class` = `IAMClientCallbackHandler`; JAAS `awsProfileName`, `awsRoleArn`, `awsStsRegion` when set | no — golden file only |
| `AzureEntra` | `OAUTHBEARER` | `OAuthBearerLoginModule` | `sasl.login.callback.handler.class` = `OAuthBearerCallbackHandler`, `sasl.oauthbearer.token.endpoint.url` — the configured endpoint, defaulting to `https://login.microsoftonline.com/<namespace>/oauth2/v2.0/token` when none is given | no — golden file only |
| `GcpManagedKafka` | `OAUTHBEARER` | `GcpLoginModule` | the handler library's own callback handler class | no — golden file only |

TLS keys, whenever the security setting uses TLS: `ssl.truststore.type`, `ssl.truststore.location`
(or `ssl.truststore.certificates` for PEM), `ssl.truststore.password`, `ssl.keystore.type`,
`ssl.keystore.location` (or `ssl.keystore.key` plus `ssl.keystore.certificate.chain` for PEM),
`ssl.keystore.password`, `ssl.key.password`, `ssl.endpoint.identification.algorithm`,
`ssl.enabled.protocols`, `ssl.cipher.suites`.

`ssl.endpoint.identification.algorithm` is rendered **explicitly in both cases** — the empty
string when `verifyHostname` is false, `https` when it is true. Relying on the client's default
for the "on" case is how a hostname check silently disappears in a client upgrade.

Sensitive keys, marked `PropertyValue.Sensitive` by this renderer: `sasl.jaas.config`,
`ssl.truststore.password`, `ssl.keystore.password`, `ssl.key.password`, `ssl.keystore.key`,
`ssl.truststore.certificates`, `ssl.keystore.certificate.chain`.

## ADRs this task must obey

ADR-022 (the mechanism list, the override layer applied last, correct quoting), ADR-013
(`Secret[A]` becomes a `String` only through `unsafeValue`), ADR-041 rule A10 (no
`kafka-clients` at compile scope in this module — CFGOP-003's build test asserts it), ADR-034
(a bad configuration is a returned `ValidationError`, never a thrown exception).

## Library coordinates

From `DEPENDENCY_MATRIX.md`: `org.typelevel::cats-core::2.13.0`,
`org.typelevel::cats-effect::3.7.1`, `co.fs2::fs2-core::3.13.0`, `co.fs2::fs2-io::3.13.0`,
`org.typelevel::log4cats-core::2.8.0`. Test scope: `org.scalameta::munit::1.3.6`,
`org.scalameta::munit-scalacheck::1.3.1`, `org.scalacheck::scalacheck::1.20.0` (all inherited
from `KuiTests` and `libs.testkit`), plus `org.apache.kafka:kafka-clients:4.3.1` declared test
only. The optional cloud coordinates of `DEPENDENCY_MATRIX.md` — `software.amazon.msk:aws-msk-iam-auth:2.3.7`,
`com.azure:azure-identity:1.18.6`, `com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler:1.0.6`,
`com.google.oauth-client:google-oauth-client:1.39.0` — are **not** added here; KAFKA-003
declares them as optional runtime coordinates and documents them.

## Acceptance criteria

```
$ ./mill libs.kafkaAuth.compile        # clean under -Werror
$ ./mill libs.kafkaAuth.test
$ ./mill libs.kafkaAuth.checkFormat
$ ./mill libs.kafkaAuth.fix --check
```

The round trip that closes the injection bug, asserted with Kafka's own parser:

```scala
val awkward = """he said "hi" \ and = ; then"""
val props   = renderScram512(user = "u", password = Secret(awkward))
val jaas    = props.get("sasl.jaas.config").get.unsafeValue
val entry   = JaasContext
  .loadClientContext(java.util.Map.of("sasl.jaas.config", new Password(jaas)))
  .configurationEntries
  .get(0)
assertEquals(entry.getOptions.get("password"), awkward)
assertEquals(entry.getOptions.size, 2)   // username and password — nothing was injected
```

The redaction assertion:

```scala
assert(!props.render.contains(awkward))
assert(!props.redactedValues.values.exists(_.contains(awkward)))
```

## Tests required

- `JaasSuite`:
  - `quoteEscapesBackslashThenQuote` — a table including a lone backslash, a lone quote, an
    escaped quote, two quotes, the empty string and a value that is only backslashes.
  - **`renderedJaasParsesBackToTheInput`** (ScalaCheck, over `genSecretString` from KAFKA-001's
    testkit generators, at least 1000 cases): for any username and any password made of
    quotes, backslashes, spaces, `=`, `;`, `,`, braces and unicode, the JAAS string parses
    through `JaasContext.loadClientContext` back to exactly the inputs, and the parsed entry
    holds exactly the options that were rendered and no others. *This is the test that closes
    Kouncil's `String.format` injection; it is the reason this module exists.*
  - `rejectsControlCharacters` — line feed, carriage return and tab each produce a `Left` whose
    message names the character class and the field and never echoes the password.
  - `moduleTerminatesWithASemicolon`.
- `MechanismTableSuite`:
  - one test per row of the mechanism table, asserting the rendered map equals the committed
    golden `.properties` file line for line. The fixtures carry fake credentials only
    (`golden-user` / `golden-secret`), so the JAAS line is written out unredacted and a diff is
    readable.
  - `everyMechanismHasAGoldenFile` — a `match` over `SaslMechanism` with no default case, so a
    mechanism added later fails to compile until its fixture exists.
- `ClientPropertyRendererSuite`:
  - `securityProtocolIsDerivedNotConfigured` — a table over the four protocols.
  - `overrideLayerWinsOverEveryRenderedKey` (property): for an arbitrary override map the
    rendered result agrees with the override on every shared key, **including**
    `sasl.jaas.config` and `security.protocol`. The escape hatch ADR-022 promises has to work
    even for the keys KUI computes itself, or it is not an escape hatch.
  - `hostnameVerificationOffRendersTheEmptyAlgorithm` and
    `hostnameVerificationOnRendersHttps` — both directions, explicitly.
  - `clientIdIsSetForEveryPurpose`.
  - `inlineNonPemStoreWithoutAMaterializedPathIsAnError` — the guard that stops an empty
    `ssl.truststore.location` reaching a client.
  - `pemStoreRendersInlineAndNeedsNoPath` — PEM carries its bytes in the property itself.
  - `noSecretAppearsInRenderOrRedactedValues` (property) — over a generated
    `ClusterConnection` with a distinctive token in every secret field.
  - `everyRenderedSensitiveKeyIsMarkedSensitive` — the rendered map's `Sensitive` key set
    equals the documented list, asserted as a set equality so an addition on either side fails.

## Observability

This module logs exactly once, at DEBUG, under the logger `kui.kafka.auth`: the **redacted**
property map it produced, with the `cluster` attribute set to `ClusterId.value`. It is the line
an operator needs when a connection fails for a reason the error does not explain, and it is
safe by construction, because `redactedValues` is the only rendering this module can reach —
there is no formatter here that could print the other one.

No metric. Rendering is a pure function on a code path that already has one: client creation is
measured in KAFKA-004.

## Degraded behavior

There is no upstream to degrade against. The failure this task owns is a *bad configuration*,
and the contract is: return `Left(NonEmptyList[ValidationError])`, never throw, never render a
half-built map. CFGOP-001 accumulates these with every other configuration error so that the
milestone's "unknown key, missing secret and invalid URL are reported together in one message"
criterion holds. An error from this module names the field and the mechanism and never contains
a password, a keystore's bytes or a rendered JAAS string.

## Docs to update

None in this task. `docs/operations/configuration.md` — including risk R-1's table column
recording which mechanisms are integration-tested and which are golden-file-tested only — is
written by CFGOP-008. Leave the evidence behind for it: the mechanism table above and the
committed golden fixtures are that evidence.

## Deviations

- **A password containing a line break or another C0 control character is refused, not
  rendered.** DEVPLAN §7's suite row asks for a round-trip property "for any password
  containing quotes, backslashes, spaces, newlines and `=`". Line breaks cannot be part of it:
  `sasl.jaas.config` is tokenized by `java.io.StreamTokenizer` with the double quote as its
  quote character, and that tokenizer terminates a quoted value at a line break — there is no
  escape sequence that carries one through. A renderer that emitted such a password would
  produce a string Kafka parses into something other than what the operator typed, which is the
  same class of defect as the injection this task exists to close, only quieter. So the
  property test covers quotes, backslashes, spaces, `=`, `;`, `,`, braces and unicode, and a
  separate test asserts that a control character produces a named `ValidationError` at startup.
  The operator-facing consequence is documented by CFGOP-008: such a password must be supplied
  through the `properties` override layer, where the operator owns the quoting.

### Further deviations, recorded by the implementer

2. **`LoginModules` gained a seventh constant, `GcpManagedKafkaCallbackHandler`.** The mechanism
   table's GCP row says "the handler library's own callback handler class" without naming it, and
   the renderer cannot emit a class it has no name for. It is
   `com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler`, from
   `managed-kafka-auth-login-handler`, recorded in the golden fixture.

3. **Azure Entra and GCP Managed Kafka render a login module with no options.** Both vendor handlers
   obtain the token themselves from the ambient credential — a managed identity, an application
   default credential — so there is nothing for the JAAS entry to carry. Rendering an option anyway
   would have been inventing a contract. `Jaas.module` therefore has to handle an empty option list,
   and it renders `<module> required;`.

4. **An inline PEM keystore is split into its key and its certificate blocks.** Kafka takes an
   inline PEM keystore as two properties — `ssl.keystore.key` for the private key and
   `ssl.keystore.certificate.chain` for the certificates — while every tool that produces one emits
   a single file containing both. The renderer splits on the PEM block markers and fails with a
   named error when either half is missing, rather than putting the whole bundle in both properties
   and letting the client fail with a parse error that names neither the cluster nor the field.

5. **`render` fills in a blank `clientId` from the purpose and the cluster id**
   (`kui-admin-<clusterId>`). The signature takes both a `purpose` and a `clientId`, and a caller
   with nothing better to say would otherwise pass the empty string, which makes Kafka generate an
   anonymous id and defeats the attribution the `ClientPurpose` parameter exists for.

6. **Three golden fixtures beyond the five the spec lists** — `properties-sasl-ssl-plain`,
   `properties-sasl-ssl-scram256`, `properties-ssl-only` and `properties-plaintext`. The spec's list
   omits the two mechanisms that *are* integration-tested and the two non-SASL protocols; a golden
   file for those is what makes a regression in the TLS block or in the "no SASL block at all" case
   a readable diff rather than an assertion buried in a suite.

7. **The fixtures are read from the test classpath, not from `test/resources/golden` as a path.**
   Mill runs a test in a sandbox working directory, so the relative path `kui.testkit.Golden` uses
   resolves to an empty sandbox. `libs/contracts-core`'s `GoldenFilesSuite` already reads its
   samples through `getResourceAsStream`, and this suite follows it.

8. **The `client.id` is set from `render`'s parameter rather than derived here**, and
   `ClientPurpose.prefix` is the vocabulary KAFKA-004 builds it from. `defaultsFor(purpose)` is an
   empty hook in M1: no non-security default is known yet, and the exhaustive `match` in it means a
   later task adding one cannot forget a purpose.

9. **`build.mill`'s `kafkaAuth` module object and the `kafkaClients` / `fs2Kafka` version entries
   were added by this task but landed in another agent's commit** (`aaa882f`, the STORE lane's
   file-adapter commit), which staged `build.mill` wholesale. The content is exactly what this spec
   dictates; it is recorded here so that a reader looking for the module's introduction finds it.
