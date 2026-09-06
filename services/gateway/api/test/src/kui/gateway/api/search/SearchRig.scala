package kui.gateway.api.search

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.cluster.contract.dto.ClustersResponse
import kui.consumer.contract.GroupListParams
import kui.consumer.contract.dto.GroupsResponse
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto}
import kui.contracts.consumer.GroupSummaryDto
import kui.contracts.paging.{PageDto, PageInfo}
import kui.contracts.{ErrorEnvelope, Section}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.group.{GroupProtocol, GroupState}
import kui.kernel.{ClusterId, GroupId, ServiceId, Subject, TopicName}
import kui.schema.contract.SubjectListParams
import kui.schema.contract.dto.SubjectSummaryDto
import kui.security.SignedPrincipal
import kui.topic.contract.dto.TopicNamesResponse

/** Four stub services that answer the four endpoints a search folds over, and count every call.
  *
  * The counting is not decoration. The rule this packet ships is "one request per service per cluster, never
  * one per result", and the only way to assert it is to record what was asked. The same recording is what
  * lets a case check that the gateway passed the caller's query *through* to the two services that filter on
  * their own side, rather than fetching everything and narrowing it here.
  *
  * The stubs filter exactly where the real services filter: the consumer and schema services narrow by `q`
  * themselves, and the topic service answers its whole name index because `topics/names` has no `q` at all.
  * Getting that division wrong in the fixture would be the classic false gate — a topic search that passes
  * because the fixture did the matching the gateway was supposed to do.
  */
object SearchRig {

  val At: Instant = Instant.parse("2026-09-03T10:11:12Z")

  val Cluster: ClusterId = ClusterId.unsafe("prod-eu")

  val ClusterService: ServiceId = ServiceId.unsafe("cluster")
  val TopicService: ServiceId = ServiceId.unsafe("topic")
  val ConsumerService: ServiceId = ServiceId.unsafe("consumer")
  val SchemaService: ServiceId = ServiceId.unsafe("schema")

  /** What each stubbed service holds, before any query narrows it. */
  final case class Data(
      clusters: List[ClusterId] = List(Cluster),
      topics: List[String] = Nil,
      groups: List[String] = Nil,
      subjects: List[String] = Nil
  )

  /** How a stubbed service behaves. `Down` is the case the `partial` rule exists for. */
  enum Behaviour {
    case Answers
    case Down
  }

  /** One recorded upstream call: which operation, on which cluster, with which query.
    *
    * A record and not a formatted string, so a case can assert on the count and on the query separately and
    * a failure prints something a reader can act on.
    */
  final case class Call(operation: String, cluster: Option[ClusterId], q: Option[String])

  object Call {
    given CanEqual[Call, Call] = CanEqual.derived
  }

  def client(
      id: ServiceId,
      data: Data,
      calls: Ref[IO, List[Call]],
      behaviour: Behaviour = Behaviour.Answers
  ): ServiceClient[IO] =
    new ServiceClient[IO] {

      val service: ServiceId = id

      def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

      def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] = {
        val operation = endpoint.info.name.getOrElse("<unnamed>")
        record(operation, input) *> answer[O](operation, input)
      }

      private def record(operation: String, input: Any): IO[Unit] =
        calls.update(_ :+ callOf(operation, input))

      private def answer[O](operation: String, input: Any): IO[Either[KuiError, O]] =
        behaviour match {
          case Behaviour.Down =>
            IO.pure(Left(InfrastructureError.Unreachable(id.value, "connection refused")))
          case Behaviour.Answers => IO.pure(Right(body(operation, input).asInstanceOf[O]))
        }

      /** The answer each of the four operations gives, dispatched on the operation id rather than on the
        * input's type: the kernel's identifiers are opaque `String`s, so a type test on one would be
        * unchecked at runtime and would match the wrong tuple half as readily as the right one.
        */
      private def body(operation: String, input: Any): Any = operation match {
        case "cluster.list" => ClustersResponse(data.clusters.map(row), At)
        case "topic.names" => TopicNamesResponse(Section.Ok(data.topics.map(TopicName.unsafe), At))
        case "consumer.list" =>
          val params = input.asInstanceOf[(ClusterId, GroupListParams)]._2
          GroupsResponse(Section.Ok(page(matching(data.groups, params.q, params.pageSize).map(group)), At), 0)
        case "schema.subjects" =>
          val params = input.asInstanceOf[(ClusterId, SubjectListParams)]._2
          page(matching(data.subjects, params.q, params.pageSize).map(subject))
        case other => sys.error(s"the search fold called an endpoint this rig does not stub: $other")
      }

      private def callOf(operation: String, input: Any): Call = operation match {
        case "cluster.list" => Call(operation, None, None)
        case "topic.names" => Call(operation, Some(input.asInstanceOf[ClusterId]), None)
        case "consumer.list" =>
          val (cluster, params) = input.asInstanceOf[(ClusterId, GroupListParams)]
          Call(operation, Some(cluster), params.q)
        case "schema.subjects" =>
          val (cluster, params) = input.asInstanceOf[(ClusterId, SubjectListParams)]
          Call(operation, Some(cluster), params.q)
        case other => Call(other, None, None)
      }

      def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

      def stream[I](
          endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
          input: I
      )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
    }

  /** What a service that filters on its own side would return: a case-insensitive substring match, cut to
    * the page size it was asked for.
    */
  private def matching(names: List[String], q: Option[String], pageSize: Int): List[String] = {
    val needle = q.getOrElse("").toLowerCase(java.util.Locale.ROOT)
    names.filter(_.toLowerCase(java.util.Locale.ROOT).contains(needle)).take(pageSize)
  }

  private def page[A](items: List[A]): PageDto[A] =
    PageDto(items, PageInfo(1, items.size.max(1), Some(items.size.toLong), None))

  private def group(id: String): GroupSummaryDto =
    GroupSummaryDto(
      groupId = GroupId.unsafe(id),
      state = GroupState.Stable,
      protocol = GroupProtocol.Classic,
      isSimple = false,
      members = 1,
      topics = 1,
      partitions = 1,
      coordinatorId = None,
      coordinatorHost = None,
      coordinatorPort = None,
      totalLag = None,
      pace = None,
      excludedPartitions = 0,
      incomplete = None
    )

  private def subject(name: String): SubjectSummaryDto =
    SubjectSummaryDto(Subject.unsafe(name), None, None, None)

  private def row(id: ClusterId): ClusterRowDto =
    ClusterRowDto(
      id = id,
      name = id.value,
      readOnly = false,
      bootstrapServers = "broker-1.example.com:9093",
      security = ClusterSecurityDto("PLAINTEXT", None, false, false),
      summary = Section.NotConfigured
    )
}
