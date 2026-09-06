package kui.metrics.infrastructure

import java.time.Instant

import scala.util.matching.Regex

import kui.metrics.domain.ThroughputSample

/** One line of a Prometheus text exposition, as this adapter needs to see it.
  *
  * The name is kept exactly as it was served and the comparison is done at the point of use, because the two
  * spellings this parser has to survive differ only in case: a JMX exporter run with `lowercaseOutputName`
  * serves `kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate`, and one run without it serves
  * `kafka_server_BrokerTopicMetrics_OneMinuteRate{name="BytesInPerSec",}`.
  */
final case class PrometheusSample(name: String, labels: Map[String, String], value: Double)

object PrometheusSample {
  given CanEqual[PrometheusSample, PrometheusSample] = CanEqual.derived
}

/** The Prometheus text exposition format, reduced to the two questions the Traffic screen asks.
  *
  * ==Why a parser and not a client library==
  *
  * The format is five lines of grammar — a name, optional labels, a value, an optional timestamp, and
  * comments beginning with `#` — and every Prometheus client library that reads it brings a registry, a
  * scrape scheduler and an HTTP stack, all three of which KUI already has and none of which are
  * interchangeable with the ones it has (ADR-037's breaker and bulkhead, ADR-016's retention). What is left
  * of such a library is this file.
  *
  * ==What it recognises, and what it does with everything else==
  *
  * A JMX exporter in front of a Kafka broker serves several hundred families. Three of them are the cluster's
  * throughput, and the rest — request-handler idle ratios, log-flush percentiles, JVM heap, the exporter's
  * own scrape duration — are ignored rather than refused. An adapter that failed on a family it did not know
  * would break on the next Kafka release, and the honest reading of an exposition carrying information KUI
  * does not use is the information KUI does use.
  *
  * The one thing it will not do is answer from nothing: a body carrying none of the three families is a
  * `Left`, because a source that publishes no throughput at all is a misconfiguration an operator has to be
  * told about, and a sample of three `None`s would file a measured gap instead.
  *
  * ==Per-broker aggregates only==
  *
  * `BrokerTopicMetrics` is published twice: once for the broker and once per topic, distinguished by a
  * `topic` label. A parser that took both would count every byte twice — once in the aggregate and once in
  * whichever topic carried it — so a sample carrying a `topic` label is skipped. The same is true of the
  * `partition` label some builds add.
  */
object PrometheusExposition {

  /** The three families this service reads, spelled as the lowercased JMX MBean attribute they come from. */
  val BytesIn: String = "bytesinpersec"
  val BytesOut: String = "bytesoutpersec"
  val MessagesIn: String = "messagesinpersec"

  /** The Kafka MBean type every one of them lives under, lowercased. */
  private val BrokerTopicMetrics: String = "brokertopicmetrics"

  /** Kafka's `BrokerTopicMetrics` are Yammer `Meter`s, so the broker publishes the rate itself and KUI does
    * not have to difference two counters to get one. The one-minute rate rather than the mean rate: a mean
    * taken since the broker booted is a number that stops moving after a week of uptime, which is the one
    * thing a *live* traffic chart must not draw.
    */
  private val RateSuffix: String = "oneminuterate"

  /** The only label a broker-wide sample ever carries: the MBean attribute's name, under the exporter's
    * unruled naming scheme. Every other label — `topic` on `BrokerTopicMetrics`, and whatever a future Kafka
    * release dimensions these families by — means the line is a *slice* of the aggregate.
    *
    * Stated as "any label but this one" rather than as a list of known slice labels, because the two get a
    * different answer wrong. A list has to be complete to be safe: the day a broker publishes these families
    * per listener or per rack, a parser holding a list would silently start adding the slices to the
    * aggregate, and the chart would read high with nothing failing. This way the unknown dimension is
    * skipped, and the aggregate — which is published beside every slice — is still there to be read.
    */
  private val AggregateLabel: String = "name"

  /** `name{a="1",b="2"} 12.5 1699999999999` — the value's trailing timestamp is optional and unused: KUI
    * stamps a sample with the instant it asked, because an exporter's clock is not the one the axis is drawn
    * against.
    */
  private val Line: Regex = """^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([^\s]+)(?:\s+[^\s]+)?\s*$""".r

  private val Label: Regex = """([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""".r

  /** Every sample the body carried, in the order it carried them. Unreadable lines are dropped rather than
    * failing the body: a single malformed line in a three-hundred-family exposition is one metric lost, and
    * refusing the whole scrape over it would lose the other two hundred and ninety-nine.
    */
  def parse(body: String): List[PrometheusSample] =
    body.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap(sampleOf)
      .toList

  /** The cluster's throughput as of `at`, or the reason this body carried none.
    *
    * `at` is the instant KUI took the reading and is a parameter for the reason the port's is: the axis is
    * drawn against KUI's clock, and a sample stamped with an exporter's would put a broker whose clock is two
    * minutes fast into a bucket that has not happened yet.
    */
  def throughputAt(at: Instant, body: String): Either[String, ThroughputSample] = {
    val samples = parse(body)
    val sample = ThroughputSample(
      at = at,
      bytesInPerSecond = rateOf(samples, BytesIn),
      bytesOutPerSecond = rateOf(samples, BytesOut),
      recordsPerSecond = rateOf(samples, MessagesIn)
    )

    if !sample.isEmpty then Right(sample)
    else if samples.isEmpty then
      Left(
        "it carries no Prometheus samples at all, so the address is answering with something that is not " +
          "an exposition"
      )
    else
      Left(
        s"it carries ${samples.size} samples and none of them is a broker-wide $BytesIn, $BytesOut or " +
          s"$MessagesIn rate; check the exporter's whitelist"
      )
  }

  /** The broker-wide one-minute rate for one family, under either of the exporter's two naming schemes.
    *
    * The first match wins rather than a sum. An exporter in httpserver mode is attached to one broker's JVM
    * and publishes each MBean attribute once; a second line matching the same family is the same number
    * spelled the other way, which a sum would double.
    */
  private def rateOf(samples: List[PrometheusSample], family: String): Option[Double] =
    samples.find(sample => isBrokerWideRate(sample, family)).map(_.value)

  private def isBrokerWideRate(sample: PrometheusSample, family: String): Boolean = {
    val name = sample.name.toLowerCase
    // `_` before the family, so `replicationbytesinpersec` — which is a different number, and a much
    // smaller one — cannot be read as `bytesinpersec`.
    val namesFamily =
      name.contains(s"_$family") || sample.labels.get("name").map(_.toLowerCase).contains(family)

    name.contains(BrokerTopicMetrics) &&
    name.endsWith(RateSuffix) &&
    namesFamily &&
    sample.labels.keySet.forall(_ == AggregateLabel)
  }

  private def sampleOf(line: String): Option[PrometheusSample] =
    line match {
      case Line(name, labels, raw) =>
        // `NaN`, `+Inf` and `-Inf` are legal exposition values and are not measurements: a JMX attribute
        // that has not been populated since the broker started reads as `NaN`, and charting it as a number
        // is the fabricated figure this product exists not to draw.
        raw.toDoubleOption
          .filter(value => !value.isNaN && !value.isInfinite)
          .map(value => PrometheusSample(name, labelsOf(Option(labels).getOrElse("")), value))
      case _ => None
    }

  private def labelsOf(raw: String): Map[String, String] =
    Label
      .findAllMatchIn(raw)
      .map(matched => matched.group(1) -> unescape(matched.group(2)))
      .toMap

  /** The three escapes the format defines inside a label value, and nothing else. */
  private def unescape(value: String): String =
    value.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n")
}
