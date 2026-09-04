package kui.config

import java.nio.file.{Files, Path}

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.testkit.KuiSuite

/** That every configuration file this repository ships actually loads.
  *
  * The file that prompted this suite is `deployment/quickstart/kui-quickstart.yaml`. It carried a cluster
  * written with invented field names for months, and nothing noticed, because the loader tolerated anything
  * under `kui.clusters` and no test ever read the file. The quickstart is the first thing a newcomer runs; a
  * broken example there is worse than no example.
  *
  * The rule this enforces is narrow and worth stating: a configuration file committed to this repository is
  * an example somebody will copy, so it has to be one the shipped loader accepts. Nothing here asserts what
  * the values *mean* — that is each file's own business — only that KUI would start with them.
  */
final class ShippedConfigurationSuite extends KuiSuite {

  /** The inter-service signing key every distributed-shape file expects (ADR-005). */
  private val signingKey: Map[String, String] =
    Map("KUI_PRINCIPAL_KEY" -> "a-signing-key-long-enough-to-be-accepted")

  /** Every file, with the environment it expects and the URL policy the deployment it describes runs under.
    *
    * The environment entries are the `env:` references each file makes. Supplying them rather than leaving
    * them unset is deliberate: an unset secret is a *different* failure, and one that would be reported for
    * every file at once, hiding whatever else was wrong.
    */
  private val shipped: List[(String, UrlPolicy, Map[String, String])] = List(
    ("deployment/compose/kui.yaml", UrlPolicy.Dev, signingKey),
    ("deployment/compose/kui-cluster.yaml", UrlPolicy.Dev, signingKey),
    ("deployment/compose/kui-allinone.yaml", UrlPolicy.Dev, Map.empty),
    ("deployment/quickstart/kui-quickstart.yaml", UrlPolicy.Dev, Map.empty),
    (
      "deployment/secured/kui-secured.yaml",
      UrlPolicy.Dev,
      signingKey ++ Map(
        "KUI_ANALYTICS_PASSWORD" -> "an-analytics-password",
        "KUI_ANALYTICS_TRUSTSTORE_PASSWORD" -> "a-truststore-password",
        "KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted"
      )
    ),
    ("deployment/examples/minimal.yaml", UrlPolicy.Strict, Map.empty),
    (
      "deployment/examples/three-clusters.yaml",
      UrlPolicy.Strict,
      Map(
        "KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted",
        "KUI_SECURED_PASSWORD" -> "a-scram-password",
        "KUI_SECURED_TRUSTSTORE_PASSWORD" -> "a-truststore-password"
      )
    ),
    (
      "deployment/examples/production.yaml",
      UrlPolicy.Strict,
      signingKey ++ Map(
        "KUI_ANALYTICS_PASSWORD" -> "an-analytics-password",
        "KUI_ANALYTICS_TRUSTSTORE_PASSWORD" -> "a-truststore-password",
        "KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted",
        "KUI_STORE_ENCRYPTION_KEY" -> "a-store-encryption-key",
        "KUI_STORE_PASSWORD" -> "a-store-password"
      )
    )
  )

  shipped.foreach { (relative, policy, environment) =>
    test(s"$relative loads") {
      val file = resolve(relative)

      KuiConfigSource.loadFrom[IO](Nil, List(file), environment, policy).unsafeRunSync() match {
        case Right(_) => ()
        case Left(errors) => fail(s"$relative does not load:\n${errors.render}")
      }
    }
  }

  test("the quickstart describes the broker the quickstart starts") {
    val loaded = KuiConfigSource
      .loadFrom[IO](Nil, List(resolve("deployment/quickstart/kui-quickstart.yaml")), Map.empty, UrlPolicy.Dev)
      .unsafeRunSync()
      .fold(errors => fail(errors.render), identity)

    // `quickstart.sh` starts one broker under the Compose service name `kafka`, and the promise made in
    // that script's own output is that the dashboard shows a cluster with nothing else to type. If this
    // entry stops naming that broker, the promise is broken and nothing else would say so.
    assertEquals(loaded.clusters.map(_.id.value), List("quickstart"))
    assertEquals(loaded.clusters.head.bootstrapServers.value, "kafka:9092")
  }

  test("the production example's secrets are all resolved and none is left as its own reference") {
    // "It loads" was not enough, and this is the case that proves it. `deployment/examples/production.yaml`
    // writes the truststore password as `env:KUI_ANALYTICS_TRUSTSTORE_PASSWORD`, and the loader used to
    // accept that string *as the password* — no error, a file that loaded, and a secured cluster that then
    // sat on the dashboard reading "unavailable" for ever, because `Admin.create` could not open the store
    // with a password that was the name of an environment variable.
    //
    // So the assertion is over the resolved values: no secret in the file may still look like the reference
    // that was written in it.
    val environment = signingKey ++ Map(
      "KUI_ANALYTICS_PASSWORD" -> "an-analytics-password",
      "KUI_ANALYTICS_TRUSTSTORE_PASSWORD" -> "a-truststore-password",
      "KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted",
      "KUI_STORE_ENCRYPTION_KEY" -> "a-store-encryption-key",
      "KUI_STORE_PASSWORD" -> "a-store-password"
    )

    val loaded = KuiConfigSource
      .loadFrom[IO](Nil, List(resolve("deployment/examples/production.yaml")), environment, UrlPolicy.Strict)
      .unsafeRunSync()
      .fold(errors => fail(errors.render), identity)

    val analytics = loaded.clusters
      .find(_.id.value == "analytics")
      .getOrElse(fail(s"the example no longer has an `analytics` cluster: ${loaded.clusters.map(_.id.value)}"))

    analytics.security match {
      case kui.kernel.cluster.ClusterSecurity.Sasl(_, mechanism, Some(tls)) =>
        assertEquals(
          tls.truststore.flatMap(_.password).map(_.value),
          Some("a-truststore-password"),
          clue = "the truststore password was not resolved; the env: reference reached the Kafka client"
        )
        mechanism match {
          case kui.kernel.cluster.SaslMechanism.ScramSha512(_, password) =>
            assertEquals(password.value, "an-analytics-password")
          case other => fail(s"the example no longer uses SCRAM-SHA-512: $other")
        }
      case other => fail(s"the example's analytics cluster is no longer SASL over TLS: $other")
    }
  }

  /** The repository root, found by walking up to the directory holding `build.mill`.
    *
    * A test runs in a sandbox directory, so a relative path means nothing. Walking up to a file that only
    * the root has is more robust than any number of `../`, and it fails with a sentence rather than a
    * `NoSuchFileException` when it is wrong.
    */
  private def resolve(relative: String): Path = {
    val start = Path.of("").toAbsolutePath
    val root = Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))

    val file = root.resolve(relative)
    if Files.exists(file) then file
    else fail(s"$relative is listed in this suite and does not exist; delete the row or restore the file")
  }
}
