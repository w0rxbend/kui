package kui.message.app

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import cats.data.NonEmptyList
import cats.effect.IO
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
// `export` is a Scala 3 keyword, so the package that holds the SDK's reader interfaces needs backticks.
import io.opentelemetry.sdk.metrics.`export`.{
  AggregationTemporalitySelector,
  CollectionRegistration,
  MetricReader
}
import io.opentelemetry.sdk.metrics.data.{AggregationTemporality, MetricData}
import io.opentelemetry.sdk.metrics.{InstrumentType, SdkMeterProvider}
import org.typelevel.otel4s.oteljava.metrics.Metrics

import kui.config.{ClusterConfig, MaskingConfig}
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.serde.Target
import kui.kernel.{ClusterId, TopicName}
import kui.observability.MetricNames
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

  test("the mask this process browses through records kui.masking.applied to a real meter") {
    // W10-04/F2, THE RULE THIS PACKET OWNS, and wave 10 could only guard it by reading source text.
    // `MessageWiring` is the only place that decides which `MaskingMetrics` the running process gets, and
    // nothing asserted the choice: `MaskingMetrics.otel4s[F](meter)` -> `MaskingMetrics.noop[F].pure[F]`
    // is one compiling, -Werror-clean line under which `kui.masking.applied` is never emitted in
    // production while all 1,442 cases of this service stay green. `MaskingMetricsSuite` drives the real
    // adapter directly and `ConfiguredRecordMaskingSuite` drives a counting fake; neither can see which
    // one RUNS, because neither is reached from the composition root.
    //
    // `maskingFor` is the seam W10-04 named, landed here: it is the production expression, `private[app]`
    // the way `describeMasking` is, and this case takes it, hands it a meter over an in-memory SDK reader,
    // masks a topic through what comes back and reads the point off the meter. The assertion is about
    // what the process HOLDS rather than about either adapter on its own, so the one-word mutation has
    // nowhere left to hide.
    val reader = new CollectingMetricReader

    for {
      meter <- recordingMeter(reader)
      masking <- MessageWiring.maskingFor[IO](List(masked(twoRules)), meter)
      // `payments.settlements` is what `twoRules`' first rule scopes itself to, so this is a read on
      // which a rule really is in force — the only shape on which the series is written at all.
      _ <- masking.forTopic(ClusterId.unsafe("prod"), TopicName.unsafe("payments.settlements"))
    } yield {
      val points = applications(reader.collected)

      assert(
        points.nonEmpty,
        "nothing recorded kui.masking.applied for a masked read, so this process holds a silent adapter"
      )
      // Both halves of the record, because `ConfiguredRecordMasking` decides them separately and the
      // second rule of `twoRules` is unscoped, so it reaches the keys as well as the values.
      assertEquals(
        points.map(_(MetricNames.Attr.Target)).sorted,
        List(Target.Key.label, Target.Value.label).sorted
      )
      // And the attributes an operator's panel filters on, which a noop and a mis-labelled adapter both
      // fail differently: `MaskingMetricsSuite` owns that argument and this is the process-level echo.
      points.foreach { attributes =>
        assertEquals(attributes(MetricNames.Attr.Cluster), "prod")
        assertEquals(attributes(MetricNames.Attr.Topic), "payments.settlements")
      }
    }
  }

  test("the two metric families with no seam are still constructed, read off the composition root") {
    // `CacheMetrics` and `FilterMetrics` are wired beside the masking metrics and have the same defect
    // and no seam of their own: nothing they feed is reachable from this module without building a Kafka
    // consumer and a CEL engine. So they keep wave 10's source read, which is second best and is said so
    // plainly here rather than left to look like a real assertion. `MaskingMetrics` is deliberately NOT
    // in this list any more — the case above is the real thing, and leaving it here as well would let a
    // reader think the source read is what gates it.
    val wiring = wiringSource

    // AND THE SEAM'S USE, which is the half the case above cannot reach. `maskingFor` is driven in
    // isolation there, so `make`'s `masking <- Resource.eval(maskingFor[F](clusters, meter))` replaced by
    // an inline `MaskingMetrics.noop[F]` construction -- leaving `maskingFor` in the file, compiling,
    // -Werror-clean and simply uncalled -- left `./mill -k services.message.__.test` at 1442/1442 SUCCESS.
    // That is W10-04/F2's actual defect, the running process holding a silent adapter, reachable again in
    // one edit that never touches the function the assertion reads. Filed as W11-05/V-1. Wave 10's source
    // read, `wiring.contains("MaskingMetrics.otel4s")`, had exactly this reach over the whole file and was
    // narrowed to the two families when the seam landed; this puts the call site back under a gate.
    assert(
      wiring.contains("maskingFor[F](clusters, meter)"),
      "MessageWiring.make no longer calls maskingFor, so the seam the case above drives is not the one " +
        "the running process holds and kui.masking.applied can go silent in production with this " +
        "service's whole suite green"
    )

    List("CacheMetrics", "FilterMetrics").foreach { family =>
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

  /** An otel4s meter over an SDK that keeps its points in memory, for `reader` to hand back.
    *
    * Built from `opentelemetry-sdk-metrics` and `otel4s-oteljava`, both of which reach this module through
    * `libs/observability`'s own compile dependencies. The testkit artifact every other suite in this
    * repository uses for the same job — `otel4s-oteljava-testkit`, and `OtelJavaTestkit.inMemory` — is not on
    * this module's classpath, and adding it is an edit to `build.mill`, which belongs to another packet this
    * wave. Thirty lines of reader beats a cross-packet dependency for a gate that has waited a wave already;
    * the note is here so whoever adds the artifact knows this can collapse into two lines.
    */
  private def recordingMeter(reader: CollectingMetricReader): IO[org.typelevel.otel4s.metrics.Meter[IO]] =
    Metrics
      .fromJOpenTelemetry[IO](
        OpenTelemetrySdk
          .builder()
          .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
          .build()
      )
      .meterProvider
      .get(MessageWiring.Instrumentation)

  /** The attributes of every recorded point of `kui.masking.applied`. */
  private def applications(metrics: List[MetricData]): List[Map[String, String]] =
    metrics
      .filter(_.getName == MetricNames.MaskingApplied)
      .flatMap(_.getLongSumData.getPoints.asScala.toList)
      .map(point => point.getAttributes.asMap.asScala.map((key, value) => key.getKey -> value.toString).toMap)

  /** A metric reader that collects on demand instead of exporting.
    *
    * The SDK hands a `CollectionRegistration` to every registered reader and that object is the only way to
    * pull the recorded points out of a meter provider, so this keeps it and exposes one call. Cumulative
    * temporality because the assertion is "was this ever recorded", and a delta reader answers that only if
    * you ask before the next collection.
    */
  final private class CollectingMetricReader extends MetricReader {

    private val registration: AtomicReference[CollectionRegistration] =
      new AtomicReference(CollectionRegistration.noop())

    def register(collection: CollectionRegistration): Unit = registration.set(collection)

    def getAggregationTemporality(instrument: InstrumentType): AggregationTemporality =
      AggregationTemporalitySelector.alwaysCumulative().getAggregationTemporality(instrument)

    def forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    def collected: List[MetricData] = registration.get().collectAllMetrics().asScala.toList
  }

  /** The composition root's own text, for the two claims about it that have no seam to be made through. */
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
