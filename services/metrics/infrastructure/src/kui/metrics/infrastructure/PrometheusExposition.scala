package kui.metrics.infrastructure

import java.time.Instant

import scala.util.matching.Regex

import kui.metrics.domain.{BrokerSample, PurgatoryQueue, TopicProducer}

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

/** The Prometheus text exposition format, reduced to the questions the dashboard's metrics cards ask.
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
  * A JMX exporter in front of a Kafka broker serves several hundred families — 670 of them under the stock
  * ruleset, measured against the quickstart broker, recorded in ADR-052:29 and counted in the header of the
  * capture committed beside this file
  * (`services/metrics/infrastructure/test/resources/exposition/kafka-broker-stock-ruleset.txt:7`). This
  * parser reads eight of them. Everything else — log-flush percentiles, JVM heap, the controller's own beans,
  * the exporter's scrape duration — is ignored rather than refused. An adapter that failed on a family it did
  * not know would break on the next Kafka release, and the honest reading of an exposition carrying
  * information KUI does not use is the information KUI does use.
  *
  * The one thing it will not do is answer from nothing: a body carrying none of the eight is a `Left`,
  * because a source that publishes nothing this service reads is a misconfiguration an operator has to be
  * told about, and a sample of nothing but `None`s would file a measured gap on every card instead.
  *
  * ==Aggregates and slices==
  *
  * `BrokerTopicMetrics` is published twice: once for the broker and once per topic, distinguished by a
  * `topic` label. Adding both would count every byte twice — once in the aggregate and once in whichever
  * topic carried it — so the two are read separately and for different cards. The broker-wide throughput
  * takes only the line with **no dimension at all** ([[dimensionsOf]]); the top-producers card takes only the
  * lines dimensioned by `topic` and nothing else.
  *
  * ==Why "no dimension" rather than a list of dimensions to skip==
  *
  * The two get a different answer wrong. A list of known slice labels has to be complete to be safe: the day
  * a broker publishes these families per listener or per rack, a parser holding a list would silently start
  * adding the slices to the aggregate, and the chart would read high with nothing failing. Requiring the
  * aggregate to carry no dimension means an unknown dimension is skipped, and the aggregate — which is
  * published beside every slice — is still there to be read.
  *
  * ==The names below are a contract==
  *
  * `deployment/metrics/kafka-jmx-exporter.yml` whitelists what a broker publishes, and its own comment calls
  * the list a contract with this file. Both spellings are accepted for every family, so the same parser reads
  * a stock-ruleset exporter and a whitelisted one.
  */
object PrometheusExposition {

  // -----------------------------------------------------------------------------------------------
  // The MBean types and attributes this parser reads, lowercased
  // -----------------------------------------------------------------------------------------------

  /** The three throughput rates, spelled as the lowercased JMX MBean attribute they come from. */
  val BytesIn: String = "bytesinpersec"
  val BytesOut: String = "bytesoutpersec"
  val MessagesIn: String = "messagesinpersec"

  /** The Kafka MBean type all three of them live under, and the per-topic slices with them. */
  val BrokerTopicMetrics: String = "brokertopicmetrics"

  /** `kafka.network:type=RequestMetrics,name=TotalTimeMs,request=…`, whose `99thPercentile` the broker
    * computes for itself. KUI never derives a percentile (ADR-052).
    */
  val RequestMetrics: String = "requestmetrics"
  val TotalTimeMs: String = "totaltimems"

  /** The two request kinds the Traffic screen draws, as the broker spells its `request` label. */
  val ProduceRequest: String = "produce"
  val FetchRequest: String = "fetchconsumer"

  /** `kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent` — the pool that does the
    * disk and replication work, and the design's "IO IDLE". A meter, so the rate suffix applies.
    *
    * The attribute is matched whole. A broker also publishes `BrokerRequestHandlerAvgIdlePercent` and
    * `ControllerRequestHandlerAvgIdlePercent`, which are different pools and different numbers.
    */
  val RequestHandlerPool: String = "kafkarequesthandlerpool"
  val RequestHandlerIdle: String = "requesthandleravgidlepercent"

  /** `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent` — the design's "NETWORK IDLE". A
    * plain gauge, so there is no rate suffix on it.
    */
  val SocketServer: String = "socketserver"
  val NetworkProcessorIdle: String = "networkprocessoravgidlepercent"

