package kui.ui.messages

import munit.FunSuite

import kui.contracts.PublicApi
import kui.kernel.browse.SeekMode
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, Offset, PartitionId, TopicName}
import kui.message.contract.{BrowseAddress, GoldenDocuments}
import kui.ui.kernel.sse.SseError
import kui.ui.messages.browse.{BrowseEvent, BrowseQuery, BrowseStream}

/** The browser half of the seam, for a stream.
  *
  * ## Why this suite matters more here than anywhere else
  *
  * Every other screen in KUI calls a Tapir endpoint value, so its URL and its response type are the server's
  * own by construction and cannot drift. A browse cannot: its response is a stream of server-sent events,
  * whose description needs `fs2` and does not cross-compile, so the URL is *built* rather than derived.
  *
  * That is exactly the shape of failure this project has already shipped once — M1's dashboard decoded a
  * document nobody sent, with every suite on both sides green, because each held its own idea of the payload.
  * So the URL is built from `BrowseAddress`, which both halves compile, and this suite asserts the result
  * against those same constants; and the events are decoded from the contract's *committed* sample documents
  * rather than from copies of them, so a field renamed on the server fails here rather than in production.
  */
final class BrowseWireSuite extends FunSuite {

  private val root = "https://kui.example"
  private val cluster = ClusterId.from("prod-eu").getOrElse(fail("`prod-eu` should be a legal cluster id"))
  private val topic = TopicName.from("orders").getOrElse(fail("`orders` should be a legal topic name"))

  private def urlFor(query: BrowseQuery): String = BrowseStream.url(root, cluster, topic, query)

  // --- The smart filter -------------------------------------------------------------------------

  test("aSmartFilterTravelsAsItsIdAndItsSource") {
    // Both, always together (ADR-017). The id is the short handle a URL can carry; the source is what lets
    // a replica that has never seen the id compile it instead of telling the user their filter expired,
    // and what puts the expression back in the editor for whoever opens the link.
    val query = BrowseQuery.Default.copy(
      filterId = Some("0123456789abcdef"),
      filterSource = Some("record.value.status == 'FAILED'")
    )

    val url = urlFor(query)

    assert(url.contains(s"${BrowseAddress.FilterIdParam}=0123456789abcdef"), url)
    assert(url.contains(s"${BrowseAddress.FilterSourceParam}="), url)
    // Percent-encoded, because an expression contains spaces, quotes and equals signs, every one of which
    // would otherwise end the parameter early and send the server half a program.
    assert(!url.contains("record.value.status == "), url)
  }

  test("aBrowseWithNoSmartFilterSendsNeitherParameter") {
    val url = urlFor(BrowseQuery.Default)

    assert(!url.contains(BrowseAddress.FilterIdParam), url)
    assert(!url.contains(BrowseAddress.FilterSourceParam), url)
  }

  test("aSmartFilterSurvivesTheRoundTripThroughAUrl") {
    val expression = "record.value.status == 'FAILED' && record.headers['x'] == 'y'"

    val read = BrowseQuery.fromParams(
      Map(
        BrowseAddress.FilterIdParam -> List("0123456789abcdef"),
        BrowseAddress.FilterSourceParam -> List(expression)
      )
    )

    assertEquals(read.filterId, Some("0123456789abcdef"))
    assertEquals(read.filterSource, Some(expression))
  }

  test("aSmartFilterIsStillSentAlongsideACursor") {
    // Unlike the seek, which a cursor replaces. A cursor names a position; which records at that position
    // are worth delivering is still the filter's decision, and dropping it on "load more" would make the
    // second page wider than the first.
    val query = BrowseQuery.Default.copy(
      filterId = Some("0123456789abcdef"),
      filterSource = Some("record.partition == 0"),
      cursor = Some("cursor.v1.signed")
    )

    val url = urlFor(query)

    assert(url.contains(BrowseAddress.FilterIdParam), url)
    assert(url.contains(BrowseAddress.CursorParam), url)
    assert(!url.contains(BrowseAddress.SeekParam), url)
  }

  // --- The address ------------------------------------------------------------------------------

  test("theBrowseUrlIsBuiltFromTheContractsOwnSegments") {
    val url = urlFor(BrowseQuery.Default)
    val path = url.takeWhile(_ != '?')

    assertEquals(
      path,
      s"$root${PublicApi.Prefix}/${BrowseAddress.ClustersSegment}/${cluster.value}" +
        s"/${BrowseAddress.TopicsSegment}/${topic.value}" +
        s"/${BrowseAddress.MessagesSegment}/${BrowseAddress.StreamSegment}"
    )
  }

  test("theUrlCarriesThePublicPrefixExactlyOnce") {
    // A client based at `apiBase` would append one prefix to another and ask for `/api/v1/api/v1/...`, which
    // is the mistake `Bootstrap.gatewayRoot` exists to prevent and which nothing but a request would reveal.
    val url = urlFor(BrowseQuery.Default)
    assertEquals(url.sliding(PublicApi.Prefix.length).count(_ == PublicApi.Prefix), 1)
  }

