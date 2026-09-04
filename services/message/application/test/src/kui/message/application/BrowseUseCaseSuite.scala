package kui.message.application

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream

import kui.kernel.browse.{Direction, PollBudget, SeekMode}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.serde.{PayloadKind, SerdeName, SerdeUse, Target}
import kui.kernel.{ClusterId, Offset, PartitionId, Secret, TopicName}
import kui.message.application.cursor.{BrowseCursor, CursorCodec}
import kui.message.domain.ports.{
  BrowseCluster,
  ClusterProfileSource,
  CompiledFilter,
  FilterSample,
  FilterSource,
  FilterVerdict,
  SerdeChoice,
  SerdeSource
}
import kui.message.domain.{BrowseLimits, BrowseRequest, Decoded, DecodedRecord, FilterRef, TimestampType}
import kui.testkit.KuiIOSuite

/** The browse use case's promises: a record that cannot be decoded is still delivered, a browse accounts for
  * what it did, and the cursor it hands back resumes exactly where it stopped.
  *
  * The first is the milestone's central rule and the reason the quickstart seeds a topic of deliberately
  * unparseable payloads. A stream that ended on the first bad record would hide every good record after it,
  * and the bad record is usually the one the screen was opened to find.
  */
final class BrowseUseCaseSuite extends KuiIOSuite {

  private val cluster = ClusterId.unsafe("local")
  private val topic = TopicName.unsafe("audit.log.raw")
  private val budget = PollBudget.unsafe(1000, 1L << 20, 30.seconds)
  private val key = Secret("test-key".getBytes("UTF-8"))

  private val clusters: ClusterProfileSource[IO] = (id: ClusterId) =>
    IO.pure(
      if id == cluster then Right(BrowseCluster(cluster, "Local", readOnly = false, Instant.EPOCH, false))
      else Left(ApplicationError.NotFound("cluster", id.value, ErrorCode.ClusterNotFound))
    )

  /** A serde that reads text and refuses one particular payload.
    *
    * That is what a real serde failure looks like: not a broken cluster, but one record whose producer
    * wrote something the configured decoder does not accept.
    */
  private def serdes(failsOn: String): SerdeSource[IO] = new SerdeSource[IO] {

    def decode(
        cluster: ClusterId,
        topic: TopicName,
        target: Target,
        requested: Option[SerdeName],
        bytes: Option[Array[Byte]]
    ): IO[(Decoded, Option[String])] = IO.pure {
      val text = bytes.fold("")(new String(_, "UTF-8"))

      // The fallback's answer, with the reason the intended serde could not produce one. The record is
      // delivered either way; this is the difference between a row a user can act on and a blank screen.
      if text == failsOn then
        (Decoded(text, PayloadKind.Text, SerdeName.Fallback, Map.empty), Some("not a valid document"))
      else (Decoded(text, PayloadKind.Text, SerdeName.String, Map.empty), None)
    }

    def serialize(
        cluster: ClusterId,
        topic: TopicName,
        target: Target,
        requested: Option[SerdeName],
        properties: Map[String, String],
        text: Option[String]
    ): IO[Either[KuiError, Option[Array[Byte]]]] = IO.pure(Right(None))

    def choices(
        cluster: ClusterId,
        topic: TopicName,
        target: Target,
        use: SerdeUse
    ): IO[Either[KuiError, List[SerdeChoice]]] = IO.pure(Right(Nil))
  }

  private def source(records: List[Either[KuiError, RawRecord]]): RecordSource[IO] =
    (_, _) => Stream.emits(records)

  private def raw(offset: Long, value: String): RawRecord =
    RawRecord(
      partition = PartitionId.unsafe(0),
      offset = Offset.unsafe(offset),
      timestamp = Instant.ofEpochMilli(offset),
      timestampType = TimestampType.CreateTime,
      key = None,
      value = Some(value.getBytes("UTF-8")),
      headers = Nil,
      keySize = 0,
      valueSize = value.length,
      headersSize = 0
    )

  private def request(
      limit: Int,
      filter: Option[String] = None,
      of: ClusterId = cluster,
      live: Boolean = false
  ): BrowseRequest =
    BrowseRequest
      .of(
        cluster = of,
        topic = topic,
        seek = SeekMode.Beginning,
        direction = Some(Direction.Forward),
        partitions = None,
        limit = Some(limit),
        isolation = None,
        keySerde = None,
        valueSerde = None,
        stringFilter = filter,
        filter = None,
        live = live
      )
      .getOrElse(fail("the request under test is not a legal browse"))

  private def useCase(
      records: List[Either[KuiError, RawRecord]],
      failsOn: String = "<nothing fails>",
      filters: FilterSource[IO] = FilterSource.unsupported[IO]
  ): BrowseUseCase[IO] =
    BrowseUseCase.make[IO](
      clusters,
      serdes(failsOn),
      source(records),
      CursorCodec.hmacSha256[IO](key),
      filters
    )

