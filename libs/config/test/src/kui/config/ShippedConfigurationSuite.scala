package kui.config

import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import scala.jdk.CollectionConverters.*

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

  /** The cursor and plan-token key the five service containers share (ADR-026, ADR-045). */
  private val cursorKey: Map[String, String] =
    Map("KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted")

  /** Every file, with the environment it expects and the URL policy the deployment it describes runs under.
    *
    * The environment entries are the `env:` references each file makes. Supplying them rather than leaving
    * them unset is deliberate: an unset secret is a *different* failure, and one that would be reported for
    * every file at once, hiding whatever else was wrong.
    */
  private val shipped: List[(String, UrlPolicy, Map[String, String])] = List(
    ("deployment/compose/kui.yaml", UrlPolicy.Dev, signingKey),
    ("deployment/compose/kui-cluster.yaml", UrlPolicy.Dev, signingKey),
    // THE FILE SIX OF THE EIGHT SERVICE CONTAINERS MOUNT, and it was not on this list for three
    // milestones. `docker-compose.yml` gives `kui-topic`, `kui-message`, `kui-consumer`,
    // `kui-schema`, `kui-metrics` and `kui-alerts` the same `--config /etc/kui/kui-service.yaml`,
    // so a mistake here stops six processes rather than one -- and nothing in this repository read
    // it. Wave 4
    // measured the cost: setting `callTimeout: "60s"` beside the default 30s `scrapeInterval` in
    // this file left `./mill libs.config.test` at 395/395 and `docker compose config -q` at exit
    // 0, because YAML is well-formed whatever it says, while the process refuses to boot with
    // "kui-metrics cannot start; the configuration has problems: ... callTimeout (60 seconds) must
    // be shorter than kui.metrics.scrapeInterval". `smoke.sh` would then fail four minutes later
    // on a stack that never came up, with a message about a capability document.
    ("deployment/compose/kui-service.yaml", UrlPolicy.Dev, signingKey ++ cursorKey),
    ("deployment/compose/kui-allinone.yaml", UrlPolicy.Dev, Map.empty),
    ("deployment/quickstart/kui-quickstart.yaml", UrlPolicy.Dev, Map.empty),
    // The quickstart's `--with-auth` configuration. No CI job runs `docker-compose.auth.yml`, so
    // before this row nothing in the repository read this file at all -- not a job, not a suite,
    // not a `docker compose config`. It carries its own `kui.auth`, its own `kui.rbac` and its own
    // copy of the `kui.metrics.sources` block, and a typo in any of the three was a demonstration
    // that failed in front of whoever was being shown it. The file itself now says so, beside the
    // section that says what still is not covered here: this row proves it loads and asserts
    // nothing about whether the two accounts can sign in.
    ("deployment/quickstart/kui-quickstart-auth.yaml", UrlPolicy.Dev, Map.empty),
    (
      "deployment/secured/kui-secured.yaml",
      UrlPolicy.Dev,
      signingKey ++ Map(
        "KUI_ANALYTICS_PASSWORD" -> "an-analytics-password",
        "KUI_ANALYTICS_TRUSTSTORE_PASSWORD" -> "a-truststore-password",
        "KUI_CURSOR_KEY" -> "a-cursor-key-long-enough-to-be-accepted"
      )
    ),
    (
      "deployment/demo/kui-demo.yaml",
      UrlPolicy.Dev,
      Map(
        "KUI_DEMO_SECURED_PASSWORD" -> "a-scram-password",
        "KUI_DEMO_TRUSTSTORE_PASSWORD" -> "a-truststore-password"
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

  /** Every YAML file under `deployment/` that is NOT one of KUI's own configuration files, with the reason.
    *
    * This is the other half of [[shipped]] and it is what makes the list above impossible to forget a row
    * from. `deployment/compose/kui-service.yaml` went unread for three milestones because somebody added the
    * file and nobody added the row, and no suite in this repository could tell: `shipped` is hand written, a
    * file that is absent from it is absent from every assertion made about it, and absence is invisible from
    * the only side anything looked at.
    *
    * So the two directions are reconciled against the filesystem below, the way
    * `scripts/feature-matrix-check.sh` reconciles its manifests. A file under `deployment/` is either a KUI
    * configuration -- in which case it is loaded through the real loader by a row in [[shipped]] -- or it is
    * one of these, and saying which costs a line and a sentence. Matching is on the file name rather than on
    * the whole path, because the question "is this KUI's configuration or somebody else's" is a question
    * about the file and not about which directory it happens to sit in.
    *
    * Widening one of these patterns is the way to make this reconciliation stop noticing anything, so the
    * case below also asserts that each one still matches something and that what is left over is exactly
    * [[shipped]]: a pattern broadened until it swallowed a KUI configuration file would take that file out of
    * the left-over set and fail on the missing row.
    */
  private val notKuiConfiguration: List[(String, String)] = List(
    "docker-compose" ->
      ("a Compose topology. It describes containers, images and mounts; every key in it would be " +
        "rejected by KUI's loader, which is the right outcome for a file that is not KUI's configuration."),
    "otel-collector.yaml" ->
      ("the OpenTelemetry Collector's own configuration, mounted by docker-compose.observability.yml. " +
        "KUI sends spans to that collector and never reads this file."),
    "kafka-jmx-exporter.yml" ->
      ("the Prometheus JMX exporter's ruleset (ADR-050). It is a contract with services/metrics's " +
        "reader and it is asserted, line shape by line shape, in deployment/compose/smoke.sh -- not here.")
  )

  /** Every `.yaml` and `.yml` file under `deployment/`, relative to the repository root. */
  private def deploymentYaml(root: Path): List[String] = {
    val stream = Files.walk(root.resolve("deployment"))
    try
      stream
        .filter(Files.isRegularFile(_))
        .map[String](path => root.relativize(path).toString.replace('\\', '/'))
        .filter(name => name.endsWith(".yaml") || name.endsWith(".yml"))
        .collect(Collectors.toList[String])
        .asScala
        .toList
        .sorted
    finally stream.close()
  }

  test("every configuration file this repository ships is on the list above, and every row is a file") {
    // THE DEFECT THIS CLOSES IS AN ABSENCE, WHICH IS WHY IT NEEDS THE FILESYSTEM. Every other case in this
    // file reads a row of `shipped` and asserts something about the file it names. None of them can say
    // anything at all about a file that has no row -- and that is the failure this suite was written for:
    // `kui-service.yaml` is mounted by five containers, carried a `callTimeout` the loader refuses, and was
    // checked by nothing for three milestones because it was missing from a hand-written list.
    val root = repositoryRoot
    val onDisk = deploymentYaml(root)

    // A pattern that matches nothing is a pattern somebody left behind after deleting the file it named, and
    // it is also the shape an over-broad replacement takes on the way in. Checked first, because the
    // partition below is meaningless if one side of it is stale.
    notKuiConfiguration.foreach { (pattern, reason) =>
      assert(
        onDisk.exists(_.contains(pattern)),
        s"no file under deployment/ matches `$pattern`, which is excluded from the shipped-configuration " +
          s"list with the reason: $reason. Delete the row, or restore the file it was written for."
      )
    }

    val excluded = onDisk.filter(name => notKuiConfiguration.exists((pattern, _) => name.contains(pattern)))
    val kuiConfiguration = onDisk.diff(excluded)
    val listed = shipped.map(_._1).sorted

    // Before either direction, because `diff` cannot see a repeat: a file listed twice loads twice, both
    // set differences stay empty, and the only visible effect is a case count that no longer matches the
    // number of files this suite is said to cover.
    assertEquals(
      listed.distinct,
      listed,
      clue = s"a file is listed twice above: ${listed.diff(listed.distinct).mkString(", ")}"
    )

    val unlisted = kuiConfiguration.diff(listed)
    assert(
      unlisted.isEmpty,
      s"these files are shipped under deployment/ and nothing loads them: ${unlisted.mkString(", ")}.\n" +
        "  Add a row to `shipped` above with the environment the file expects and the URL policy the " +
        "deployment it describes runs under, so that a key spelled wrongly fails here rather than in front " +
        "of whoever copied the example. If it is not a KUI configuration file at all, say so in " +
        "`notKuiConfiguration` with the reason."
    )

    val missing = listed.diff(kuiConfiguration)
    assert(
      missing.isEmpty,
      s"these rows name a file that is not a shipped configuration file: ${missing.mkString(", ")}.\n" +
        "  Either the file was deleted or renamed and the row was left behind, or it now matches one of " +
        "the `notKuiConfiguration` patterns, which would mean this suite has stopped loading it."
    )
  }

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
  private def repositoryRoot: Path = {
    val start = Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))
  }

  private def resolve(relative: String): Path = {
    val file = repositoryRoot.resolve(relative)
    if Files.exists(file) then file
    else fail(s"$relative is listed in this suite and does not exist; delete the row or restore the file")
  }
}
