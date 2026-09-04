package kui.gateway.application.topic

import java.time.Instant

import cats.effect.kernel.{Async, Clock, Resource}
import cats.syntax.all.*
import io.circe.Json
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Counter

import kui.contracts.Section
import kui.contracts.capability.ReasonCode
import kui.contracts.topic.TopicDetailDto
import kui.gateway.application.capability.{CapabilitySignals, ReadinessSignal}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.contract.dto.TopicOverviewDto
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, CorrelationId, TopicName}
import kui.observability.{MetricNames, Telemetry}
import kui.security.Principal
import kui.topic.contract.TopicEndpoints

/** The topic page's aggregation: five sections, one request, five independent failure levels.
  *
  * ==It never fails because a service did not answer==
  *
  * Every upstream failure produces a document, not an error. A section whose service answered is `Ok`; one
  * whose service is configured and did not answer is `Unavailable` with the reason; one whose service this
  * build has no client for is `NotConfigured`. A page that 500s because one of its five inputs failed is
  * exactly the outage the section shape exists to prevent — the user would lose the topic's name and cluster
  * along with the tab that broke.
  *
  * The one thing that *is* an error is a topic that does not exist. "No such topic" and "the topic service
  * could not answer" are different facts with different remedies, and collapsing them would make the page
  * show an empty topic instead of "no such topic". That is why this returns an `Either` rather than the bare
  * document: the `Left` is only ever an `ApplicationError` — a 404 or a 403 about the *request* — and
  * `TopicOverviewSuite` asserts that no infrastructure failure can produce one.
  *
  * ==Adding a section is a map entry==
  *
  * M4's Consumers tab is a `SectionSource` registered under `consumerGroups`, plus the client it calls. This
  * file does not change, and `TopicOverviewSuite.addingASectionIsAMapEntry` proves it by registering one in a
  * test. That is KU-013's extension point demonstrated rather than promised.
  *
  * There is deliberately no fan-out framework. Four of the five sections are constants in M2 and the fifth is
  * one call; when M4 adds a real second call the traversal below becomes a `parTraverse`, which is a
  * three-line change this suite already covers.
  */
trait TopicOverviewUseCase[F[_]] {

  def overview(
      cluster: ClusterId,
      topic: TopicName,
      principal: Principal,
      correlationId: CorrelationId
  ): F[Either[KuiError, TopicOverviewDto]]
}

object TopicOverviewUseCase {

  /** One section this build can fill, beyond the topic itself.
    *
    * It answers `Json` because the shape belongs to the service that owns the section and that service's
    * contract does not exist yet; inventing a record here would be a type M4 has to delete. A source that
    * fails gives its own section an `Unavailable` and leaves the other four alone.
    */
  trait SectionSource[F[_]] {
    def fetch(cluster: ClusterId, topic: TopicName, context: CallContext): F[Either[KuiError, List[Json]]]
  }

  /** The sections this build can actually fill.
    *
    * `topic` is filled by the topic client below; everything else is filled by a registered [[SectionSource]]
    * and is `NotConfigured` without one. Reading the set from the sources rather than hard-coding it means
    * the answer cannot drift from what is actually wired.
    */
  def fillable[F[_]](sources: Map[String, SectionSource[F]], hasTopicClient: Boolean): Set[String] =
    sources.keySet ++ Option.when(hasTopicClient)(TopicOverviewDto.TopicSection)

  /** The aggregation's name, for the span and for the `aggregation` label on the section counter. */
  val Aggregation: String = "topic.overview"

  def resource[F[_]: Async](
      topics: ServiceClient[F],
      signals: CapabilitySignals[F],
      telemetry: Telemetry[F],
      sources: Map[String, SectionSource[F]] = Map.empty[String, SectionSource[F]]
  ): Resource[F, TopicOverviewUseCase[F]] =
    Resource.eval(sectionCounter(telemetry)).map(new Impl[F](topics, signals, sources, _))

  private def sectionCounter[F[_]: Async](telemetry: Telemetry[F]): F[Counter[F, Long]] =
    telemetry
      .meter("kui.gateway.aggregation")
      .flatMap(
        _.counter[Long](MetricNames.AggregationSection)
          .withDescription("How often each section of an aggregated response is served in each state")
          .create
      )

  /** Why a section is not `Ok`, classified by failure *case* rather than by error code: "could not connect"
    * and "the breaker is open" share a code and mean different things on a screen.
    */
  def reasonOf(error: KuiError): ReasonCode = error match {
    case InfrastructureError.CircuitOpen(_, _) => ReasonCode.CircuitOpen
    case InfrastructureError.Timeout(_, _) => ReasonCode.UpstreamTimeout
    case InfrastructureError.AuthFailed(_) => ReasonCode.UpstreamAuth
    case _ => ReasonCode.UpstreamUnavailable
  }

