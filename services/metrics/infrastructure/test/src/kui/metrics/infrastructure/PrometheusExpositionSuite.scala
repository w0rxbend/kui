package kui.metrics.infrastructure

import java.time.Instant

import scala.io.Source
import scala.util.Using

import munit.FunSuite

import kui.metrics.domain.{PurgatoryQueue, TopicProducer}

/** What the parser makes of an exposition an exporter actually serves.
  *
  * The bodies are files rather than strings built beside the assertions, and that is the point: a parser
  * tested against text a test wrote is a parser tested against the shape its author already believed in.
  * These carry the families this service ignores, the per-topic slices it must not add up, attribute names
  * that contain the ones it looks for, a NaN, an infinity, comment lines and blank lines.
  *
  * One of the three is a **real capture**, trimmed but not edited, taken from a Prometheus JMX exporter
  * running the stock ruleset against the quickstart broker. It is what ADR-052's three refusals rest on, and
  * a refusal with no evidence behind it is indistinguishable from an endpoint nobody wrote.
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
  private val captured = body("kafka-broker-stock-ruleset.txt")

  private def sampleOf(text: String) =
    PrometheusExposition.brokerSampleAt(at, text).fold(why => fail(s"expected a sample, got $why"), identity)

  // -----------------------------------------------------------------------------------------------
  // Throughput
  // -----------------------------------------------------------------------------------------------

  test("a body the exporter served becomes a sample with both rates") {
    val sample = sampleOf(lowercased)

    assertEquals(sample.bytesInPerSecond, Some(124800.5))
    assertEquals(sample.bytesOutPerSecond, Some(249600.75))
    assertEquals(sample.recordsPerSecond, Some(1420.75))
    assertEquals(sample.at, at)
  }

  test("the one-minute rate is read and not the count, the mean rate or the five-minute rate") {
    // A count is a monotonic total and charting it draws a line that only ever goes up; a mean rate taken
    // since the broker booted stops moving after a week of uptime. Both are in the fixture, either would
    // parse, and neither is a live traffic figure.
    assertEquals(sampleOf(lowercased).bytesInPerSecond, Some(124800.5))
  }

  test("a per-topic slice of the same MBean is skipped, so no byte is counted twice") {
    // The fixture's two `topic="..."` lines and the aggregate sum to 249601.0, which is exactly how this
    // goes wrong: a parser that took the first matching line in document order would answer 98000.0 and a
    // parser that summed every match would answer 249601.0. Both look plausible on a chart.
    assertEquals(sampleOf(lowercased).bytesInPerSecond, Some(124800.5))
    assert(PrometheusExposition.parse(lowercased).exists(_.labels.contains("topic")))
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

    assertEquals(sampleOf(dimensioned).bytesInPerSecond, Some(40.0))
    // And it is not read as a topic either: `top producers` would otherwise grow a row called "EXTERNAL".
    assertEquals(sampleOf(dimensioned).topicBytesInPerSecond, Some(Nil))
  }

  test("replicationbytesinpersec is not read as bytesinpersec, though it contains it") {
    // Replication traffic is a real number and it is not the cluster's throughput. The name test has to be
    // anchored at a `_`, and the fixture is what makes the unanchored version fail: the replication line is
    // written *before* the aggregate, and `aggregateOf` takes the first undimensioned line that matches. So
    // an unanchored `name.contains(attribute)` reads 61200.0 here, and this case is what says so.
    val samples = PrometheusExposition.parse(lowercased)
    val decoyIsFirst =
      samples.indexWhere(_.name.contains("replicationbytesinpersec")) <
        samples.indexWhere(sample => sample.name == "kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate")

    assert(decoyIsFirst, "the fixture must put the decoy first or this case gates nothing")
    assertEquals(samples.find(_.name.contains("replicationbytesinpersec")).map(_.value), Some(61200.0))
    assertEquals(sampleOf(lowercased).bytesInPerSecond, Some(124800.5))
  }

  test("a body carrying only the replication family reports no cluster throughput at all") {
    // The same rule with the decoy alone, which is the shape that cannot be satisfied by line order. An
    // exporter whose ruleset whitelists ReplicationBytesInPerSec and not BytesInPerSec measures the traffic
    // between brokers and none of the traffic from producers; charting it would draw a cluster's
    // throughput out of its own replication.
    val replicationOnly =
      """kafka_server_brokertopicmetrics_replicationbytesinpersec_oneminuterate 61200.0
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 20.0
        |""".stripMargin

    val sample = sampleOf(replicationOnly)
    assertEquals(sample.bytesInPerSecond, None)
    assertEquals(sample.topicBytesInPerSecond, None)
  }

  // -----------------------------------------------------------------------------------------------
  // Latency, idle ratios, purgatory and topics
  // -----------------------------------------------------------------------------------------------

  test("a captured exposition becomes a latency sample carrying produce and fetch p99") {
    // The required case, against a body a real broker really served. `TotalTimeMs` is published for
    // twenty-odd request kinds and the two the screen draws are picked out by the `request` dimension.
    val sample = sampleOf(captured)

    assertEquals(sample.produceP99Millis, Some(9.0))
    assertEquals(sample.fetchP99Millis, Some(502.0))
  }

  test("the p99 is read and not the mean or the max published beside it") {
    // All three are in the captured body under one attribute. A p99 of 9 ms and a max of 1296 ms are very
    // different claims about the same broker, and only one of them is what the legend chip says.
    val sample = sampleOf(captured)

    assertEquals(sample.fetchP99Millis, Some(502.0))
    assert(PrometheusExposition.parse(captured).exists(_.name.endsWith("_max")))
    assert(PrometheusExposition.parse(captured).exists(_.name.endsWith("_mean")))
  }

  test("an idle ratio arrives as a ratio and is not pre-formatted") {
    // 0..1 exactly as the broker publishes it. A service that shipped "89%" would be choosing a rounding
    // and a locale for every client, and a ring gauge cannot draw an arc from a string.
    val sample = sampleOf(lowercased)

    assertEquals(sample.requestHandlerIdleRatio, Some(0.8912))
    assertEquals(sample.networkProcessorIdleRatio, Some(0.7104))
    assert(sample.requestHandlerIdleRatio.exists(ratio => ratio >= 0.0 && ratio <= 1.0))
  }

  test("the broker and controller handler pools are not read as the request handler pool") {
    // Three attributes whose names contain each other, published together on every broker. A substring
    // match reads whichever comes first, and in both bodies the decoys are written first — in the capture
    // because that is the order the broker really published them, and in the hand-written fixture because
    // an anchor whose decoys come last is an anchor nothing can make fail.
    //
    // The two bodies exercise two different halves of `isAttribute`. The capture spells the attribute in a
    // `name` label, which is compared whole, so the anchor is not what saves it; the lowercased fixture
    // spells it inside the metric name, which is where the `_` anchor is the only thing between
    // `requesthandleravgidlepercent` and the broker pool's 1.0006.
    val samples = PrometheusExposition.parse(lowercased)
    val decoyIsFirst =
      samples.indexWhere(_.name.contains("brokerrequesthandleravgidlepercent")) <
        samples.indexWhere(_.name == "kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate")

    assert(decoyIsFirst, "the fixture must put the decoy pools first or this case gates nothing")
    assertEquals(sampleOf(lowercased).requestHandlerIdleRatio, Some(0.8912))
    assertEquals(sampleOf(captured).requestHandlerIdleRatio, Some(0.9993945459789384))
    assert(PrometheusExposition.parse(captured).exists(_.name.contains("kafkarequesthandlerpool")))
  }

  test("a body carrying only the broker pool reports no request-handler idle ratio") {
    // The decoy alone, so the rule holds whatever order an exporter publishes in. `BrokerRequestHandler`
    // is a different pool doing different work: reported as the request handler's idle ratio it would draw
    // a saturated broker as idle, or the reverse, with nothing on the card able to tell.
    val decoyOnly =
      """kafka_server_kafkarequesthandlerpool_brokerrequesthandleravgidlepercent_oneminuterate 1.0006
        |kafka_network_socketserver_networkprocessoravgidlepercent 0.7104
        |""".stripMargin

    val sample = sampleOf(decoyOnly)
    assertEquals(sample.requestHandlerIdleRatio, None)
    assertEquals(sample.networkProcessorIdleRatio, Some(0.7104))
  }

  test("an idle ratio carrying a dimension is a slice too, and neither gauge reads one") {
    // **The rule this packet owns**, and the third copy of the no-double-count guard in this file. The
    // other two have cases — `aggregateOf`'s reddens seven and `purgatoryOf`'s reddens one — while
    // `ratioOf`'s, which feeds both saturation gauges on the brokers screen, reddened nothing: relaxing
    // `dimensionsOf(sample).isEmpty` to `dimensionsOf(sample).sizeIs < 2` left every metrics task SUCCESS.
    //
    // These two families carry no dimension on any exporter shipping today, which is exactly why the
    // fixture is written out rather than captured: the rule is a bound on what a *future* broker or rule
    // file may publish. Kafka's own pools are already sliced elsewhere — a per-handler or per-processor
    // line is the obvious next dimension — and under the relaxed guard one processor's 1% idle would be
    // drawn as the whole pool's, which is a saturated broker reported as a healthy one on the gauge an
    // operator looks at first.
    //
    // The slices are written **before** the aggregates on purpose. `ratioOf` takes the first line that
    // matches, so a fixture whose aggregate came first would answer correctly under any guard at all and
    // gate nothing — the mistake the `aggregateOf` case beside it names out loud.
    val sliced =
      """kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate{handler="7"} 0.1204
        |kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate 0.8912
        |kafka_network_socketserver_networkprocessoravgidlepercent{networkProcessor="3"} 0.0102
        |kafka_network_socketserver_networkprocessoravgidlepercent 0.7104
        |""".stripMargin

    val sample = sampleOf(sliced)

    assertEquals(sample.requestHandlerIdleRatio, Some(0.8912))
    assertEquals(sample.networkProcessorIdleRatio, Some(0.7104))
  }

  test("a cumulative count is not the one-minute rate, whichever view the exporter printed first") {
    // The other half of the same three-line `find` predicate. W8-02 closed `dimensionsOf(sample).isEmpty`
    // on the line below and `rated`'s own suffix requirement, one line above it, reddened nothing:
    // relaxing `endsWith(RateSuffix)` to `nonEmpty` left every metrics case SUCCESS.
    //
    // `rated = true` is passed for both saturation gauges, and the flag exists because a JMX exporter
    // publishes several views of one meter under names that differ only in suffix — `_count`, `_total`,
    // `_meanrate`, `_oneminuterate`. `_count` here is `88123`, a cumulative tally of requests handled
    // since the broker started. Drawn on a 0..1 ring it is not a wrong ratio, it is not a ratio at all:
    // the arc pins at full and stays there for the life of the broker, and a reader is told a saturated
    // pool is idle. The refusal direction below is the same rule with nothing to fall back to.
    //
    // The non-rate views are written **first** on purpose, for the reason the dimension case above states:
    // `ratioOf` answers with the first line that matches, so a fixture whose `_oneminuterate` came first
    // would be green under any guard and gate nothing.
    //
    // Only the request-handler family carries the decoys. `networkProcessorIdleRatio` is read with
    // `rated = false` — the socket server's gauge is published unsuffixed by every exporter shipping
    // today — so a `_count` line under *that* family is a different argument, and putting one here would
    // make this case fail against correct code rather than against the mutation it is aimed at. It rides
    // along undecorated to prove the mutation is confined to the rated reader.
    val countFirst =
      """kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_count 88123
        |kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_meanrate 0.4410
        |kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate 0.8912
        |kafka_network_socketserver_networkprocessoravgidlepercent 0.7104
        |""".stripMargin

    val sample = sampleOf(countFirst)

    assertEquals(sample.requestHandlerIdleRatio, Some(0.8912))
    assertEquals(sample.networkProcessorIdleRatio, Some(0.7104))

    // And the refusal: a body carrying only the cumulative view has no rate to read at all, so the gauge
    // is absent and §3.14 draws its dash. This is the failure above with no correct line beside it that
    // a suffix-blind `find` could have stumbled onto by luck of ordering.
    val countOnly =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 124800.5
        |kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_count 88123
        |""".stripMargin

    val counted = sampleOf(countOnly)

    assertEquals(counted.bytesInPerSecond, Some(124800.5))
    assertEquals(counted.requestHandlerIdleRatio, None)
  }

  test("a broker that published only a per-slice idle ratio has no broker-wide one to draw") {
    // The other direction, and the one that makes the rule a refusal rather than a preference. With no
    // undimensioned line there is no pool figure, and §3.14's *Absent* paragraph says an unmeasured ring
    // gauge draws a dash. Answering the slice would be a measured number about the wrong thing.
    //
    // A throughput line rides along so that the body is a readable exposition: a sample with nothing but
    // `None`s in it is `brokerSampleAt`'s `Left`, and this case is about the two ratios rather than about
    // that refusal.
    val sliceOnly =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 124800.5
        |kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate{handler="7"} 0.1204
        |kafka_network_socketserver_networkprocessoravgidlepercent{networkProcessor="3"} 0.0102
        |""".stripMargin

    val sample = sampleOf(sliceOnly)

    assertEquals(sample.bytesInPerSecond, Some(124800.5))
    assertEquals(sample.requestHandlerIdleRatio, None)
    assertEquals(sample.networkProcessorIdleRatio, None)
  }

  test("purgatory arrives as a count per delayed operation, and no percentage exists to read") {
    // ADR-052's first refusal, asserted against the capture it was decided from. The design draws
    // "38% PURGATORY"; what a broker publishes is 481 parked Fetch requests and no ceiling to divide by.
    val sample = sampleOf(captured)

    assertEquals(sample.purgatory, List(PurgatoryQueue("Fetch", 481L), PurgatoryQueue("Produce", 0L)))
    assert(
      !PrometheusExposition.parse(captured).exists(_.name.toLowerCase.contains("purgatorypercent")),
      "a purgatory percentage would change ADR-052's decision, so its absence is asserted"
    )
  }

  test("a purgatory line carrying a second dimension is a slice too, and is skipped") {
    // The twin of the throughput rule two sections up, and it had no case of its own. `delayedOperation`
    // is the only dimension this family carries today; a line that carries another one is narrower than
    // the queue length the card draws, and admitting it would put two rows called `Fetch` on one card
    // carrying different numbers — which is the defect the `NumDelayedOperations` case below exists for,
    // arrived at from the other direction.
    //
    // Written out rather than taken from a capture, because no exporter serves this line today. That is
    // the point: the rule is a bound on what a *future* ruleset may add, and a parser that accepted any
    // dimensioned line would start summing slices into a broker-wide figure with nothing failing.
    val family = "kafka_server_delayedoperationpurgatory_value"
    val dimensioned =
      s"""$family{delayedoperation="Fetch",name="PurgatorySize"} 481.0
         |$family{delayedoperation="Fetch",broker="1",name="PurgatorySize"} 7.0
         |""".stripMargin

    assertEquals(sampleOf(dimensioned).purgatory, List(PurgatoryQueue("Fetch", 481L)))
  }

  test("purgatory queues arrive in the broker's own name order and not in the exposition's") {
    // `purgatoryOf`'s scaladoc, one line above the `.sortBy(_.operation)` it describes: *"Sorted rather
    // than left in document order, so that two scrapes of the same broker put the same queue in the same
    // place on a card."* Deleting that sort left every metrics case SUCCESS — the same rule W8-02 spent
    // its item 6 closing three copies of one service over, in the same pair of services it was auditing.
    //
    // A JMX exporter walks its MBean server; that iteration order is not a promise and does change
    // between scrapes. The purgatory rows on a broker card are a short list an operator reads by
    // position, and a list that reorders under the pointer between two polls is read as a *different*
    // list — the reader concludes the broker's work changed shape when only the map did.
    //
    // Three operations, in an order that is neither the sorted one nor its reverse: the document reads
    // `Fetch, Produce, DeleteRecords`, sorted reads `DeleteRecords, Fetch, Produce` and the document
    // reversed reads `DeleteRecords, Produce, Fetch`. Two entries would not do it — with two, `reverse`
    // and `sortBy` can agree, which is the fixture defect that let three orderings ship ungated in
    // `ConnectorsSuite` and is recorded there by name.
    val family = "kafka_server_delayedoperationpurgatory_value"
    val unordered =
      s"""$family{delayedoperation="Fetch",name="PurgatorySize"} 481.0
         |$family{delayedoperation="Produce",name="PurgatorySize"} 0.0
         |$family{delayedoperation="DeleteRecords",name="PurgatorySize"} 12.0
         |""".stripMargin

    val operations = sampleOf(unordered).purgatory.map(_.operation)

    // Two positions rather than the whole list, so this is a statement about *order* and not about which
    // queues the fixture happens to name; the whole-list assertion beside it then pins the values too.
    assert(
      operations.indexOf("DeleteRecords") < operations.indexOf("Fetch"),
      s"the exposition's own order survived into the card's rows: $operations"
    )
    assertEquals(operations, operations.sorted)
    assertEquals(
      sampleOf(unordered).purgatory,
      List(PurgatoryQueue("DeleteRecords", 12L), PurgatoryQueue("Fetch", 481L), PurgatoryQueue("Produce", 0L))
    )
  }

  test("the same purgatory queue is not read twice from its NumDelayedOperations sibling") {
    // Both attributes live under one MBean with one `delayedOperation` key. Reading both would put two
    // rows called `Fetch` on one card, carrying different numbers.
    assertEquals(sampleOf(captured).purgatory.count(_.operation == "Fetch"), 1)
  }

  test("top producers are topics, and the busiest one is the aggregate's own topic") {
    // ADR-052's second refusal. There is no per-client.id rate to read, and the per-topic rate that does
    // exist travels under a field called `topic`.
    val topics = sampleOf(captured).topicBytesInPerSecond.getOrElse(fail("no per-topic family"))

    assertEquals(topics.map(_.topic), List("__consumer_offsets", "analytics.pageviews", "orders.v1"))
    assertEquals(topics.find(_.topic == "__consumer_offsets").map(_.bytesInPerSecond), Some(156.9725527287428))
    assert(
      !PrometheusExposition.parse(captured).exists(sample => sample.labels.keySet.exists(_.contains("client"))),
      "a per-client.id dimension would change ADR-052's decision, so its absence is asserted"
    )
  }

  test("a family the exporter does not serve is absent rather than zero") {
    // One dead family costs one card and nothing else. An exporter whitelisting only the byte rates is an
    // ordinary configuration, and every other figure has to come back as "not measured" rather than as a
    // set of zeroes that would draw a saturated broker and an empty top-producers list.
    val trimmed =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 10.0
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 20.0
        |""".stripMargin

    val sample = sampleOf(trimmed)
    assertEquals(sample.bytesInPerSecond, Some(10.0))
    assertEquals(sample.recordsPerSecond, None)
    assertEquals(sample.produceP99Millis, None)
    assertEquals(sample.fetchP99Millis, None)
    assertEquals(sample.requestHandlerIdleRatio, None)
    assertEquals(sample.networkProcessorIdleRatio, None)
    assertEquals(sample.purgatory, Nil)
    // The family was served and carries no topic line, which is not the same as the family being absent —
    // the top-producers endpoint says something different about each. Which of the two causes of an empty
    // list applies is not decided here, because an exposition does not carry it.
    assertEquals(sample.topicBytesInPerSecond, Some(Nil))
  }

  test("a body with no bytes-in family at all reports the per-topic family as absent, not empty") {
    val noBytesIn = "kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate 5.0\n"

    assertEquals(sampleOf(noBytesIn).topicBytesInPerSecond, None)
  }

  // -----------------------------------------------------------------------------------------------
  // Both naming schemes, and the values that are not measurements
  // -----------------------------------------------------------------------------------------------

  test("the same body under the exporter's other naming scheme reads identically") {
    // An operator who runs the exporter with no rule file gets `name="BytesInPerSec"` as a label instead
    // of as part of the metric name, and label keys that keep their camel case with it. Refusing that
    // deployment would be refusing the exporter's default.
    assertEquals(
      PrometheusExposition.brokerSampleAt(at, mbeanNames),
      PrometheusExposition.brokerSampleAt(at, lowercased)
    )
  }

  test("families it does not know are ignored rather than failing the body") {
    val samples = PrometheusExposition.parse(lowercased)

    assert(samples.exists(_.name.startsWith("jvm_memory")), "an unknown family should still parse")
    assert(samples.exists(_.name.startsWith("kafka_controller")), "an unknown family should still parse")
    assert(PrometheusExposition.brokerSampleAt(at, lowercased).isRight)
  }

  test("a NaN is not a measurement and does not become one") {
    // A JMX attribute nobody has populated since the broker started reads as NaN. It is legal exposition
    // and it is not a number: charting it is the fabricated figure this product exists not to draw.
    assert(!PrometheusExposition.parse(lowercased).exists(_.value.isNaN))
  }

  test("an exposition line of +Inf is not a sample") {
    // An exporter that could not read a counter serves `+Inf`, which is legal exposition and is not a
    // measurement. Filed as one it becomes `Infinity` on a card and `null` after a JSON round trip, by a
    // route nobody chose. The body puts it on a family KUI reads so that the guard is load-bearing.
    //
    // It is refused one step earlier than one might expect and the distinction is worth writing down:
    // `java.lang.Double.parseDouble` accepts `Infinity` and rejects `Inf`, so the exposition format's own
    // spelling never reaches the `isInfinite` guard at all — `toDoubleOption` has already said no. The
    // case below is what does reach it.
    val infinite =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate +Inf
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate 20.0
        |""".stripMargin

    assertEquals(sampleOf(infinite).bytesInPerSecond, None)
    assertEquals(sampleOf(infinite).bytesOutPerSecond, Some(20.0))
    assert(!PrometheusExposition.parse(infinite).exists(_.value.isInfinite))
  }

  test("a value too large for a double is not a sample either") {
    // The line that actually reaches the infinity guard. A counter rendered with too many digits — which
    // is what an exporter does with a `BigInteger` attribute, and what `1e400` is here — parses to
    // `Infinity` rather than failing, and `Infinity` on the wire is `null` after a JSON round trip: a gap
    // KUI never measured, from a line KUI did read. Both spellings of "this is not a number" have to end
    // at the same place.
    val overflowing =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate 1e400
        |kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate -1e400
        |kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate 20.0
        |""".stripMargin

    assertEquals(sampleOf(overflowing).bytesInPerSecond, None)
    assertEquals(sampleOf(overflowing).bytesOutPerSecond, None)
    assertEquals(sampleOf(overflowing).recordsPerSecond, Some(20.0))
    assert(!PrometheusExposition.parse(overflowing).exists(_.value.isInfinite))
  }

  test("a body that cannot be parsed is a Left and no sample") {
    // Not an exposition at all — an ingress serving its own error page, which is what a mistyped port
    // usually produces.
    val html = PrometheusExposition.brokerSampleAt(at, "<html><body>502 Bad Gateway</body></html>")

    assert(html.isLeft, s"expected a refusal, got $html")
    assert(html.left.exists(_.contains("no Prometheus samples")), html.toString)
  }

  test("a valid exposition with no Kafka families is refused by name, not read as zero") {
    // The node exporter, reached by copying the wrong port out of a compose file. It parses perfectly and
    // says nothing about a broker, and the wrong answer here is a chart of zeroes.
    val other = PrometheusExposition.brokerSampleAt(at, body("no-kafka-families.txt"))

    assert(other.isLeft, s"expected a refusal, got $other")
    assert(other.left.exists(_.contains("whitelist")), other.toString)
  }

  test("an exporter publishing only the request percentiles is measured, not refused") {
    // The refusal is about a body carrying nothing at all, never about a body carrying a family KUI reads
    // and not the one it happened to look at first. A deployment whose whitelist has RequestMetrics and
    // no BrokerTopicMetrics gets a latency card and a throughput card that says it has nothing.
    val percentilesOnly =
      """kafka_network_requestmetrics_totaltimems_99thpercentile{request="Produce",} 3.5
        |""".stripMargin

    val sample = sampleOf(percentilesOnly)
    assertEquals(sample.produceP99Millis, Some(3.5))
    assertEquals(sample.bytesInPerSecond, None)
    assertEquals(sample.topicBytesInPerSecond, None)
  }

  test("the captured body carries no record-size distribution to read") {
    // ADR-052's third refusal, asserted against the evidence. The full capture is 12,993 lines and has no
    // histogram of message size under any name; the trimmed fixture keeps that true of what it kept.
    val samples = PrometheusExposition.parse(captured)

    assert(!samples.exists(_.name.contains("_bucket")), samples.map(_.name).toString)
    assert(!samples.exists(_.name.toLowerCase.contains("recordsize")), samples.map(_.name).toString)
  }

  test("a topic slice is not read as the broker-wide rate even when it is the only line") {
    // The failure this guards is a quiet broker: `__consumer_offsets` alone carries traffic and its slice
    // is the only `BytesInPerSec` line with a number in it. Read as the aggregate it would put one
    // internal topic's rate on the cluster's throughput chart.
    val sliceOnly =
      """kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate{topic="__consumer_offsets",} 157.0
        |""".stripMargin

    val sample = sampleOf(sliceOnly)
    assertEquals(sample.bytesInPerSecond, None)
    assertEquals(sample.topicBytesInPerSecond, Some(List(TopicProducer("__consumer_offsets", 157.0))))
  }
}
