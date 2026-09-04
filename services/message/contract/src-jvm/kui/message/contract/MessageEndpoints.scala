package kui.message.contract

import java.nio.charset.StandardCharsets

import cats.data.NonEmptySet
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.*

import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.browse.{Direction, IsolationLevel, SeekMode}
import kui.kernel.serde.SerdeName
import kui.kernel.{ClusterId, PartitionId, TopicName}
import kui.security.SignedPrincipal

/** Everything one browse asks for, as one value.
  *
  * A case class rather than a fourteen-element tuple because these parameters are read together, passed
  * together and validated together, and because a tuple of that width is a shape in which two `Option`s of
  * the same type can be swapped by a refactor with nothing failing to compile.
  *
  * @param live
  *   tail mode: start at the end and keep the stream open. It has no default of its own here — the domain
  *   refuses a live browse that also names a start position, which is a rule about the *request* and so
  *   belongs there rather than in a codec
  */
final case class BrowseStreamParams(
    cluster: ClusterId,
    topic: TopicName,
    seek: Option[SeekMode],
    direction: Option[Direction],
    partitions: Option[NonEmptySet[PartitionId]],
    limit: Option[Int],
    isolation: Option[IsolationLevel],
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName],
    stringFilter: Option[String],
    live: Option[Boolean],
    /** The signed continuation of a previous browse. Exclusive with `seek`: it *is* a start position. */
    cursor: Option[String]
)

/** The message service's addresses.
  *
  * ==Why this is in `api` and not in `contract`==
  *
  * This file sits in `src-jvm`, so it is part of the contract on the server and absent from the browser
  * build. Describing an event-stream body needs `fs2` and a stream capability, and the Scala.js half has no
  * use for either: a browser opens this address with `EventSource`, which takes a URL, not a Tapir endpoint.
  *
  * What the browser *does* share is the thing that could silently drift — the documents *inside* the stream,
  * which are this module's own `MessageDto`, `ConsumedDto` and `PhaseDto`, compiled into both halves from one
  * file. The seam that has ever caused a defect in this project is the payload, not the path.
  */
object MessageEndpoints {

  // Every segment and parameter name is `BrowseAddress`', next door in this module's shared sources, and
  // none is typed here. This file is JVM-only — its output is a server-sent-event body, which needs fs2 and
  // does not link for Scala.js — so the browser cannot see these declarations and builds the browse URL
  // itself. A name spelled in both places would let a rename compile on both sides and then 404 or 400 at
  // runtime with every suite green, which is M1's defect exactly. There is one spelling, in the half both
  // platforms compile, and this re-exports it so that every existing use here reads unchanged.
  export BrowseAddress.{
    ClustersSegment,
    TopicsSegment,
    MessagesSegment,
    StreamSegment,
    ClusterIdParam,
    TopicNameParam,
    SeekParam,
    PartitionParam,
    DirectionParam,
    LimitParam,
    IsolationParam,
    KeySerdeParam,
    ValueSerdeParam,
    QueryParam,
    LiveParam,
    CursorParam
  }

  /** `FORWARD`/`BACKWARD` and `READ_COMMITTED`/`READ_UNCOMMITTED`, parsed by the contract's own codecs so
    * that this endpoint and the page endpoint cannot disagree about what a direction is.
    */
  private given sttp.tapir.Codec[String, Direction, sttp.tapir.CodecFormat.TextPlain] =
    BrowseParams.directionCodec

  private given sttp.tapir.Codec[String, IsolationLevel, sttp.tapir.CodecFormat.TextPlain] =
    BrowseParams.isolationCodec

  /** A serde name, validated at the edge.
    *
    * A name that could never have been minted is refused here, with the parameter named, rather than
    * travelling three layers down to become a "serde not configured" that reads like a deployment problem.
    */
  private given sttp.tapir.Codec[String, SerdeName, sttp.tapir.CodecFormat.TextPlain] =
    sttp.tapir.Codec.string.mapDecode(raw =>
      SerdeName.fromString(raw) match {
        case Right(name) => sttp.tapir.DecodeResult.Value(name)
        case Left(why) => sttp.tapir.DecodeResult.Error(raw, new IllegalArgumentException(why))
      }
    )(_.value)

  private val base = "internal" / "v1" / ClustersSegment

  private val clusterIdPath: EndpointInput[ClusterId] =
    path[ClusterId](ClusterIdParam).description("The configured cluster's slug id")

  private val topicNamePath: EndpointInput[TopicName] =
    path[TopicName](TopicNameParam).description("The topic's name, as Kafka spells it")