  final private class Impl[F[_]: Async](
      topics: ServiceClient[F],
      signals: CapabilitySignals[F],
      sources: Map[String, SectionSource[F]],
      counter: Counter[F, Long]
  ) extends TopicOverviewUseCase[F] {

    def overview(
        cluster: ClusterId,
        topic: TopicName,
        principal: Principal,
        correlationId: CorrelationId
    ): F[Either[KuiError, TopicOverviewDto]] = {
      val context = CallContext(principal, correlationId, Some(cluster))

      for {
        answer <- topics.call(TopicEndpoints.getTopic, (cluster, topic))(context)
        now <- Clock[F].realTimeInstant
        document <- answer match {
          // A request-level failure — no such topic, no such cluster, not permitted — is the caller's
          // answer, not a missing section. It is returned as it came, so the status and the code the topic
          // service chose are the ones the browser sees.
          case Left(error) if !isTransport(error) => Async[F].pure(Left(error))
          case _ => assemble(answer, cluster, topic, context, now).map(Right(_))
        }
        _ <- document.traverse_(record)
      } yield document
    }

    /** The topic section from the upstream answer, the other four from whatever is registered. */
    private def assemble(
        answer: Either[KuiError, kui.topic.contract.dto.TopicDetailResponse],
        cluster: ClusterId,
        topic: TopicName,
        context: CallContext,
        now: Instant
    ): F[TopicOverviewDto] =
      for {
        topicSection <- answer match {
          case Right(response) => Async[F].pure(response.topic)
          case Left(error) => report(error, now).as(unavailable[TopicDetailDto](error, now))
        }
        consumerGroups <- sectionOf(TopicOverviewDto.ConsumerGroupsSection, cluster, topic, context, now)
        connectors <- sectionOf(TopicOverviewDto.ConnectorsSection, cluster, topic, context, now)
        acls <- sectionOf(TopicOverviewDto.AclsSection, cluster, topic, context, now)
        schemas <- sectionOf(TopicOverviewDto.SchemasSection, cluster, topic, context, now)
      } yield TopicOverviewDto(
        topic = topicSection,
        consumerGroups = consumerGroups,
        connectors = connectors,
        acls = acls,
        schemas = asOne(schemas),
        generatedAt = now
      )

    /** A section with no registered source is `NotConfigured` — hidden, not shown as an error.
      *
      * This is the whole of DEVPLAN §10 D10 in one line. Four permanent red panels on every topic page of
      * every M2 installation would train operators to ignore the colour that matters.
      */
    private def sectionOf(
        name: String,
        cluster: ClusterId,
        topic: TopicName,
        context: CallContext,
        now: Instant
    ): F[Section[List[Json]]] =
      sources.get(name) match {
        case None => Async[F].pure(Section.NotConfigured)
        case Some(source) =>
          source.fetch(cluster, topic, context).map {
            case Right(values) => Section.Ok(values, now)
            case Left(error) => unavailable[List[Json]](error, now)
          }
      }

    /** The schemas section is one document rather than a list, because a topic has a key schema and a value
      * schema and the registry answers about the pair. The four `Json` sections share one source shape so
      * that registering one is the same edit whichever it is; this is where the odd one out is adapted.
      */
    private def asOne(section: Section[List[Json]]): Section[Json] = section match {
      case Section.Ok(values, at) => Section.Ok(Json.arr(values*), at)
      case Section.Stale(values, at, reason) => Section.Stale(Json.arr(values*), at, reason)
      case Section.Unavailable(reason, message, since) => Section.Unavailable(reason, message, since)
      case Section.Forbidden => Section.Forbidden
      case Section.NotConfigured => Section.NotConfigured
    }

    private def unavailable[A](error: KuiError, now: Instant): Section[A] =
      Section.Unavailable(reasonOf(error), error.message, Some(now))

    /** Only a transport failure dims a capability (ADR-039 §6).
      *
      * A Kafka cluster the topic service cannot reach is *not* one of these: that is the topic service
      * answering correctly, with a stale or unavailable section inside its own response, and dimming the
      * whole feature for it would take the topic list away for every other cluster in the deployment.
      */
    private def report(error: KuiError, now: Instant): F[Unit] = error match {
      case transport: InfrastructureError =>
        signals.updateService(topics.service)(
          _.copy(readiness = Some(ReadinessSignal.NotReady(reasonOf(transport), transport.message, now)))
        )
      case _ => Async[F].unit
    }

    private def isTransport(error: KuiError): Boolean = error match {
      case _: InfrastructureError => true
      case _ => false
    }

    /** One count per section per request, labelled with the state it was served in.
      *
      * No log line for a `NotConfigured` section: it is the normal state in M2 and would be a line on every
      * request, which is how a log stops being read.
      */
    private def record(document: TopicOverviewDto): F[Unit] =
      TopicOverviewDto
        .statuses(document)
        .toList
        .traverse_ { case (section, status) =>
          counter.inc(
            Attribute("aggregation", Aggregation),
            Attribute("section", section),
            Attribute("status", status)
          )
        }
  }
}
