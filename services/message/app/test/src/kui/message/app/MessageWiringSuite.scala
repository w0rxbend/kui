package kui.message.app

import java.nio.file.{Files, Path}

import cats.data.NonEmptyList
import cats.effect.IO

import kui.config.{ClusterConfig, MaskingConfig}
import kui.kernel.ClusterId
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.security.masking.{KeepEnds, MaskingKind, MaskingRule}
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The cursor-signing key, and the two numbers this composition root pins.
  *
  * `services/message/app` declared a test module in `build.mill` and shipped no test source, so the whole of
  * this file was ungated: a cursor is trusted precisely because it was signed, and a key that was a literal —
  * or sixteen bytes instead of thirty-two — would let anyone mint a cursor naming any cluster with every
  * suite in the repository green.
  */
final class MessageWiringSuite extends KuiIOSuite {

  test("each process's cursor key is freshly random, and never a literal") {
    // Two keys, taken the way the composition root takes one. Equal keys mean either a constant or a
    // seeded generator; both are the failure `newCursorKey`'s own docstring argues against.
    for {
      first <- MessageWiring.newCursorKey[IO]
      second <- MessageWiring.newCursorKey[IO]
    } yield {
      assertEquals(first.value.length, MessageWiring.CursorKeyBytes)
      assert(
        !java.util.Arrays.equals(first.value, second.value),
        "two cursor keys minted in one process were identical, so the key is predictable"
      )
      assert(
        first.value.exists(_ != 0.toByte),
        "the cursor key is all zeroes, which is a literal wearing a random's clothes"
      )
    }
  }

  test("the key is 256 bits, which is the block size HMAC-SHA256 wants") {
    // Written out rather than read from the constant, for the reason `SchemaWiringSuite` gives: a case
    // that reads the constant it is checking passes at every value of it.
    assertEquals(MessageWiring.CursorKeyBytes, 32)
  }

  test("the service and its instrumentation are named the same thing everywhere") {
    // A dashboard filtering on the meter's scope and a log search filtering on `service.name` have to
    // agree; nothing else in this repository compares them.
    assertEquals(MessageWiring.Instrumentation, "kui.message")
    assertEquals(kui.message.api.MessageApi.Id.value, "message")
    assertEquals(ClusterSerdeFactories.Attribution.value, "message")
  }

  test("the serde profile version is one, because a restart is what changes a static profile") {
    // It keys the serde registry's caches. Moving it without moving the cache keys would serve a
    // decoded record from the previous profile; it starts mattering when profiles become editable.
    assertEquals(MessageWiring.ProfileVersion, 1L)
  }

  // ------------------------------------------------------------------ the start-up line about masking

