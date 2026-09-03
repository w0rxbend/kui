# KAFKA-003 — `libs/kafka-auth`: keystore materialization and optional cloud handlers

- **ID:** KAFKA-003
- **Title:** `libs/kafka-auth`: keystore materialization and optional cloud handlers
- **Milestone / Feature:** M1 / CL-007, KU-011
- **Owner role:** Principal Scala Engineer, reviewed by the Security Engineer
- **Context / service:** `libs/kafka-auth`
- **Size:** M
- **Dependencies / blocked by:** KAFKA-002

## Goal (user value)

An operator can paste a keystore into the configuration instead of mounting a file, and KUI
still hands the Kafka client something the JVM's SSL machinery can open — written to a private
path that exists only while the client does. And when a deployment asks for AWS MSK IAM or
Azure Entra without the optional library on the classpath, KUI says exactly that, at startup,
naming the coordinate to add, instead of failing later with a `ClassNotFoundException` from
inside a Kafka login callback.

## Scope

1. `KeyStoreMaterializer` — a `Resource[F, Map[StoreRole, String]]` that writes
   `StoreSource.Inline` (non-PEM) stores to a private temporary file, hands back the paths
   `ClientPropertyRenderer.render` needs, and deletes them on release.
2. The PEM short circuit: a PEM store is rendered inline into `ssl.truststore.certificates` /
   `ssl.keystore.key` and never touches a filesystem at all.
3. `CloudHandlers` — a startup-time check that the login module and callback handler class
   names a mechanism needs are actually resolvable, producing a named, actionable error when
   they are not.
4. `ConnectionProperties.resource` — the one function every client factory calls: materialize,
   render, and give back a `ClientProperties` scoped to a `Resource`.

## Non-goals

- **No rendering rules.** The property names and the JAAS grammar are KAFKA-002's; this task
  only supplies the paths the renderer asked for and checks that classes exist.
- **No Kafka client construction** (KAFKA-004).
- **No new dependency on any cloud SDK.** The four coordinates stay optional and out of the
  build; they are documented so an operator can add them, and detected by name at runtime.
- No credential caching, no token refresh, no STS calls. Those are the login handlers' job,
  which is why KUI delegates to them rather than reimplementing them.

## Design references