  /** The browse parameters, parsed by the codecs `services/message/contract` publishes.
    *
    * The parsing is the contract's and not this file's on purpose: the page endpoint accepts the same seek
    * and the same partition list, and a page that started somewhere the stream would not is a page whose
    * "load more" moves the user sideways.
    */
  private val browseQuery: EndpointInput[
    (
        Option[SeekMode],
        Option[Direction],
        Option[NonEmptySet[PartitionId]],
        Option[Int],
        Option[IsolationLevel],
        Option[SerdeName],
        Option[SerdeName],
        Option[String],
        Option[Boolean],
        Option[String]
    )
  ] =
    query[Option[SeekMode]](SeekParam)(using BrowseParams.optionalSeekModeCodec)
      .description(
        "Where to start: 'beginning', 'latest', 'offset::<n>', 'timestamp::<millis>', or one or more " +
          "'<partition>::<offset>' pairs. The forms do not mix."
      )
      .and(
        query[Option[Direction]](DirectionParam)
          .description("FORWARD or BACKWARD. Absent means whichever the seek implies.")
      )
      .and(
        query[Option[NonEmptySet[PartitionId]]](PartitionParam)(using BrowseParams.partitionsCodec)
          .description("Which partitions to read. Absent means all of them.")
      )
      .and(query[Option[Int]](LimitParam).description("How many records to deliver, clamped to the ceiling"))
      .and(query[Option[IsolationLevel]](IsolationParam).description("READ_UNCOMMITTED or READ_COMMITTED"))
      .and(query[Option[SerdeName]](KeySerdeParam).description("The serde to read keys with"))
      .and(query[Option[SerdeName]](ValueSerdeParam).description("The serde to read values with"))
      .and(query[Option[String]](QueryParam).description("A plain substring the decoded record must contain"))
      .and(query[Option[Boolean]](LiveParam).description("Tail: start at the end and stay open"))
      .and(
        query[Option[String]](CursorParam)
          .description(
            "The signed cursor a finished browse ended with. Carry on from where that one stopped. " +
              "Refused together with seekTo, because a cursor is itself a start position"
          )
      )

  /** The event-stream body, declared here rather than taken from `libs/http`.
    *
    * It is the same body `kui.http.sse.Sse.body` produces — `text/event-stream`, UTF-8, framing left to the
    * caller — written out because a contract module may not depend on `libs/http`, which is a wire module a
    * service's published shape has no business reaching into. Two lines of Tapir is a cheaper price than that
    * edge, and `SseSuite` pins the bytes on the other side of it.
    */
  private def sseBody[F[_]] =
    streamTextBody(sttp.capabilities.fs2.Fs2Streams[F])(
      CodecFormat.TextEventStream(),
      Some(StandardCharsets.UTF_8)
    )

  /** One browse, as a stream of server-sent events.
    *
    * The named events are ADR-035's: `phase` while it is getting ready, `message` per record, `consumed` for
    * progress, `heartbeat` while it is quiet, and exactly one terminal `done` or `error`.
    */
  def browseStream[F[_]]
      : Endpoint[SignedPrincipal, BrowseStreamParams, ErrorEnvelope, Stream[F, Byte], Fs2Streams[F]] =
    KuiEndpoint.internal.get
      .in(base / clusterIdPath / TopicsSegment / topicNamePath / MessagesSegment / StreamSegment)
      .in(browseQuery)
      .out(sseBody[F])
      .mapIn(intoParams)(fromParams)
      .name("message.browse.stream")
      .summary("Browse a topic's records as a stream")
      .description(
        "Records arrive as they are read; nothing is buffered into a page first. A record no serde " +
          "could decode is still delivered, through the fallback serde, with the failure attached to it — " +
          "a browse never fails because of one unreadable record. Closing the stream closes the Kafka " +
          "consumer behind it."
      )
      .tag("message")

  /** Tapir flattens `path.and(path).and(query)…` into one wide tuple; this is the only place that width is
    * ever written down, and it is written down twice — once each way — so that a parameter added to the query
    * and forgotten here fails to compile rather than arriving as the wrong field.
    */
  private type Flat = (
      ClusterId,
      TopicName,
      Option[SeekMode],
      Option[Direction],
      Option[NonEmptySet[PartitionId]],
      Option[Int],
      Option[IsolationLevel],
      Option[SerdeName],
      Option[SerdeName],
      Option[String],
      Option[Boolean],
      Option[String]
  )

  private def intoParams(raw: Flat): BrowseStreamParams =
    BrowseStreamParams(
      cluster = raw._1,
      topic = raw._2,
      seek = raw._3,
      direction = raw._4,
      partitions = raw._5,
      limit = raw._6,
      isolation = raw._7,
      keySerde = raw._8,
      valueSerde = raw._9,
      stringFilter = raw._10,
      live = raw._11,
      cursor = raw._12
    )

  private def fromParams(params: BrowseStreamParams): Flat =
    (
      params.cluster,
      params.topic,
      params.seek,
      params.direction,
      params.partitions,
      params.limit,
      params.isolation,
      params.keySerde,
      params.valueSerde,
      params.stringFilter,
      params.live,
      params.cursor
    )

  /** The public path this endpoint answers on, once the gateway has rewritten the prefix.
    *
    * It is written here, beside the endpoint it describes, because the gateway needs it in order to declare
    * its own half of the hop and has no way to derive it from a value it cannot see.
    */
  def publicPath(cluster: String, topic: String): String =
    s"/api/v1/$ClustersSegment/$cluster/$TopicsSegment/$topic/$MessagesSegment/$StreamSegment"
}