  /** A smart filter that answers by looking at the value's text, so that a test can say which records it
    * keeps without writing an expression the CEL engine would have to compile.
    */
  private def filterOver(verdict: DecodedRecord => FilterVerdict): FilterSource[IO] =
    new FilterSource[IO] {
      def compile(cluster: ClusterId, filter: FilterRef): IO[Either[KuiError, CompiledFilter[IO]]] =
        IO.pure(
          Right(new CompiledFilter[IO] {
            def test(record: DecodedRecord): IO[FilterVerdict] = IO.pure(verdict(record))
          })
        )

      def register(cluster: ClusterId, source: String): IO[Either[KuiError, String]] =
        IO.pure(Right("0123456789abcdef"))

      def check(
          cluster: ClusterId,
          source: String,
          record: FilterSample
      ): IO[Either[KuiError, FilterVerdict]] =
        IO.pure(Right(FilterVerdict.Matched))
    }

  /** A browse that names a smart filter. The id is well-formed because `FilterRef` refuses anything else. */
  private def filtered(limit: Int): BrowseRequest =
    BrowseRequest
      .of(
        cluster = cluster,
        topic = topic,
        seek = SeekMode.Beginning,
        direction = Some(Direction.Forward),
        partitions = None,
        limit = Some(limit),
        isolation = None,
        keySerde = None,
        valueSerde = None,
        stringFilter = None,
        filter = FilterRef.of("0123456789abcdef", Some("record.value.status == 'PAID'")).toOption,
        live = false
      )
      .getOrElse(fail("the request under test is not a legal browse"))

  private def events(browse: BrowseUseCase[IO], of: BrowseRequest): IO[List[BrowseEvent]] =
    browse.browse(of, budget).compile.toList

  private def delivered(events: List[BrowseEvent]): List[String] =
    events.collect { case BrowseEvent.Record(record) => record.value.text }

  // ------------------------------------------------------------------------------ resuming a browse

  private def cursorFor(
      offsets: Map[PartitionId, Offset],
      direction: Direction = Direction.Forward,
      limit: Int = 50
  ): IO[String] =
    CursorCodec
      .hmacSha256[IO](key)
      .encode(
        BrowseCursor(
          v = BrowseCursor.Version,
          cluster = cluster,
          topic = topic,
          direction = direction,
          perPartitionNext = offsets,
          filterId = None,
          keySerde = Some(SerdeName.String),
          valueSerde = None,
          limit = limit,
          isolation = kui.kernel.browse.IsolationLevel.Default,
          expiresAt = Instant.now().plusSeconds(600)
        )
      )
      .map(_.getOrElse(fail("the cursor under test could not be signed")))

  test("a cursor resumes every partition at its own offset") {
    // The reason the seek grammar keeps a per-partition form: a continuation that could only express one
    // offset for every partition could not express what a cursor already means.
    val offsets = Map(PartitionId.unsafe(0) -> Offset.unsafe(100L), PartitionId.unsafe(3) -> Offset.unsafe(250L))

    for {
      cursor <- cursorFor(offsets)
      resumed <- useCase(Nil).resume(cluster, topic, cursor, None, BrowseLimits.Default)
    } yield {
      val request = resumed.getOrElse(fail(s"the cursor did not resume: $resumed"))
      assertEquals(request.seek, SeekMode.AtOffsets(offsets))
      // The subset is the cursor's own keys and not "all of them": a partition added to the topic since the
      // first page has no start position of its own and would arrive from wherever the consumer landed.
      assertEquals(request.partitions.map(_.toSortedSet.toSet), Some(offsets.keySet))
      assertEquals(request.live, false)
    }
  }

  test("the cursor carries the page size, the direction and the serdes so the next page matches the last") {
    val offsets = Map(PartitionId.unsafe(0) -> Offset.unsafe(7L))

    for {
      cursor <- cursorFor(offsets, direction = Direction.Backward, limit = 17)
      resumed <- useCase(Nil).resume(cluster, topic, cursor, None, BrowseLimits.Default)
    } yield {
      val request = resumed.getOrElse(fail(s"the cursor did not resume: $resumed"))
      assertEquals(request.direction, Direction.Backward)
      assertEquals(request.limit, 17)
      assertEquals(request.keySerde, Some(SerdeName.String))
    }
  }

  test("a plain substring filter may change between pages, because it is applied after decoding") {
    val offsets = Map(PartitionId.unsafe(0) -> Offset.unsafe(7L))

    for {
      cursor <- cursorFor(offsets)
      resumed <- useCase(Nil).resume(cluster, topic, cursor, Some("order-42"), BrowseLimits.Default)
    } yield assertEquals(resumed.map(_.stringFilter), Right(Some("order-42")))
  }

