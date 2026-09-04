package kui.ui.messages.browse

import io.circe.parser.decode

import kui.contracts.PublicApi
import kui.kernel.{ClusterId, TopicName}
import kui.message.contract.{BrowseAddress, ConsumedDto, MessageDto, PhaseDto}
import kui.ui.kernel.sse.{Sse, SseError, SseHandle}

/** One event from a browse, decoded.
  *
  * Three cases, and each one drives something different on the screen: a phase is the status line while
  * nothing has arrived yet, a record is a row, and a consumed figure is what tells a user whose filter
  * matched nothing that KUI read a million records and rejected them all — without which "no results" and
  * "the topic is empty" look identical.
  */
enum BrowseEvent {
  case Phase(phase: PhaseDto)
  case Record(message: MessageDto)
  case Consumed(consumed: ConsumedDto)
}

object BrowseEvent {
  given CanEqual[BrowseEvent, BrowseEvent] = CanEqual.derived
}

/** The browser's end of `GET /api/v1/clusters/{c}/topics/{t}/messages/stream`.
  *
  * ## Why this is not a Tapir client
  *
  * Every other screen in KUI calls an endpoint value and gets a decoded body. This one cannot: the response
  * is a stream of server-sent events, whose Tapir description needs `fs2` and a server-side stream body and
  * therefore cannot cross-compile to Scala.js — which is why `MessageEndpoints` lives in the service's `api`
  * module and says so in as many words.
  *
  * What that leaves is a URL to build and a body to parse, and both are built from the contract module both
  * halves compile: `BrowseAddress` names the segments, the parameters and the events, `BrowseParams` spells
  * the values, and `MessageDto`, `PhaseDto` and `ConsumedDto` are the very types the service encodes. Nothing
  * here declares a field name or a path segment of its own, which is the same protection a generated client
  * would give and the reason a rename on the server breaks this build rather than the product.
  *
  * ## Why `fetchStream` and not `EventSource`
  *
  * Because the user has to be able to stop it. The native `EventSource` cannot be aborted, and a browse the
  * browser has stopped listening to but not cancelled leaves a Kafka consumer open on the service for as long
  * as the budget lasts. `fetchStream`'s `close()` aborts the request, and that cancellation propagates: the
  * gateway's stream is cancelled, the service's fiber is cancelled, the consumer is closed (ADR-035). The
  * screen calls it when the user presses Stop and Laminar calls it when the element unmounts, so navigating
  * away is enough.
  */
object BrowseStream {

  /** The absolute URL of one browse.
    *
    * `apiRoot` is the deployment's own root — origin and base path, with no `/api/v1` on it — because that
    * prefix belongs to the public API and is added here from `PublicApi`, exactly as every generated client
    * gets it from the endpoint value it was built from. A caller that passed a root with the prefix already
    * on it would ask for `/api/v1/api/v1/...`, which is the mistake `Bootstrap.gatewayRoot` exists to stop.
    */
  def url(apiRoot: String, cluster: ClusterId, topic: TopicName, query: BrowseQuery): String = {
    val root = apiRoot.stripSuffix("/") + PublicApi.Prefix
    val path =
      List(
        BrowseAddress.ClustersSegment,
        encodeSegment(cluster.value),
        BrowseAddress.TopicsSegment,
        encodeSegment(topic.value),
        BrowseAddress.MessagesSegment,
        BrowseAddress.StreamSegment
      ).mkString("/")

    val parameters = BrowseQuery.queryString(query)

    if parameters.isEmpty then s"$root/$path" else s"$root/$path?$parameters"
  }

  /** Opens one browse. The caller closes it — by pressing Stop, or by the element unmounting. */
  def open(
      apiRoot: String,
      cluster: ClusterId,
      topic: TopicName,
      query: BrowseQuery
  ): SseHandle[BrowseEvent] =
    Sse.fetchStream(
      Sse.FetchRequest(url = url(apiRoot, cluster, topic, query)),
      BrowseAddress.Events.browse
    )(decodeEvent)

  /** Turns one named event into a value.
    *
    * A decode failure is reported and the stream **continues**. One record whose document this build cannot
    * read must not end a browse: the other nine hundred are still what the user came for, and a stream that
    * died on the first surprise would make a contract skew look like an outage.
    */
  private[messages] def decodeEvent(name: String, data: String): Either[SseError, BrowseEvent] =
    name match {
      case BrowseAddress.Events.Message =>
        decode[MessageDto](data).left
          .map(error =>
            SseError.Decode(BrowseAddress.Events.Message, s"a record did not decode: ${error.getMessage}")
          )
          .map(BrowseEvent.Record.apply)
      case BrowseAddress.Events.Phase =>
        decode[PhaseDto](data).left
          .map(error =>
            SseError.Decode(BrowseAddress.Events.Phase, s"a phase event did not decode: ${error.getMessage}")
          )
          .map(BrowseEvent.Phase.apply)
      case BrowseAddress.Events.Consumed =>
        decode[ConsumedDto](data).left
          .map(error =>
            SseError
              .Decode(BrowseAddress.Events.Consumed, s"a consumed event did not decode: ${error.getMessage}")
          )
          .map(BrowseEvent.Consumed.apply)
      // Unreachable while the subscription lists exactly the three names above, and still answered rather
      // than thrown: ADR-035 lets a stream add an event, and a browser that crashed on one would turn a
      // forward-compatible change into an outage.
      case other => Left(SseError.Decode(other, "this build did not ask for that event"))
    }

  /** A path segment, percent-encoded. A topic name may contain a dot and a dash and nothing else Kafka
    * permits needs escaping — but a cluster id comes from a configuration file and a URL, so neither is
    * pasted in raw.
    */
  private def encodeSegment(raw: String): String =
    raw.flatMap {
      case c if c.isLetterOrDigit => c.toString
      case c @ ('-' | '_' | '.' | '~') => c.toString
      case c => c.toString.getBytes("UTF-8").map(byte => f"%%${byte & 0xff}%02X").mkString
    }
}