  /** `kafka.server:type=DelayedOperationPurgatory,name=PurgatorySize` — a queue **length**, per delayed
    * operation. There is no purgatory percentage to read (ADR-052).
    */
  val DelayedOperationPurgatory: String = "delayedoperationpurgatory"
  val PurgatorySize: String = "purgatorysize"

  /** Kafka's `BrokerTopicMetrics` are Yammer `Meter`s, so the broker publishes the rate itself and KUI does
    * not have to difference two counters to get one. The one-minute rate rather than the mean rate: a mean
    * taken since the broker booted is a number that stops moving after a week of uptime, which is the one
    * thing a *live* traffic chart must not draw.
    */
  private val RateSuffix: String = "oneminuterate"

  /** The Yammer histogram attribute the latency card is drawn from. */
  private val P99Suffix: String = "99thpercentile"

  /** The label an exporter with no rule file puts the MBean *attribute* in. It is not a dimension: it is the
    * attribute's own identity, which a whitelisted exporter puts in the metric name instead.
    */
  private val AttributeLabel: String = "name"

  /** The dimensions this parser understands, each belonging to exactly one family. */
  private val TopicLabel: String = "topic"
  private val RequestLabel: String = "request"
  private val DelayedOperationLabel: String = "delayedoperation"

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

  /** Everything this body says about the broker as of `at`, or the reason it says nothing at all.
    *
    * `at` is the instant KUI took the reading and is a parameter for the reason the port's is: the axis is
    * drawn against KUI's clock, and a sample stamped with an exporter's would put a broker whose clock is two
    * minutes fast into a bucket that has not happened yet.
    *
    * A family the exporter does not serve leaves its field `None` and refuses nothing. That is the rule that
    * makes one dead family cost one card: an operator whose whitelist carries the byte rates and not the
    * request percentiles gets a throughput chart and a latency card that says why it has nothing.
    */
  def brokerSampleAt(at: Instant, body: String): Either[String, BrokerSample] = {
    val samples = parse(body)
    val bytesInLines = samples.filter(sample => isThroughputRate(sample, BytesIn))

    val sample = BrokerSample(
      at = at,
      bytesInPerSecond = aggregateOf(bytesInLines),
      bytesOutPerSecond = aggregateOf(samples.filter(isThroughputRate(_, BytesOut))),
      recordsPerSecond = aggregateOf(samples.filter(isThroughputRate(_, MessagesIn))),
      produceP99Millis = percentileOf(samples, ProduceRequest),
      fetchP99Millis = percentileOf(samples, FetchRequest),
      requestHandlerIdleRatio = ratioOf(samples, RequestHandlerPool, RequestHandlerIdle, rated = true),
      networkProcessorIdleRatio = ratioOf(samples, SocketServer, NetworkProcessorIdle, rated = false),
      purgatory = purgatoryOf(samples),
      // The family is present exactly when *some* `BytesInPerSec` line was served, aggregate or slice. That
      // separates "the whitelist omits this family entirely" from "the family is here and carries no topic
      // line" — which is as far as an exposition can separate anything, because a broker with no producers
      // and a ruleset with no per-topic rule serve the same bytes. Both were seen on a live broker; the
      // second fact travels as an empty list and the card's sentence names both of its causes.
      topicBytesInPerSecond = Option.when(bytesInLines.nonEmpty)(topicsOf(bytesInLines))
    )

    if !sample.isEmpty then Right(sample)
    else if samples.isEmpty then
      Left(
        "it carries no Prometheus samples at all, so the address is answering with something that is not " +
          "an exposition"
      )
    else
      Left(
        s"it carries ${samples.size} samples and not one of them is a Kafka broker family this build " +
          s"reads ($BytesIn, $BytesOut, $MessagesIn, $RequestMetrics, $RequestHandlerIdle, " +
          s"$NetworkProcessorIdle, $PurgatorySize); check the exporter's whitelist"
      )
  }

  // -----------------------------------------------------------------------------------------------

  /** The broker-wide value among a family's lines, or `None`.
    *
    * The first match wins rather than a sum. An exporter in httpserver mode is attached to one broker's JVM
    * and publishes each MBean attribute once; a second line matching the same family is the same number
    * spelled the other way, which a sum would double.
    */
  private def aggregateOf(lines: List[PrometheusSample]): Option[Double] =
    lines.find(sample => dimensionsOf(sample).isEmpty).map(_.value)