  test("a cursor minted for another topic does not resume here") {
    for {
      cursor <- cursorFor(Map(PartitionId.unsafe(0) -> Offset.unsafe(1L)))
      resumed <- useCase(Nil)
        .resume(cluster, TopicName.unsafe("somewhere.else"), cursor, None, BrowseLimits.Default)
    } yield assert(resumed.isLeft, "a cursor for another topic was accepted")
  }

  test("a tampered cursor is refused rather than read as if the change were absent") {
    for {
      cursor <- cursorFor(Map(PartitionId.unsafe(0) -> Offset.unsafe(1L)))
      resumed <- useCase(Nil).resume(cluster, topic, cursor.dropRight(3) + "aaa", None, BrowseLimits.Default)
    } yield assert(resumed.isLeft, "a tampered cursor was accepted")
  }

  // -------------------------------------------------------------------------- decoding never fails

  test("a record that cannot be decoded is delivered anyway, and the stream carries on") {
    val records = List(raw(0, "good"), raw(1, "broken"), raw(2, "also good")).map(_.asRight[KuiError])

    events(useCase(records, failsOn = "broken"), request(10)).map { produced =>
      assertEquals(delivered(produced), List("good", "broken", "also good"))

      val failed = produced.collectFirst { case BrowseEvent.Record(r) if r.decodeErrors.nonEmpty => r }
      assertEquals(failed.map(_.offset.value), Some(1L))
      assertEquals(failed.toList.flatMap(_.decodeErrors).map(_.cause), List("not a valid document"))
      // The serde named on the payload is the one that actually produced the text, the fallback, while
      // the failure names the one the user configured. They answer different questions.
      assertEquals(failed.map(_.value.serde), Some(SerdeName.Fallback))
    }
  }

  test("a stream that fails halfway keeps the records it already delivered") {
    val boom: KuiError = ApplicationError.NotFound("topic", "gone", ErrorCode.TopicNotFound)
    val records = List(raw(0, "first").asRight[KuiError], boom.asLeft[RawRecord], raw(1, "never").asRight)

    events(useCase(records), request(10)).map { produced =>
      assertEquals(delivered(produced), List("first"))
      assertEquals(produced.lastOption, Some(BrowseEvent.Failed(boom)))
      // A failed browse ends with the failure and nothing else: two terminal events would leave a client
      // with no rule about which one it is meant to believe.
      assert(produced.forall {
        case BrowseEvent.Finished(_, _) => false
        case _ => true
      })
    }
  }

  // ------------------------------------------------------------------------------------ the ending

  // ------------------------------------------------------------------------------- the smart filter

  test("a smart filter that will not compile ends the browse before a single record is read") {
    // Before, and not during: an expression with a typo in it must not open a Kafka consumer and read a
    // million records in order to tell the user that nothing matched.
    val refusing = new FilterSource[IO] {
      def compile(cluster: ClusterId, filter: FilterRef): IO[Either[KuiError, CompiledFilter[IO]]] =
        IO.pure(Left(ApplicationError.Invalid("line 1, column 8: undeclared reference to 'staus'", Nil)))

      def register(cluster: ClusterId, source: String): IO[Either[KuiError, String]] =
        IO.pure(Left(ApplicationError.Invalid("nope", Nil)))

      def check(
          cluster: ClusterId,
          source: String,
          record: FilterSample
      ): IO[Either[KuiError, FilterVerdict]] =
        IO.pure(Left(ApplicationError.Invalid("nope", Nil)))
    }

    val records = List(raw(0, "one"), raw(1, "two")).map(_.asRight[KuiError])

    events(useCase(records, filters = refusing), filtered(10)).map { produced =>
      assert(delivered(produced).isEmpty, "records were read despite the filter not compiling")
      assert(produced.exists {
        case BrowseEvent.Failed(_) => true
        case _ => false
      })
    }
  }

  test("records the smart filter rejects are read and not delivered") {
    val records = List(raw(0, "keep"), raw(1, "drop"), raw(2, "keep")).map(_.asRight[KuiError])

    val keeping = filterOver(record =>
      if record.value.text == "keep" then FilterVerdict.Matched else FilterVerdict.DidNotMatch
    )

    events(useCase(records, filters = keeping), filtered(10)).map { produced =>
      assertEquals(delivered(produced), List("keep", "keep"))

      // Three read, two delivered. The gap is what tells a user the filter is doing something, and
      // without it a narrow filter and an empty topic are the same screen.
      val consumed = produced.collect { case event: BrowseEvent.Consumed => event }.last
      assertEquals((consumed.read, consumed.delivered), (3L, 2L))
    }
  }