  test("eachSeekIsSpelledByTheContractsOwnCodec") {
    def seekIn(mode: SeekMode): String =
      urlFor(BrowseQuery.Default.copy(seek = mode)).dropWhile(_ != '?').drop(1)

    assert(seekIn(SeekMode.Beginning).contains(s"${BrowseAddress.SeekParam}=beginning"), "beginning")
    assert(seekIn(SeekMode.Latest).contains(s"${BrowseAddress.SeekParam}=latest"), "latest")
    // `::` percent-encoded, which is what makes the two-colon separator safe to put in a query string.
    assert(
      seekIn(SeekMode.AtOffset(Offset.unsafe(41284))).contains(s"${BrowseAddress.SeekParam}=offset%3A%3A41284"),
      seekIn(SeekMode.AtOffset(Offset.unsafe(41284)))
    )
    assert(
      seekIn(SeekMode.AtTimestamp(1767225600000L))
        .contains(s"${BrowseAddress.SeekParam}=timestamp%3A%3A1767225600000"),
      seekIn(SeekMode.AtTimestamp(1767225600000L))
    )
  }

  test("aPerPartitionSeekBecomesOneParameterPerPartitionInAStableOrder") {
    // Two URLs that differ only in the order of a set are two things to compare by eye and two cache entries.
    val seek =
      SeekMode.AtOffsets(Map(PartitionId.unsafe(3) -> Offset.unsafe(250), PartitionId.unsafe(0) -> Offset.unsafe(100)))
    val query = urlFor(BrowseQuery.Default.copy(seek = seek)).dropWhile(_ != '?').drop(1)

    assertEquals(
      query.split('&').filter(_.startsWith(BrowseAddress.SeekParam)).toList,
      List(s"${BrowseAddress.SeekParam}=0%3A%3A100", s"${BrowseAddress.SeekParam}=3%3A%3A250")
    )
  }

  test("noSerdeOverrideMeansNoSerdeParameterAtAll") {
    // Absent is what the service already does — it chooses per topic — so spelling out the default would
    // make every browse URL longer and none of them clearer.
    val query = BrowseQuery.queryString(BrowseQuery.Default)
    assert(!query.contains(BrowseAddress.KeySerdeParam), query)
    assert(!query.contains(BrowseAddress.ValueSerdeParam), query)
  }

  test("aSerdeOverrideIsSentUnderTheServicesOwnParameterNames") {
    val query =
      BrowseQuery.queryString(
        BrowseQuery.Default.copy(keySerde = Some(SerdeName.Int64), valueSerde = Some(SerdeName.Json))
      )
    assert(query.contains(s"${BrowseAddress.KeySerdeParam}=Int64"), query)
    assert(query.contains(s"${BrowseAddress.ValueSerdeParam}=Json"), query)
  }

  test("aSerdeNameThatWillNotParseCostsThatOneSettingAndNotTheScreen") {
    // The value came from a link somebody was sent. Dropping it means the service chooses, which is the
    // behaviour with no override at all — a far better answer than a blank page.
    val parsed = BrowseQuery.fromParams(Map(BrowseAddress.KeySerdeParam -> List("1nvalid name")))
    assertEquals(parsed.keySerde, None)
  }

  test("theQueryRoundTripsThroughItsOwnParameters") {
    val query =
      BrowseQuery(
        seek = SeekMode.AtOffset(Offset.unsafe(41284)),
        partitions = List(PartitionId.unsafe(0), PartitionId.unsafe(3)),
        limit = Some(200),
        contains = Some("order-42"),
        keySerde = Some(SerdeName.Int64),
        valueSerde = Some(SerdeName.Json),
        live = false
      )

    val parsed = BrowseQuery.fromParams(parametersOf(BrowseQuery.queryString(query)))

    assertEquals(parsed, query)
  }

  test("anUnreadableSeekFallsBackToTheDefaultRatherThanFailing") {
    // A link can outlive a spelling. Losing one setting is a screen that works; refusing to parse is a blank
    // page for a URL somebody was sent.
    val parsed = BrowseQuery.fromParams(Map(BrowseAddress.SeekParam -> List("halfway")))
    assertEquals(parsed.seek, BrowseQuery.Default.seek)
  }

  test("liveIsAbsentFromTheUrlWhenItIsOff") {
    assert(!urlFor(BrowseQuery.Default).contains(BrowseAddress.LiveParam), urlFor(BrowseQuery.Default))
    assert(urlFor(BrowseQuery.Default.copy(live = true)).contains(s"${BrowseAddress.LiveParam}=true"))
  }

