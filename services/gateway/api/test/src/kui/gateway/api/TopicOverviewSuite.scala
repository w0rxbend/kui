package kui.gateway.api

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Resource
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.contracts.capability.CapabilityKey
import kui.gateway.application.capability.{
  CapabilityRegistry,
  CapabilitySignals,
  ReadinessSignal,
  RegistryConfig
}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.gateway.application.topic.TopicOverviewUseCase
import kui.gateway.contract.TopicOverviewEndpoints
import kui.gateway.contract.dto.TopicOverviewDto
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, ServiceId, TopicName}
import kui.observability.Telemetry
import kui.security.SignedPrincipal
import kui.testkit.fakes.FakeStructuredLogger
import kui.topic.contract.dto.TopicDetailResponse
import kui.topic.contract.{GoldenDocuments, TopicEndpoints}

/** The topic page's aggregation, over a stub topic service.
  *
  * Three claims are on trial here and each is one the page depends on. That a section whose service this
  * deployment does not have is *hidden* rather than shown as an error; that a service that could not answer
  * costs its own section and never the request; and that adding a section in M4 is a registration rather than
  * a rewrite of this file.
  *
  * The topic section's content is the topic service's own committed golden document, read from that module
  * rather than retyped here, so the aggregation is exercised against what the service really sends.
  */
final class TopicOverviewSuite extends CatsEffectSuite {

  private val topic = ServiceId.unsafe("topic")

  private def detailResponse: TopicDetailResponse =
    parse(GoldenDocuments.topicDetailResponse)
      .flatMap(_.as[TopicDetailResponse])
      .fold(failure => fail(s"the topic service's golden detail document must decode: $failure"), identity)

  private def stubClient(answer: Either[KuiError, TopicDetailResponse]): ServiceClient[IO] =
    new ServiceClient[IO] {
      val service: ServiceId = topic
      def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

      def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] =
        IO.pure(answer.map(_.asInstanceOf[O]))

      def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