ADR-022 ("keystores and truststores are `Secret[Bytes]` inline in the profile (or a path in
single-process mode); adapters materialize them to a private tmpfs path when needed"; "cloud
handlers are optional runtime modules selected by config"), `ARCHITECTURE.md` §14 (security
boundaries), DEVPLAN §8 risk R-1, ADR-034 (`ApplicationError.Unsupported` is the shape of "this
deployment did not install that"), Apache Kafka documentation for the PEM store types
(`ssl.truststore.type=PEM`).

## Files to create

```
libs/kafka-auth/src/kui/kafka/auth/KeyStoreMaterializer.scala
libs/kafka-auth/src/kui/kafka/auth/CloudHandlers.scala
libs/kafka-auth/src/kui/kafka/auth/ConnectionProperties.scala
libs/kafka-auth/test/src/kui/kafka/auth/KeyStoreMaterializerSuite.scala
libs/kafka-auth/test/src/kui/kafka/auth/CloudHandlersSuite.scala
libs/kafka-auth/test/src/kui/kafka/auth/ConnectionPropertiesSuite.scala
```

## Files to change

None. `libs/kafka-auth`'s module object already declares `fs2-io` (KAFKA-002).

## Public Scala signatures to implement

```scala
package kui.kafka.auth

import cats.effect.{Async, Resource}
import fs2.io.file.{Files, Path}
import kui.kernel.cluster.*
import kui.kafka.auth.ClientPropertyRenderer.StoreRole

/** Writes inline keystores to disk for exactly as long as a client needs them.
  *
  * Kafka's SSL engine takes a *path* for a JKS or PKCS12 store; there is no property that
  * carries those bytes. So a store an operator pasted into configuration has to become a file
  * somewhere, and the only two questions worth arguing about are where and for how long. The
  * answers here: a directory the process owner alone can read, and only while the `Resource`
  * is open.
  */
object KeyStoreMaterializer {

  /** Materializes whichever of the connection's stores need a path, and returns the paths for
    * `ClientPropertyRenderer.render`'s `materialized` parameter.
    *
    * The result is empty when nothing needs a file, which is the common case: a
    * `StoreSource.FromPath` already has one, a PEM store carries its bytes in a property, and
    * `TlsConfig.default` has neither store.
    *
    * The directory is created with POSIX permissions `rwx------` and each file with `rw-------`
    * on a filesystem that supports them; on one that does not, materialization fails with a
    * named error rather than writing a world-readable private key. Files are overwritten with
    * zero bytes before deletion on release, then deleted.
    */
  def resource[F[_]: Async: Files](
      connection: ClusterConnection,
      baseDirectory: Option[Path] = None    // default: the JVM temp directory
  ): Resource[F, Map[StoreRole, String]]

  /** The directory name under the base directory: `kui-kafka-auth-<clusterId>-<random>`. The
    * cluster id is in the name so an operator inspecting a running container can tell which
    * cluster a stray directory belonged to. */
  def directoryName(id: ClusterId, random: String): String
}
```

```scala
package kui.kafka.auth

import cats.effect.Sync
import kui.kernel.KuiError
import kui.kernel.cluster.SaslMechanism

/** Are the classes this mechanism needs actually on the classpath?
  *
  * ADR-022 keeps the cloud SDKs off the default classpath because they are large and most
  * deployments need none of them. The cost of that decision is a failure mode — a mechanism
  * configured without its library — and this object is where that cost is paid: at startup,
  * once, with a message naming the coordinate to add.
  */
object CloudHandlers {

  /** The class names a mechanism needs at runtime: its login module and, where it has one, its
    * callback handler. */
  def requiredClasses(mechanism: SaslMechanism): List[String]

  /** The Maven coordinate an operator must add to make `mechanism` work, if any. */
  def requiredCoordinate(mechanism: SaslMechanism): Option[String]

  /** `Right(())` when every required class resolves through the current class loader.
    * `Left(ApplicationError.Unsupported(...))` otherwise, with a message of the form:
    *
    * "Cluster 'prod' is configured for AWS_MSK_IAM, but the login module
    *  software.amazon.msk.auth.iam.IAMLoginModule is not on the classpath. Add
    *  software.amazon.msk:aws-msk-iam-auth:2.3.7 to the deployment image, or see
    *  docs/operations/configuration.md."
    *
    * It uses `Class.forName(name, false, loader)` — `initialize = false`, so the check costs
    * nothing and cannot run a static initializer that talks to a metadata service.
    */
  def check[F[_]: Sync](id: ClusterId, mechanism: SaslMechanism): F[Either[KuiError, Unit]]
}
```

```scala
package kui.kafka.auth

import cats.effect.{Async, Resource}
import fs2.io.file.Files
import kui.kernel.KuiError
import kui.kernel.cluster.{ClientProperties, ClusterConnection}

/** Materialize, check, render — the one entry point every client factory in `libs/kafka` uses.
  *
  * It is a `Resource` because the properties are only valid while the materialized files
  * exist: a `ClientProperties` value that outlived its truststore would name a path that is no
  * longer there, and the resulting SSL error names the file rather than the mistake.
  */
object ConnectionProperties {
  def resource[F[_]: Async: Files](
      connection: ClusterConnection,
      purpose: ClientPurpose,
      clientId: String
  ): Resource[F, Either[KuiError, ClientProperties]]
}
```

The coordinates `requiredCoordinate` reports, from `DEPENDENCY_MATRIX.md` lines 122–125:

| Mechanism | Coordinate |
| --- | --- |
| `AwsMskIam` | `software.amazon.msk:aws-msk-iam-auth:2.3.7` |
| `AzureEntra` | none required — the generic `OAuthBearerLoginCallbackHandler` ships with `kafka-clients`; `com.azure:azure-identity:1.18.6` is optional and only needed for managed-identity flows |
| `GcpManagedKafka` | `com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler:1.0.6` plus `com.google.oauth-client:google-oauth-client:1.39.0` |
| `Gssapi` | none — `Krb5LoginModule` ships with the JDK |
| everything else | none |

## ADRs this task must obey

ADR-022 (inline stores materialized to a private path; cloud handlers optional and selected by
configuration), ADR-034 (a missing optional library is `ApplicationError.Unsupported`, which
ADR-039 §6 maps to `NotConfigured` rather than dimming a capability as if something had
broken), ADR-041 A10, `ARCHITECTURE.md` §14.

## Library coordinates

None new on the compile classpath — that is the point of the task.
`co.fs2::fs2-io::3.13.0` and `org.typelevel::cats-effect::3.7.1` are already declared by
KAFKA-002. Test scope adds nothing beyond what `libs.testkit` already brings; the certificate
helpers the suite needs are `libs/testkit`'s BouncyCastle-backed generators
(`org.bouncycastle:bcpkix-jdk18on:1.85`, already declared there).

## Acceptance criteria

```
$ ./mill libs.kafkaAuth.test
$ ./mill libs.kafkaAuth.compile      # clean under -Werror
```

Behaviour a reviewer can check by hand:

```scala
// A JKS truststore pasted into configuration becomes a real, openable file...
val conn = connectionWithInlineJks(base64Of(generatedTrustStore))
ConnectionProperties.resource[IO](conn, ClientPurpose.Admin, "kui-admin-x-1").use { props =>
  IO {
    val path = props.toOption.get.get("ssl.truststore.location").get.unsafeValue
    val ks   = java.security.KeyStore.getInstance("JKS")
    ks.load(new java.io.FileInputStream(path), "changeit".toCharArray)   // does not throw
    assertEquals(java.nio.file.Files.getPosixFilePermissions(java.nio.file.Path.of(path)).toString, "[OWNER_READ, OWNER_WRITE]")
    path
  }
}.flatMap(path => IO(assert(!java.nio.file.Files.exists(java.nio.file.Path.of(path)))))
// ...and is gone once the Resource closes.
```

```
$ grep -rn "aws-msk-iam-auth\|azure-identity\|managed-kafka-auth" build.mill
# no output: the optional coordinates are documented, never on the default classpath
```

## Tests required

- `KeyStoreMaterializerSuite` (unit, `munit-cats-effect`, real filesystem):
  - `inlineJksBecomesALoadableKeyStore` — generate a store with the `libs/testkit` certificate
    helper, materialize it, open it with `java.security.KeyStore`.
  - `inlinePkcs12BecomesALoadableKeyStore`.
  - `pemNeedsNoFile` — a PEM truststore produces an empty `materialized` map and a rendered
    `ssl.truststore.certificates` property.
  - `fromPathIsPassedThroughUnchanged` — KUI never copies a file the operator already mounted.
  - `filesAreOwnerOnly` — directory `rwx------`, file `rw-------`.
  - `filesAreDeletedOnRelease`, and `filesAreDeletedWhenTheBodyFails` (the `Resource`
    finalizer runs on the error path too).
  - `contentIsZeroedBeforeDeletion` — assert on a materializer given a base directory the test
    controls, by reading the file in a finalizer hook.
  - `noSecretAppearsInTheErrorWhenTheBase64IsInvalid` — a malformed base64 store yields a
    `ValidationError` naming the field, never the value.
- `CloudHandlersSuite` (unit):
  - `requiredClassesTable` — a `match` over `SaslMechanism` with no default case, so a new
    mechanism cannot be added without deciding what it needs.
  - `plainAndScramNeedNothingBeyondKafkaClients`.
  - `missingClassProducesAnActionableError` — assert the message contains the mechanism name,
    the class name and, where one exists, the Maven coordinate. Drive it with a
    `CloudHandlers.check` variant that takes a class loader, so the test can supply one that
    resolves nothing rather than depending on what happens to be on the test classpath.
  - `checkDoesNotInitializeTheClass` — probe with a class whose static initializer would throw,
    and assert `check` still succeeds.
- `ConnectionPropertiesSuite` (unit, `munit-cats-effect`):
  - `materializedPathsReachTheRenderedProperties`.
  - `aMissingCloudHandlerFailsBeforeAnyFileIsWritten` — the classpath check runs first, so a
    misconfigured deployment does not leave a keystore on disk.
  - `propertiesAreInvalidatedWithTheResource` — a regression guard: the file named by the
    returned properties does not exist after `use` returns.

## Observability

One INFO line per materialization, under `kui.kafka.auth`, with attributes `cluster` and
`store` (`truststore` / `keystore`): "materialized <store> for cluster <id> at <path>". The
path is safe to log — it is a directory KUI created — and it is the first thing an operator
needs when a TLS handshake fails. Never log the store's bytes, its password, or the base64.

One WARN line, once per cluster at startup, when `verifyHostname` is false: "hostname
verification is disabled for cluster <id>". Turning off certificate hostname checking is a
decision an operator should see in the log of every process that made it.

No metric — this happens once per client, on a path already measured by KAFKA-004.

## Degraded behavior

- **Missing optional library:** `ApplicationError.Unsupported`, surfaced at startup by the
  cluster service's wiring. Per ADR-039 §6 this is `NotConfigured`, not a dimmed capability:
  the deployment did not install something, nothing is broken.
- **Unwritable temp directory** (read-only container filesystem, no `/tmp`):
  `InfrastructureError.Unreachable("keystore-materializer", cause)` with a message naming the
  directory it tried. The operator's fix is a writable `emptyDir`/tmpfs mount, and
  CFGOP-008 documents it.
- **Filesystem without POSIX permissions:** fail, do not fall back. Writing a private key to a
  path other processes can read is not a degraded mode, it is a different product.
- The materializer never retries and never caches across clusters: two clusters with the same
  keystore bytes get two directories, because sharing one would mean the first cluster's
  shutdown could delete the second cluster's truststore.

## Docs to update

None here. CFGOP-008 writes the `docs/operations/configuration.md` sections for inline stores,
the tmpfs recommendation and the optional-coordinate table; the tables in this file are the
source it works from.

## Deviations

*(filled in by the implementer, in the same commit)*
