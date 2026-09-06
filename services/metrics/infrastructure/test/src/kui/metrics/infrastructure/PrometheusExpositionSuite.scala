package kui.metrics.infrastructure

import java.time.Instant

import scala.io.Source
import scala.util.Using

import munit.FunSuite

/** What the parser makes of an exposition an exporter actually serves.
  *
  * The bodies are files rather than strings built beside the assertions, and that is the point: a parser
  * tested against text a test wrote is a parser tested against the shape its author already believed in.
  * These carry the families this service ignores, the per-topic slices it must not add up, a NaN, comment
  * lines, blank lines and a family whose name *contains* one of the names being looked for.
  */
final class PrometheusExpositionSuite extends FunSuite {

  private val at = Instant.parse("2026-09-06T12:00:00Z")

  private def body(name: String): String =
    Using
      .resource(Option(getClass.getResourceAsStream(s"/exposition/$name")).getOrElse {
        fail(s"the exposition fixture '$name' is not on the test classpath")
      })(stream => Source.fromInputStream(stream, "UTF-8").mkString)

  private val lowercased = body("kafka-jmx-exporter.txt")
  private val mbeanNames = body("kafka-jmx-exporter-mbean-names.txt")

  test("a body the exporter served becomes a sample with both rates") {
    val sample = PrometheusExposition.throughputAt(at, lowercased)

    assertEquals(sample.map(_.bytesInPerSecond), Right(Some(124800.5)))
    assertEquals(sample.map(_.bytesOutPerSecond), Right(Some(249600.75)))
    assertEquals(sample.map(_.recordsPerSecond), Right(Some(1420.75)))
    assertEquals(sample.map(_.at), Right(at))
  }

  test("the one-minute rate is read and not the count, the mean rate or the five-minute rate") {
    // A count is a monotonic total and charting it draws a line that only ever goes up; a mean rate taken
    // since the broker booted stops moving after a week of uptime. Both are in the fixture, either would
    // parse, and neither is a live traffic figure.
    assertEquals(
      PrometheusExposition.throughputAt(at, lowercased).map(_.bytesInPerSecond),
      Right(Some(124800.5))
    )
  }

  test("a per-topic slice of the same MBean is skipped, so no byte is counted twice") {
    // The fixture's two `topic="..."` lines sum to the broker-wide figure, which is exactly how this goes
    // wrong: a parser that took the first matching line in document order would answer 98000.0 and a
    // parser that summed every match would answer 348601.0. Both look plausible on a chart.
    val sample = PrometheusExposition.throughputAt(at, lowercased)

    assertEquals(sample.map(_.bytesInPerSecond), Right(Some(124800.5)))
    assert(!PrometheusExposition.parse(lowercased).filter(_.labels.contains("topic")).isEmpty)
  }

  test("a dimension this parser has never seen is a slice too, and is skipped") {
    // The `topic` label is the one an exporter publishes today, and a list of known slice labels would be
    // safe only for as long as that stayed true: the day a Kafka release dimensions these families by
    // anything else, a parser holding a list would start adding the slices to the aggregate and the chart
    // would read high with nothing failing. The aggregate carries no dimension at all, so that is the
    // test.
    val dimensioned =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate{listener="EXTERNAL",} 25.0
        |kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 40.0
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 80.0
        |""".stripMargin

    assertEquals(
      PrometheusExposition.throughputAt(at, dimensioned).map(_.bytesInPerSecond),
      Right(Some(40.0))
    )
  }

  test("replicationbytesinpersec is not read as bytesinpersec, though it contains it") {
    // Replication traffic is a real number and it is not the cluster's throughput. The name test has to
    // be anchored at a `_`, and the fixture is what makes the unanchored version fail.
    val samples = PrometheusExposition.parse(lowercased)
    val replication = samples.find(_.name.contains("replicationbytesinpersec")).map(_.value)

    assertEquals(replication, Some(61200.0))
    assertEquals(
      PrometheusExposition.throughputAt(at, lowercased).map(_.bytesInPerSecond),
      Right(Some(124800.5))
    )
  }

  test("the same body under the exporter's other naming scheme reads identically") {
    // An operator who runs the exporter with no rule file gets `name="BytesInPerSec"` as a label instead
    // of as part of the metric name. Refusing that deployment would be refusing the exporter's default.
    assertEquals(
      PrometheusExposition.throughputAt(at, mbeanNames),
      PrometheusExposition.throughputAt(at, lowercased)
    )
  }

  test("families it does not know are ignored rather than failing the body") {
    val samples = PrometheusExposition.parse(lowercased)

    assert(samples.exists(_.name.startsWith("jvm_memory")), "an unknown family should still parse")
    assert(samples.exists(_.name.startsWith("kafka_controller")), "an unknown family should still parse")
    assert(PrometheusExposition.throughputAt(at, lowercased).isRight)
  }

  test("a NaN is not a measurement and does not become one") {
    // A JMX attribute nobody has populated since the broker started reads as NaN. It is legal exposition
    // and it is not a number: charting it is the fabricated figure this product exists not to draw.
    assert(!PrometheusExposition.parse(lowercased).exists(_.value.isNaN))
  }

  test("a body that cannot be parsed is a Left and no sample") {
    // Not an exposition at all — an ingress serving its own error page, which is what a mistyped port
    // usually produces.
    val html = PrometheusExposition.throughputAt(at, "<html><body>502 Bad Gateway</body></html>")

    assert(html.isLeft, s"expected a refusal, got $html")
    assert(html.left.exists(_.contains("no Prometheus samples")), html.toString)
  }

  test("a valid exposition with no Kafka families is refused by name, not read as zero") {
    // The node exporter, reached by copying the wrong port out of a compose file. It parses perfectly and
    // says nothing about a broker, and the wrong answer here is a chart of zeroes.
    val other = PrometheusExposition.throughputAt(at, body("no-kafka-families.txt"))

    assert(other.isLeft, s"expected a refusal, got $other")
    assert(other.left.exists(_.contains("whitelist")), other.toString)
  }

  test("an exporter publishing only the two byte rates is measured, not refused") {
    // W4-02's exporter configuration is asked for `BytesInPerSec` and `BytesOutPerSec` *at least*. A
    // whitelist naming exactly those two is an ordinary configuration, and refusing the whole card
    // because a third rate nobody draws was absent would lose the screen this milestone is for.
    val trimmed =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 10.0
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 20.0
        |""".stripMargin

    val sample = PrometheusExposition.throughputAt(at, trimmed)
    assertEquals(sample.map(_.bytesInPerSecond), Right(Some(10.0)))
    assertEquals(sample.map(_.recordsPerSecond), Right(None))
  }
}