  test("aTopicNameWithASlashIsEncodedRatherThanSplitIntoSegments") {
    val awkward = TopicName.unsafe("a/b")
    val url = BrowseStream.url(root, cluster, awkward, BrowseQuery.Default)
    assert(!url.contains("/a/b/"), url)
    assert(url.contains("a%2Fb"), url)
  }

  // --- The events -------------------------------------------------------------------------------

  test("theRecordedMessageEventDecodesIntoARecord") {
    BrowseStream.decodeEvent(BrowseAddress.Events.Message, GoldenDocuments.message) match {
      case Right(BrowseEvent.Record(record)) =>
        assertEquals(record.offset.value, 41284L)
        assertEquals(record.partition.value, 3)
        assertEquals(record.key.text, "A-1")
        assertEquals(record.headers.get("traceparent").isDefined, true)
        assertEquals(record.deserializeErrors, Nil)
      case other => fail(s"the contract's own record document must decode into a record, not $other")
    }
  }

  test("anUndecodableRecordStillArrivesAndCarriesItsFailure") {
    // The rule the whole screen rests on: a record no serde could read is still delivered, through the
    // fallback serde, with the failure attached. Swallowing it would turn "the producer changed the schema"
    // into "there are no records here".
    BrowseStream.decodeEvent(BrowseAddress.Events.Message, GoldenDocuments.messageWithDecodeError) match {
      case Right(BrowseEvent.Record(record)) =>
        assertEquals(record.value.kind, "binary")
        assertEquals(record.deserializeErrors.map(_.target), List("value"))
        assert(record.deserializeErrors.head.cause.contains("magic byte"), record.deserializeErrors.head.cause)
      case other => fail(s"an undecodable record must still decode as a record, not $other")
    }
  }

  test("theRecordedPhaseAndConsumedEventsDecode") {
    BrowseStream.decodeEvent(BrowseAddress.Events.Phase, GoldenDocuments.phaseEvent) match {
      case Right(BrowseEvent.Phase(phase)) => assert(phase.name.contains("Seeking"), phase.name)
      case other => fail(s"a phase document must decode into a phase, not $other")
    }

    BrowseStream.decodeEvent(BrowseAddress.Events.Consumed, GoldenDocuments.consumedEvent) match {
      case Right(BrowseEvent.Consumed(consumed)) =>
        // `records` is what was read from Kafka, not what was delivered. The gap between this and the rows on
        // screen is the number that tells a user their filter is working rather than the topic being empty.
        assertEquals(consumed.records, 4096L)
        assertEquals(consumed.budget.recordsLeft, 96)
      case other => fail(s"a consumed document must decode into a consumed event, not $other")
    }
  }

  test("aRecordThatDoesNotDecodeIsReportedAndDoesNotThrow") {
    // Reported as a value, because a thrown decode failure would take the subscription with it and the
    // browse would die on one surprising record rather than delivering the nine hundred good ones.
    BrowseStream.decodeEvent(BrowseAddress.Events.Message, """{"partition": 3}""") match {
      case Left(SseError.Decode(event, _)) => assertEquals(event, BrowseAddress.Events.Message)
      case other => fail(s"a truncated record must be reported as a decode failure, not $other")
    }
  }

  test("theSubscriptionNamesOnlyThisStreamsOwnEvents") {
    // `error`, `done` and `heartbeat` are handled by the kernel's transport for every stream in the product,
    // and listing one here would make this browse claim an event it does not own.
    assertEquals(BrowseAddress.Events.browse.toSet.size, 3)
    assert(!BrowseAddress.Events.browse.contains("done"), BrowseAddress.Events.browse.toString)
    assert(!BrowseAddress.Events.browse.contains("error"), BrowseAddress.Events.browse.toString)
    assert(!BrowseAddress.Events.browse.contains("heartbeat"), BrowseAddress.Events.browse.toString)
  }

  /** A query string, back into the repeated-parameter map `BrowseQuery.fromParams` reads. */
  private def parametersOf(queryString: String): Map[String, List[String]] =
    queryString
      .split('&')
      .toList
      .filter(_.nonEmpty)
      .map(_.span(_ != '='))
      .map((name, value) => decode(name) -> decode(value.drop(1)))
      .groupMap((name, _) => name)((_, value) => value)

  private def decode(raw: String): String = {
    val builder = new StringBuilder
    var index = 0
    val bytes = scala.collection.mutable.ArrayBuffer.empty[Byte]

    def flush(): Unit =
      if bytes.nonEmpty then {
        builder.append(new String(bytes.toArray, "UTF-8"))
        bytes.clear()
      }

    while index < raw.length do
      raw.charAt(index) match {
        case '%' if index + 2 < raw.length =>
          bytes += Integer.parseInt(raw.substring(index + 1, index + 3), 16).toByte
          index += 3
        case other =>
          flush()
          builder.append(other)
          index += 1
      }

    flush()
    builder.toString
  }
}