  test("a record the filter threw on is excluded and counted rather than delivered or fatal") {
    // ADR-017's rule, and the reason `FilterVerdict` has three cases. A filter that errors on every
    // record would otherwise be indistinguishable from a filter that matches nothing, and the user would
    // conclude their data is missing rather than their expression is wrong.
    val records = List(raw(0, "good"), raw(1, "broken"), raw(2, "good")).map(_.asRight[KuiError])

    val throwing = filterOver(record =>
      if record.value.text == "broken" then FilterVerdict.Failed("no such field 'status'")
      else FilterVerdict.Matched
    )

    events(useCase(records, filters = throwing), filtered(10)).map { produced =>
      assertEquals(delivered(produced), List("good", "good"))

      val consumed = produced.collect { case event: BrowseEvent.Consumed => event }.last
      assertEquals(consumed.filterErrors, 1L)
    }
  }

  test("a browse that ran out of records says exhausted and offers no cursor") {
    val records = List(raw(0, "one"), raw(1, "two")).map(_.asRight[KuiError])

    events(useCase(records), request(10)).map(produced =>
      assertEquals(produced.lastOption, Some(BrowseEvent.Finished(BrowseEnd.Exhausted, None)))
    )
  }

  test("a tail delivers past its limit, because a limit is a page size and a tail has no pages") {
    // `limit` bounds a page; a tail is not paged, and the bound that keeps a tail from growing without
    // end is on the screen — the browser keeps the newest rows and drops the rest. A tail that stopped at
    // `limit` would be a Follow control that worked once.
    val records = List(raw(0, "one"), raw(1, "two"), raw(2, "three")).map(_.asRight[KuiError])

    events(useCase(records), request(limit = 1, live = true)).map(produced =>
      assertEquals(delivered(produced), List("one", "two", "three"))
    )
  }

  test("a browse that hit its limit says so and hands back a cursor") {
    val records = List(raw(0, "one"), raw(1, "two"), raw(2, "three")).map(_.asRight[KuiError])

    events(useCase(records), request(2)).map { produced =>
      assertEquals(delivered(produced), List("one", "two"))

      produced.last match {
        case BrowseEvent.Finished(BrowseEnd.Limit, Some(cursor)) => assert(cursor.nonEmpty)
        case other => fail(s"expected a limit ending with a cursor, got $other")
      }
    }
  }

  test("the cursor a forward browse hands back resumes after the last record it delivered") {
    val records = List(raw(0, "one"), raw(1, "two"), raw(2, "three")).map(_.asRight[KuiError])
    val codec = CursorCodec.hmacSha256[IO](key)

    for {
      produced <- events(useCase(records), request(2))
      token = produced.last match {
        case BrowseEvent.Finished(_, Some(cursor)) => cursor
        case other => fail(s"expected a cursor, got $other")
      }
      decoded <- codec.decode(token, (cluster, topic), Instant.EPOCH)
    } yield decoded match {
      // Offset 2 and not 1: the last record delivered was offset 1, and a cursor that resumed there would
      // show it twice. This is the one increment in paging, and BrowseCursor.afterForward owns it.
      case Right(cursor) =>
        assertEquals(cursor.perPartitionNext, Map(PartitionId.unsafe(0) -> Offset.unsafe(2)))
        assertEquals(cursor.v, BrowseCursor.Version)
      case Left(error) => fail(s"the cursor this build minted could not be read back: ${error.message}")
    }
  }

  // --------------------------------------------------------------------------------- the filtering

  test("the string filter matches decoded text, and the accounting shows what it rejected") {
    val records = List(raw(0, "keep me"), raw(1, "drop"), raw(2, "keep this too")).map(_.asRight[KuiError])

    events(useCase(records), request(10, Some("KEEP"))).map { produced =>
      // Case-insensitive, and against the decoded text rather than the raw bytes: a user searching for
      // what they can see on the screen is searching the text, not the payload's encoding.
      assertEquals(delivered(produced), List("keep me", "keep this too"))

      val consumed = produced.collect { case c: BrowseEvent.Consumed => c }.last
      // Three read, two delivered. The gap is the number that tells a user their filter is working, and
      // it is the only thing on the stream that would ever say so.
      assertEquals((consumed.read, consumed.delivered), (3L, 2L))
    }
  }

  // ---------------------------------------------------------------------------- an unknown cluster

  test("a cluster nobody configured fails before the record source is touched") {
    val browse = BrowseUseCase.make[IO](
      clusters,
      serdes("<nothing fails>"),
      (_, _) => Stream.raiseError[IO](new IllegalStateException("the record source must not be reached")),
      CursorCodec.hmacSha256[IO](key),
      FilterSource.unsupported[IO]
    )

    events(browse, request(10, of = ClusterId.unsafe("nowhere"))).map { produced =>
      assert(delivered(produced).isEmpty)
      assert(produced.exists {
        case BrowseEvent.Failed(_) => true
        case _ => false
      })
    }
  }
}