      def stream[I](
          endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
          input: I
      )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
    }

  private def signals: Resource[IO, CapabilitySignals[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        Telemetry.noop[IO],
        logger
      )
      built <- Resource.eval(CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(topic)))
    } yield built

  private def overviewOf(
      answer: Either[KuiError, TopicDetailResponse],
      sources: Map[String, TopicOverviewUseCase.SectionSource[IO]] = Map.empty
  ): Resource[IO, (TopicOverviewUseCase[IO], CapabilitySignals[IO])] =
    signals.flatMap(signal =>
      TopicOverviewUseCase
        .resource[IO](stubClient(answer), signal, Telemetry.noop[IO], sources)
        .map(_ -> signal)
    )

  private def ask(
      answer: Either[KuiError, TopicDetailResponse],
      sources: Map[String, TopicOverviewUseCase.SectionSource[IO]] = Map.empty
  ): IO[Either[KuiError, TopicOverviewDto]] =
    overviewOf(answer, sources).use { case (useCase, _) =>
      useCase.overview(
        ClusterId.unsafe("prod-eu"),
        TopicName.unsafe("orders"),
        kui.security.Principal.Anonymous,
        kui.kernel.CorrelationId.unsafe("11111111-1111-1111-1111-111111111111")
      )
    }

  private def statuses(result: Either[KuiError, TopicOverviewDto]): Map[String, String] =
    result.fold(error => fail(s"the aggregation should have answered: ${error.message}"), TopicOverviewDto.statuses)

  test("theTopicSectionIsOkWhenTheTopicServiceAnswers") {
    ask(Right(detailResponse)).map { result =>
      assertEquals(statuses(result).get("topic"), Some("ok"))
      assertEquals(
        result.toOption.flatMap(_.topic.toOption).map(_.row.name),
        Some(TopicName.unsafe("orders"))
      )
    }
  }

  test("everySectionWhoseServiceIsAbsentIsNotConfigured") {
    // The four of them, by name. `unavailable` here would put four permanent red panels on every topic
    // page of every M2 installation, and an operator shown four errors that never change stops reading
    // errors (DEVPLAN §10 D10 correcting the roadmap's wording, ADR-032).
    ask(Right(detailResponse)).map { result =>
      assertEquals(
        statuses(result).removed("topic"),
        Map(
          "consumerGroups" -> "not_configured",
          "connectors" -> "not_configured",
          "acls" -> "not_configured",
          "schemas" -> "not_configured"
        )
      )
    }
  }

  test("aFailingTopicServiceGivesAnUnavailableSectionAndStillA200") {
    // The aggregation never fails on a transport error. The page renders its header from the URL and an
    // error region in place of the body; the four placeholder sections are untouched.
    val unreachable = InfrastructureError.Unreachable("kui-topic", "connection refused")

    ask(Left(unreachable)).map { result =>
      assert(result.isRight, result.toString)
      assertEquals(statuses(result).get("topic"), Some("unavailable"))
      assertEquals(statuses(result).get("consumerGroups"), Some("not_configured"))
    }
  }

  test("a transport failure is reported to the capability signals, never swallowed") {
    // An aggregation that hid the failure would leave the sidebar green while the page in front of the
    // user showed an outage, and nothing on the screen would explain the difference.
    val unreachable = InfrastructureError.Unreachable("kui-topic", "connection refused")

    overviewOf(Left(unreachable)).use { case (useCase, signal) =>
      for {
        _ <- useCase.overview(
          ClusterId.unsafe("prod-eu"),
          TopicName.unsafe("orders"),
          kui.security.Principal.Anonymous,
          kui.kernel.CorrelationId.unsafe("11111111-1111-1111-1111-111111111111")
        )
        inputs <- signal.inputs(CapabilityKey(topic, None))
      } yield assert(
        inputs.readiness.exists {
          case ReadinessSignal.NotReady(_, _, _) => true
          case _ => false
        },
        inputs.readiness.toString
      )
    }
  }

  test("aStaleTopicSectionSurvivesAsStale") {
    // The gateway passes the topic service's own section through rather than re-deciding it: only the
    // topic service knows when its snapshot was taken and why it could not be renewed.
    val response = detailResponse.copy(
      topic = kui.contracts.Section.Stale(
        detailResponse.topic.toOption.getOrElse(fail("the golden detail must carry data")),
        java.time.Instant.parse("2026-09-03T10:11:12Z"),
        kui.contracts.capability.ReasonCode.UpstreamTimeout
      )
    )

    ask(Right(response)).map(result => assertEquals(statuses(result).get("topic"), Some("stale")))
  }

  test("anUnknownTopicIsA404FromTheAggregationNotAnEmptySection") {
    // A topic that does not exist is a different fact from a service that could not answer, and collapsing
    // them would make the page show an empty topic instead of "no such topic".
    val missing = ApplicationError.NotFound("topic", "nope", ErrorCode.TopicNotFound)

    ask(Left(missing)).map { result =>
      assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.TopicNotFound))
      assertEquals(ErrorEnvelope.statusOf(missing), 404)
    }
  }

  test("a caller who may not see the topic gets the refusal, not a hidden section") {
    val forbidden = ApplicationError.Forbidden("TOPIC:VIEW is required")

    ask(Left(forbidden)).map(result => assert(result.isLeft, result.toString))
  }

  test("addingASectionIsAMapEntry") {
    // KU-013's extension point, proven rather than promised: M4's Consumers tab is this, plus the client
    // it calls. No other change to the use case, and no change to this file when it happens.
    val groups = List(Json.obj("groupId" -> Json.fromString("orders-consumer")))

    val source = new TopicOverviewUseCase.SectionSource[IO] {
      def fetch(cluster: ClusterId, topic: TopicName, context: CallContext): IO[Either[KuiError, List[Json]]] =
        IO.pure(Right(groups))
    }

    ask(Right(detailResponse), Map(TopicOverviewDto.ConsumerGroupsSection -> source)).map { result =>
      assertEquals(statuses(result).get("consumerGroups"), Some("ok"))
      assertEquals(result.toOption.flatMap(_.consumerGroups.toOption), Some(groups))
      // And nothing else moved.
      assertEquals(statuses(result).get("connectors"), Some("not_configured"))
      assertEquals(statuses(result).get("topic"), Some("ok"))
    }
  }

  test("a registered section that fails costs only its own section") {
    val source = new TopicOverviewUseCase.SectionSource[IO] {
      def fetch(cluster: ClusterId, topic: TopicName, context: CallContext): IO[Either[KuiError, List[Json]]] =
        IO.pure(Left(InfrastructureError.Unreachable("kui-consumer", "connection refused")))
    }

    ask(Right(detailResponse), Map(TopicOverviewDto.ConsumerGroupsSection -> source)).map { result =>
      assertEquals(statuses(result).get("consumerGroups"), Some("unavailable"))
      assertEquals(statuses(result).get("topic"), Some("ok"))
    }
  }

  test("fillable says what this build can actually fill, read from what is wired") {
    // A hard-coded set would be a set that drifts from the sources actually registered.
    assertEquals(
      TopicOverviewUseCase.fillable[IO](Map.empty, hasTopicClient = true),
      Set(TopicOverviewDto.TopicSection)
    )
    assertEquals(TopicOverviewUseCase.fillable[IO](Map.empty, hasTopicClient = false), Set.empty[String])
  }

  test("theOverviewPathIsTheTopicPathPlusOverview") {
    // The gateway's contract is cross-compiled and cannot import the topic service's, so it spells these
    // four segments out. This is the only module that sees both, and this is the comparison that keeps the
    // two spellings from drifting into a 404 nobody notices until a page is blank.
    assertEquals(TopicOverviewEndpoints.ClustersSegment, TopicEndpoints.ClustersSegment)
    assertEquals(TopicOverviewEndpoints.TopicsSegment, TopicEndpoints.TopicsSegment)
    assertEquals(TopicOverviewEndpoints.ClusterIdParam, TopicEndpoints.ClusterIdParam)
    assertEquals(TopicOverviewEndpoints.TopicNameParam, TopicEndpoints.TopicNameParam)

    assertEquals(
      TopicOverviewEndpoints.overview.showPathTemplate().takeWhile(_ != '?'),
      "/api/v1/clusters/{clusterId}/topics/{topicName}/overview"
    )
  }

  test("the overview endpoint claims no path a proxied topic route already claims") {
    // Two routes for one address is invisible in a route list. `/overview` is a suffix no topic endpoint
    // has, and this is the assertion that keeps it that way.
    val proxied = TopicEndpoints.all.map(_.showPathTemplate().takeWhile(_ != '?').replace("/internal/v1", "/api/v1"))

    assert(
      !proxied.contains(TopicOverviewEndpoints.overview.showPathTemplate().takeWhile(_ != '?')),
      proxied.toString
    )
  }
}