  /** The per-topic slices, by topic name.
    *
    * Sorted here and ranked by rate later, so that two exporters serving the same broker's numbers in a
    * different line order produce the same value — which is what lets a suite assert that the two naming
    * schemes read identically rather than merely equivalently.
    */
  private def topicsOf(lines: List[PrometheusSample]): List[TopicProducer] =
    lines
      .filter(sample => dimensionsOf(sample) == Set(TopicLabel))
      .flatMap(sample => labelOf(sample, TopicLabel).map(topic => TopicProducer(topic, sample.value)))
      .sortBy(_.topic)

  private def isThroughputRate(sample: PrometheusSample, family: String): Boolean =
    sample.name.toLowerCase.endsWith(RateSuffix) && isAttribute(sample, BrokerTopicMetrics, family)

  private def percentileOf(samples: List[PrometheusSample], request: String): Option[Double] =
    samples
      .find { sample =>
        sample.name.toLowerCase.endsWith(P99Suffix) &&
        isAttribute(sample, RequestMetrics, TotalTimeMs) &&
        dimensionsOf(sample) == Set(RequestLabel) &&
        labelOf(sample, RequestLabel).map(_.toLowerCase).contains(request)
      }
      .map(_.value)

  private def ratioOf(
      samples: List[PrometheusSample],
      mbeanType: String,
      attribute: String,
      rated: Boolean
  ): Option[Double] =
    samples
      .find { sample =>
        (!rated || sample.name.toLowerCase.endsWith(RateSuffix)) &&
        isAttribute(sample, mbeanType, attribute) &&
        dimensionsOf(sample).isEmpty
      }
      .map(_.value)

  /** Every delayed-operation queue the body carried, ordered by the broker's own name for it.
    *
    * Sorted rather than left in document order, so that two scrapes of the same broker put the same queue in
    * the same place on a card. `Math.round` because a queue length is a count that arrives as a `Double`:
    * every exposition value is a float and `PurgatorySize` has no fractional part to lose.
    */
  private def purgatoryOf(samples: List[PrometheusSample]): List[PurgatoryQueue] =
    samples
      .filter { sample =>
        isAttribute(sample, DelayedOperationPurgatory, PurgatorySize) &&
        dimensionsOf(sample) == Set(DelayedOperationLabel)
      }
      .flatMap { sample =>
        labelOf(sample, DelayedOperationLabel)
          .map(operation => PurgatoryQueue(operation, Math.round(sample.value)))
      }
      .sortBy(_.operation)

  /** Whether this line is the named attribute of the named MBean type, under either naming scheme.
    *
    * The attribute is anchored at a `_` in the metric name, so `replicationbytesinpersec` — which is a
    * different number, and a much smaller one — cannot be read as `bytesinpersec`, and
    * `brokerrequesthandleravgidlepercent` cannot be read as `requesthandleravgidlepercent`.
    */
  private def isAttribute(sample: PrometheusSample, mbeanType: String, attribute: String): Boolean = {
    val name = sample.name.toLowerCase

    name.contains(mbeanType) &&
    (name.contains(s"_$attribute") || labelOf(sample, AttributeLabel).map(_.toLowerCase).contains(attribute))
  }

  /** One label's value, whatever case the exporter spelled the key in.
    *
    * `lowercaseOutputLabelNames` is a rule file's setting and an exporter run without one serves
    * `delayedOperation` where a whitelisted one serves `delayedoperation`. Both stacks KUI ships set it; an
    * operator pointing this build at their own exporter has not necessarily done so, and a key lookup that
    * matched only one spelling would silently drop a whole family for them.
    */
  private def labelOf(sample: PrometheusSample, key: String): Option[String] =
    sample.labels.collectFirst { case (name, value) if name.equalsIgnoreCase(key) => value }

  /** The labels that make this line a *slice* rather than the whole reading.
    *
    * `name` is excluded because it is not a dimension: an exporter with no rule file puts the MBean attribute
    * there, and a whitelisted one puts the same word in the metric name. Everything else narrows the reading
    * to one topic, one request kind or one delayed operation.
    */
  private def dimensionsOf(sample: PrometheusSample): Set[String] =
    sample.labels.keySet.map(_.toLowerCase) - AttributeLabel

  private def sampleOf(line: String): Option[PrometheusSample] =
    line match {
      case Line(name, labels, raw) =>
        // `NaN`, `+Inf` and `-Inf` are legal exposition values and are not measurements: a JMX attribute
        // that has not been populated since the broker started reads as `NaN`, and a counter an exporter
        // could not read reads as `+Inf`. Charting either is the fabricated figure this product exists not
        // to draw.
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
