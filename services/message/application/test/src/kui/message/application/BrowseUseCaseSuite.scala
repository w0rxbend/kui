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
import kui.message.domain.ports.{BrowseCluster, ClusterProfileSource, SerdeChoice, SerdeSource}
import kui.message.domain.{BrowseRequest, Decoded, TimestampType}
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
      of: ClusterId = cluster
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
        live = false
      )
      .getOrElse(fail("the request under test is not a legal browse"))

  private def useCase(
      records: List[Either[KuiError, RawRecord]],
      failsOn: String = "<nothing fails>"
  ): BrowseUseCase[IO] =
    BrowseUseCase.make[IO](clusters, serdes(failsOn), source(records), CursorCodec.hmacSha256[IO](key))

  private def events(browse: BrowseUseCase[IO], of: BrowseRequest): IO[List[BrowseEvent]] =
    browse.browse(of, budget).compile.toList

  private def delivered(events: List[BrowseEvent]): List[String] =
    events.collect { case BrowseEvent.Record(record) => record.value.text }

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

  test("a browse that ran out of records says exhausted and offers no cursor") {
    val records = List(raw(0, "one"), raw(1, "two")).map(_.asRight[KuiError])

    events(useCase(records), request(10)).map(produced =>
      assertEquals(produced.lastOption, Some(BrowseEvent.Finished(BrowseEnd.Exhausted, None)))
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
      CursorCodec.hmacSha256[IO](key)
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
