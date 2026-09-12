package kui.config

import java.nio.file.{Files, Path}
import java.util.stream.Collectors

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.TopicName
import kui.kernel.serde.{PayloadKind, Target}
import kui.security.masking.{KeepEnds, MaskingEngine, MaskingKind, MaskingRule}
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
    * [[shipped]].
    *
    * THAT SENTENCE USED TO CONTINUE *"a pattern broadened until it swallowed a KUI configuration file would
    * take that file out of the left-over set and fail on the missing row"*, AND IT WAS FALSE FOR THE ONE
    * WIDENING ANYBODY WOULD MAKE. Measured on this tree before the case below existed: replacing
    * `"kafka-jmx-exporter.yml"` with `".yml"` left `./mill libs.config.test` at 395/395 with this suite 14/14
    * green. Nothing on disk needed the widened pattern to stay honest -- every row of [[shipped]] is a
    * `*.yaml`, a `.yaml` name does not contain the text `.yml`, and every `docker-compose*.yml` was already
    * excluded by the row above -- so the partition did not move at all and the "still matches something"
    * check passed on the Compose files. The reconciliation was inert for exactly the class of file it was
    * written to notice: the next `*.yml` KUI configuration anybody ships.
    *
    * So the widening is checked against files that do not exist yet rather than against the ones that do, by
    * [[excludedBy]] and the probe assertion in the case below.
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

  /** The [[notKuiConfiguration]] row that claims a file, if one does.
    *
    * Extracted so that the partition below and the probe beside it cannot ask the question two different
    * ways. A probe with its own copy of `contains` would keep passing after somebody changed the real matcher
    * to a suffix test or a `Path.getFileName` comparison, which is the failure this whole suite is about: two
    * hand-written halves that agree by coincidence until one of them moves.
    */
  private def excludedBy(name: String): Option[(String, String)] =
    notKuiConfiguration.find((pattern, _) => name.contains(pattern))

  /** The names a widened exclusion pattern would swallow: every file KUI's next configuration plausibly is.
    *
    * On-disk names cannot carry this assertion and that is the whole point: every file the reconciliation can
    * see today is a `*.yaml` in a directory that already holds one, so a pattern widened past them moves
    * nothing and both directions of the set difference stay empty. The rule is about the files that are not
    * here, and it is derived from what IS here so that nothing has to be remembered twice.
    *
    * ==Two families, and the second one is why this is a `def` over the filesystem==
    *
    * **The extension twins.** `shipped` is the roster of files the loader must accept, so its `.yml` twins
    * are the eleventh row written `.yml`, which half the YAML in this repository already is.
    *
    * **A KUI configuration in each directory `deployment/` already has.** The twins above are all named after
    * files that exist, in directories that already hold a `shipped` row, so a pattern widened along the
    * *directory* rather than the extension is invisible to them -- and `deployment/metrics/`,
    * `deployment/frontend/` and `deployment/storybook/` hold no `shipped` row at all, which makes them the
    * cheapest place for a widening to hide. Measured on this tree: replacing `"kafka-jmx-exporter.yml"` with
    * `"metrics/"` leaves the reconciliation above completely green -- the pattern still matches the exporter,
    * no `shipped` row is under that path, and the left-over set does not move -- while silently excluding the
    * `deployment/metrics/kui.yaml` somebody writes next. With this family present that widening reddens the
    * case below and nothing else, which is the only form of evidence this suite accepts about itself.
    *
    * ==What is deliberately NOT probed, and why it is not a hole==
    *
    * An extension `deployment/` does not use at all -- `.conf`, `.properties` -- needs no probe: the first
    * case above asserts that every pattern still matches something on disk, and a pattern matching no `.yaml`
    * or `.yml` fails there before this case is reached. Probing it would add an assertion that cannot fail,
    * which is the thing this suite exists to refuse.
    */
  private def widenedExclusionProbes(root: Path): List[String] = {
    val extensionTwins = shipped.map((relative, _, _) => relative.stripSuffix(".yaml") + ".yml")

    // `kui.yaml` and not a generated name: it is what `deployment/compose/` actually calls KUI's own
    // configuration, so it is the name the next directory's will most plausibly take, and it keeps the
    // probe a fact about this repository rather than an invention.
    val inEveryDirectory = deploymentYaml(root)
      .map(name => name.substring(0, name.lastIndexOf('/')))
      .distinct
      .flatMap(directory => List(s"$directory/kui.yaml", s"$directory/kui.yml"))

    (extensionTwins ++ inEveryDirectory).distinct.sorted
  }

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

    val excluded = onDisk.filter(name => excludedBy(name).isDefined)
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

  test("no exclusion pattern is broad enough to swallow a KUI configuration file that is not here yet") {
    // THE OTHER DIRECTION OF THE PATTERN QUESTION, AND THE ONE THE WIDENING DEFEATS. The case above asks
    // whether each pattern still matches a file that is on disk; a widened pattern passes that more easily
    // than a narrow one, because it matches more. This asks whether it matches a file that is NOT on disk
    // and would be KUI's own if somebody wrote it tomorrow -- which is the only direction in which `".yml"`
    // and `"kafka-jmx-exporter.yml"` differ, because every KUI configuration this repository has today is a
    // `*.yaml` and no `.yaml` name contains the text `.yml`.
    //
    // Measured before this case existed: the widening left `./mill libs.config.test` at 395/395 with this
    // suite 14/14 green. The reconciliation could not fail for the class of file it was written to notice.
    //
    // The probe list is wider than the `.yml` twins it started as, and the second family in its comment is
    // the reason: a pattern can be widened along the directory as easily as along the extension, and three
    // directories under `deployment/` hold no `shipped` row for a twin to be derived from.
    widenedExclusionProbes(repositoryRoot).foreach { probe =>
      excludedBy(probe) match {
        case None => ()
        case Some((pattern, reason)) =>
          fail(
            s"`$pattern` would exclude `$probe` from the shipped-configuration reconciliation, and that " +
              "name is a KUI configuration file: it is a row of `shipped` with a .yml extension.\n" +
              s"  The row's stated reason is: $reason\n" +
              "  A pattern this broad takes the next *.yml KUI configuration out of the left-over set in " +
              "silence, so nothing would load it and nothing would say so -- which is the single failure " +
              "this reconciliation exists to prevent. Name the file the row was written for rather than " +
              "its extension."
          )
      }
    }
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

  /** The two shipped quickstart configurations, which mask the same two fields of `customers.profiles`.
    *
    * `--with-auth` is the deployment with a **viewer** role, and until wave 11 it was the one with no masking
    * at all: its header claimed to be `kui-quickstart.yaml` "with nothing else changed" while
    * `grep -c masking` was 0 against 6. W11-03 landed the block; this suite is what stops the pair from
    * separating again, and it is why the case below drives a list instead of one path.
    */
  /** A masking rule as a value that can be compared.
    *
    * `MaskingRule` holds `scala.util.matching.Regex`, which has reference equality, so two rules parsed from
    * two files are never equal however identical the YAML is. The patterns are compared as the text an
    * operator wrote, which is also what a failure has to print to be worth reading.
    */
  private def describe(
      rules: List[MaskingRule]
  ): List[(MaskingKind, Option[List[String]], Option[String], Option[String], Option[String])] =
    rules.map { rule =>
      // THE PROJECTION IS HAND-WRITTEN, SO IT CANNOT NOTICE A FIELD IT WAS NEVER TOLD ABOUT. A sixth
      // field on `MaskingRule` with a default value compiles at every call site in this repository,
      // is silently dropped from the five-tuple below, and the two shipped quickstarts could then
      // disagree on it with this suite green -- which is the whole point of the cross-file comparison
      // this feeds. Filed as W11-05/V-3. `productArity` is the one thing that moves when a field is
      // added, so it is asserted beside the projection rather than left to a reader to notice.
      assertEquals(
        rule.productArity,
        5,
        clue = "MaskingRule has gained or lost a field and `describe` still projects five of them, so " +
          "the two shipped quickstarts are being compared on a subset of what a rule now holds"
      )
      (
        rule.kind,
        rule.fields.map(_.toList),
        rule.fieldsNamePattern.map(_.regex),
        rule.topicKeysPattern.map(_.regex),
        rule.topicValuesPattern.map(_.regex)
      )
    }

  private val quickstarts: List[String] = List(
    "deployment/quickstart/kui-quickstart.yaml",
    "deployment/quickstart/kui-quickstart-auth.yaml"
  )

  test(
    "both quickstarts ship the masking rules their comments describe, and neither second profile has any"
  ) {
    // DM-001/ADR-023 AS A DEPLOYMENT AND NOT ONLY AS A TYPE. Wave 9 wired the masking engine into the
    // message service and left it unreachable: no file in this repository configured a rule, so the
    // feature could be read about and not looked at. This case is the other end of that repair -- the
    // quickstart now masks two fields of `customers.profiles`, and this asserts the rules a person is
    // told they will see.
    //
    // IT IS ALSO THE REACHABILITY ASSERTION THE OVERLAY USED TO MAKE. `kui.clusters.*.masking.*` has to
    // be in `KuiConfigSource.UnknownKeys.Known` or every one of these keys is refused at start-up as "is
    // not a KUI configuration key". The overlay this replaces wrote three of those keys into a second
    // document over the quickstart; the shipped file now writes five of them itself, which is a stronger
    // statement of the same fact: deleting a `Known` entry stops the quickstart booting.
    // W11-03 -> W11-05, THE ONE DECLARED EDGE OF THIS WAVE. This case used to read one path and assert
    // that every OTHER shipped file masked nothing -- which was true, and was the defect: the
    // `--with-auth` deployment is the one with a viewer role, and it was the one with no masking, under
    // a header claiming it was the default file "with nothing else changed". The guard was pinning the
    // hole in place. It now reads both, requires them to agree, and keeps the "nobody else" half.
    val loadedQuickstarts = quickstarts.map { relative =>
      relative -> KuiConfigSource
        .loadFrom[IO](Nil, List(resolve(relative)), Map.empty, UrlPolicy.Dev)
        .unsafeRunSync()
        .fold(errors => fail(s"$relative does not load:\n${errors.render}"), identity)
    }

    loadedQuickstarts.foreach { (relative, loaded) =>
      val rules = loaded.clusters.head.masking.rules
      assertEquals(rules.size, 2, clue = s"$relative's masking rules: ${rules.map(_.kind)}")
      assertEquals(
        rules.map(_.kind),
        List(
          MaskingKind.Mask(MaskingConfig.DefaultReplacementChars, KeepEnds(0, 4)),
          MaskingKind.Replace("<redacted>")
        ),
        clue = relative
      )
      assertEquals(
        rules.map(_.fields.map(_.toList)),
        List(Some(List("email")), Some(List("name"))),
        clue = relative
      )
      assertEquals(
        rules.map(_.topicValuesPattern.map(_.regex)),
        List(Some("customers\\.profiles"), Some("customers\\.profiles")),
        clue = relative
      )

      // AS THE ENGINE READS IT, over a record shaped like the ones `seed/data/customers.profiles`
      // writes. A case that stopped at the parse would prove the loader agrees with itself, and the
      // promise made in the file's own comment is about what the message browser draws.
      assertEquals(
        MaskingEngine
          .maskPayload(
            rules,
            TopicName.unsafe("customers.profiles"),
            Target.Value,
            PayloadKind.Json,
            """{"customerId":"CUST-8812","name":"Marta Zielinska","email":"marta.zielinska@example.com"}"""
          ),
        """{"customerId":"CUST-8812","name":"<redacted>","email":"***********************.com"}""",
        clue = relative
      )

      // AND EVERY PROFILE AFTER THE FIRST CONFIGURES NONE ON PURPOSE. The default quickstart's
      // `staging-eu-01` points at the same `kafka:9092` as the cluster above, so the same records read
      // through it come back in full: a masking rule protects the registered profile it is written on
      // and not the broker underneath it. That is the property `docs/operations/masking.md` warns
      // about, and this is where it is true; copying the block onto the second profile would take the
      // demonstration away. Written over `drop(1)` rather than over `clusters(1)` because the
      // `--with-auth` file registers one profile and not two -- which is one of the three omissions its
      // own header lists -- and an index would throw there instead of asserting anything.
      assert(
        loaded.clusters.drop(1).forall(_.masking.isEmpty),
        s"$relative masks on a profile after the first, which takes the demonstration away"
      )
    }

    // AND THE TWO AGREE, ASSERTED DIRECTLY RATHER THAN INFERRED FROM TWO PASSING LOOPS. A reader
    // comparing the files by eye is what let them drift for a wave; a rule added to one and not the
    // other is what this line is for, and it does not need the roster above to be exhaustive.
    //
    // Over `describe` and not over the rules themselves, because `MaskingRule` carries `Regex`, and
    // `Regex` does not implement value equality -- two identical patterns parsed from two files are
    // never `==`, so a direct comparison would fail on files that agree and would look like a finding.
    val (defaultFile, defaultConfig) = loadedQuickstarts.head
    val (authFile, authConfig) = loadedQuickstarts(1)

    assertEquals(
      describe(authConfig.clusters.head.masking.rules),
      describe(defaultConfig.clusters.head.masking.rules),
      s"$authFile and $defaultFile mask different things, and the one with a viewer role is the one a " +
        "reader is least likely to check"
    )

    // Every other shipped file is unchanged by the section existing, which is the standing rule for
    // every configuration section this project has added: an existing YAML still boots unchanged.
    val others = shipped.filterNot((relative, _, _) => quickstarts.contains(relative))
    others.foreach { (relative, policy, environment) =>
      val configured = KuiConfigSource
        .loadFrom[IO](Nil, List(resolve(relative)), environment, policy)
        .unsafeRunSync()
        .fold(errors => fail(s"$relative does not load:\n${errors.render}"), identity)

      assert(
        configured.clusters.forall(_.masking.isEmpty),
        clue = s"$relative acquired a masking rule and nothing says why"
      )
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
    //
    // TWO REGISTERED PROFILES OVER THAT ONE BROKER, since wave 9. `staging-eu-01` is the second, and it
    // is what gives the cluster selector something to switch to: the design's own capture spells the
    // toast "Switched to staging-eu-01" (SCREENS-V4 §1, M08) and no browser case had ever opened that
    // menu, because a one-entry menu has nothing to choose. Both entries point at `kafka:9092` on
    // purpose — the quickstart runs one Kafka, and what the screens need is a second *profile*.
    //
    // The order is asserted rather than the set: the first entry is the one the shell lands on, and a
    // newcomer who sees `staging-eu-01` first is looking at the cluster with no metrics source, no
    // registry, no Connect and no ksqlDB — which is the emptiest possible first screen of this product.
    assertEquals(loaded.clusters.map(_.id.value), List("quickstart", "staging-eu-01"))
    assertEquals(loaded.clusters.map(_.bootstrapServers.value).distinct, List("kafka:9092"))

    // THE DISPLAY NAME, WHICH IS THE WORD THE DESIGN ASKS A BROWSER TO READ. The id and the broker were
    // asserted above and the name was not, so `- name: "Staging (EU)"` was a one-line edit with every
    // Scala gate green -- and `frontend/e2e/shell.spec.ts` deliberately discovers the second cluster's
    // name off `/api/v1/clusters` rather than writing it down, which is right for the browser and leaves
    // the word itself unasserted by anything in the tree. M08's capture spells the toast "Switched to
    // staging-eu-01" (SCREENS-V4 §1), so the name and the id agreeing is the product's promise and not a
    // coincidence of this file. Filed as W9-01/V1.
    assertEquals(loaded.clusters.map(_.name), List("Quickstart (local)", "staging-eu-01"))

    // AND THE ABSENCE THAT THE ENTRY EXISTS FOR. The comment above `staging-eu-01` in the YAML argues at
    // length that it must have no `kui.metrics.sources` member, because `traffic.spec.ts`'s "says the
    // same thing about a cluster nobody configured a source for" needs a registered cluster whose
    // throughput answers `not_configured`. Four lines of YAML take that subject away again; this is the
    // assertion that notices. Filed as W9-01/V2.
    assertEquals(loaded.metrics.sources.keys.map(_.value).toList, List("quickstart"))

    // AND THE THRESHOLD THE BROWSER SUITE'S THIRD SKIP TURNS ON. `frontend/e2e/shell.spec.ts`'s
    // acknowledgement case -- SCREENS-V4 M06's path, the one screen that was covered by half a case for
    // three waves -- is a `test.skip` unless the alerts feed has an open event, and the only reason a
    // freshly booted quickstart has one is this literal. `80` is the default and the value this file
    // shipped until wave 11; restoring it is a one-character edit that takes the suite back to 107 passed
    // / 3 skipped and exit 0, with no gate anywhere in the repository disagreeing -- measured, and filed
    // as W11-02/F-1. The YAML argues for the value at length forty lines above; this is the assertion that
    // holds it there.
    assertEquals(
      loaded.alerts.thresholds.diskUsedWarningPercent,
      1,
      clue = "the quickstart no longer seeds a storage warning on first boot, so shell.spec.ts's " +
        "acknowledgement case goes back to being a skip and the browser suite silently loses a case"
    )
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
      .getOrElse(
        fail(s"the example no longer has an `analytics` cluster: ${loaded.clusters.map(_.id.value)}")
      )

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
    * A test runs in a sandbox directory, so a relative path means nothing. Walking up to a file that only the
    * root has is more robust than any number of `../`, and it fails with a sentence rather than a
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