  /** A cluster whose masking rules name the things an operator would least like printed. */
  private def masked(rules: List[MaskingRule]): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe("prod"),
      name = "Production",
      bootstrapServers = BootstrapServers.unsafe("prod-broker:9092"),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default,
      masking = MaskingConfig(rules)
    )

  private val twoRules: List[MaskingRule] = List(
    MaskingRule(
      MaskingKind.Mask("*", KeepEnds(0, 4)),
      Some(NonEmptyList.of("nationalInsuranceNumber")),
      None,
      None,
      Some("payments\\.settlements".r)
    ),
    MaskingRule(MaskingKind.Remove, None, Some(".*[Pp]assword.*".r), None, None)
  )

  test("the start-up line says how many masking rules are in force and names none of them") {
    // W9-06/F3. `MaskingConfig.toString` makes this choice and `MaskingConfigSuite` asserts it; this is the
    // SECOND place that prints the same fact, it is the one an operator reads in `docker logs`, and it had
    // no case anywhere. Changing `${cluster.masking.rules.size}` to `${cluster.masking.rules}` left the
    // scoped suite and `./scripts/run-tests.sh` green while printing every masked field name and topic
    // pattern into the log — a shortlist of exactly where this cluster's secrets are, published by the
    // process that exists to hide them.
    //
    // The assertion is over the rendered line rather than over a substring of the roster, because
    // `MaskingRule` is a case class and its own `toString` is what the mutation reaches for.
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- MessageWiring.describeMasking[IO](List(masked(twoRules)), logger)
      entries <- logger.entries
    } yield {
      assertEquals(entries.map(_.level), List("info"))

      val line = entries.head.message
      assert(line.contains("2 rule(s)"), clue = line)
      assert(line.contains("prod"), clue = line)
      assert(!line.contains("nationalInsuranceNumber"), clue = line)
      assert(!line.contains("payments"), clue = line)
      assert(!line.contains("[Pp]assword"), clue = line)
      // And no rule rendered as itself. `MaskingRule` is a case class, so the roster reaches the log
      // through its generated `toString` — which is what the one-word mutation prints. The kinds are as
      // telling as the field names: "this cluster removes something" is itself a fact about the data.
      assert(!line.contains("MaskingRule("), clue = line)
      assert(!line.contains("KeepEnds"), clue = line)
      // W10-04/F3, closed by W10-A2. The count and the absent roster were asserted and the *claim* was
      // not: inverting the sentence to "Masking is also applied on produce and resend" left this case, the
      // scoped suite and `./scripts/run-tests.sh` green. It is the half of the line an operator acts on —
      // the difference between "the topic still holds the real value" and "producing through KUI is safe"
      // — and the same sentence is published in `docs/operations/configuration.md`, so with nothing
      // reading either the two could disagree silently and both look authoritative.
      assert(line.contains("never applied on produce"), clue = line)
      assert(line.contains("resend"), clue = line)
    }
  }

  test("the wiring hands the masking mask the recording metrics adapter and not the silent one") {
    // W10-04/F2, and it is the most serious hole the verification passes filed. `MessageWiring` is the
    // only place that decides which `MaskingMetrics` the running process gets, and nothing asserted the
    // choice: `MaskingMetrics.otel4s[F](meter)` -> `MaskingMetrics.noop[F].pure[F]` is one compiling,
    // -Werror-clean line under which `kui.masking.applied` is never emitted in production while all 1,442
    // cases of this service stay green. `MaskingMetricsSuite` drives the real adapter directly and
    // `ConfiguredRecordMaskingSuite` drives a counting fake; neither can see which one RUNS.
    //
    // This reads the composition root's own source, which is second best and is said so plainly: the
    // assertion a seam would allow — widen `resource` to publish the constructed `MaskingMetrics`, the
    // same `private[app]` widening `describeMasking` already has, and record a masked read through an
    // `OtelJavaTestkit` meter — needs a production edit, and `services/message/app` belongs to another
    // packet this wave. It is filed. What this does hold is the exact defect: a `noop` adapter reaching
    // the production path, for masking or for either of the other two metric families wired beside it.
    val wiring = wiringSource

    List("MaskingMetrics", "CacheMetrics", "FilterMetrics").foreach { family =>
      assert(
        wiring.contains(s"$family.otel4s"),
        s"MessageWiring no longer constructs $family.otel4s, so nothing in this process writes its series"
      )
      assert(
        !wiring.contains(s"$family.noop"),
        s"MessageWiring wires $family.noop into the running service, which records nothing at all"
      )
    }
  }

  test("a cluster that configures no masking says nothing at all, rather than saying zero") {
    // One line per cluster that masks, and silence for the rest. A "0 rule(s)" line on every cluster of a
    // deployment that masks nothing is noise an operator learns to skip, which is how the line that
    // matters gets skipped too.
    for {
      logger <- FakeStructuredLogger[IO]
      _ <- MessageWiring.describeMasking[IO](List(masked(Nil), masked(twoRules)), logger)
      entries <- logger.entries
    } yield assertEquals(entries.size, 1, clue = entries.map(_.message).mkString("\n"))
  }

  /** The composition root's own text, for the one claim about it that has no seam to be made through. */
  private def wiringSource: String = {
    val start = Path.of("").toAbsolutePath
    val root = Iterator
      .iterate(Option(start))(_.flatMap(path => Option(path.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(candidate => Files.exists(candidate.resolve("build.mill")))
      .getOrElse(fail(s"no build.mill above $start, so the repository root could not be found"))

    val file = root.resolve("services/message/app/src/kui/message/app/MessageWiring.scala")
    if Files.isRegularFile(file) then Files.readString(file)
    else fail(s"$file is read by this suite and does not exist")
  }
}
