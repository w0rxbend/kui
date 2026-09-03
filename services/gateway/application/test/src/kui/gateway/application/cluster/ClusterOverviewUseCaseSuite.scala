package kui.gateway.application.cluster

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.cluster.contract.dto.ClustersResponse
import kui.config.UpstreamServiceConfig
import kui.contracts.capability.{CapabilityKey, CapabilityState, DegradedReason, ReasonCode}
import kui.contracts.cluster.{ClusterRowDto, ClusterSecurityDto, ClusterSummaryDto}
import kui.contracts.{ErrorEnvelope, Section}
import kui.gateway.application.capability.{CapabilityRegistry, CapabilitySignals, RegistryConfig}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.{ApplicationError, ErrorCode, InfrastructureError, KuiError}
import kui.kernel.{ClusterId, CorrelationId, ServiceId}
import kui.observability.Telemetry
import kui.security.{Principal, SignedPrincipal}
import kui.testkit.fakes.FakeStructuredLogger

/** The milestone's headline promise, asserted where it is decided.
  *
  * Three configured clusters with one unreachable must give two populated rows and one marked unavailable
  * with a reason — and the row that failed must still carry enough to be a link, because "remains clickable"
  * is a property of the payload before it is a property of any screen.
  */
final class ClusterOverviewUseCaseSuite extends CatsEffectSuite {

  private val clusterService = ServiceId.unsafe("cluster")
  private val at = Instant.parse("2026-09-03T10:11:12Z")
  private val caller = Principal.Anonymous
  private val correlationId = CorrelationId.unsafe("3b1fa9c2e4d54f0b")

  private def row(id: String, summary: Section[ClusterSummaryDto]): ClusterRowDto =
    ClusterRowDto(
      id = ClusterId.unsafe(id),
      name = s"cluster $id",
      readOnly = false,
      bootstrapServers = s"$id.example.com:9092",
      security = ClusterSecurityDto("PLAINTEXT", None, false, false),
      summary = summary
    )

  private val healthy: Section[ClusterSummaryDto] = Section.Ok(
    ClusterSummaryDto(None, None, None, ClusterSummaryDto.KRaft, 3, None, None, None, None, Nil, at),
    at
  )

  private val unreachable: Section[ClusterSummaryDto] =
    Section.Unavailable(ReasonCode.UpstreamUnavailable, "connection refused", Some(at))

  private val threeClusters =
    ClustersResponse(List(row("prod-eu", healthy), row("staging", healthy), row("dead", unreachable)), at)

  /** A client that answers from a script, optionally after a delay. */
  private def client(
      answer: Either[KuiError, ClustersResponse],
      after: FiniteDuration = Duration.Zero
  ): ServiceClient[IO] =
    new ServiceClient[IO] {
      val service: ServiceId = clusterService
      def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

      def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] =
        IO.sleep(after).as(answer.map(_.asInstanceOf[O]))

      def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
          ctx: CallContext
      ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

      def stream[I](
          endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
          input: I
      )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
    }

  private def using[A](
      service: ServiceClient[IO]
  )(body: (ClusterOverviewUseCase[IO], CapabilityRegistry[IO]) => IO[A]): IO[A] =
    (for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](RegistryConfig.Default, Telemetry.noop[IO], logger)
      signals <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(clusterService))
      )
      overview <- ClusterOverviewUseCase.resource[IO](service, registry, signals, logger)
    } yield (overview, registry)).use(body.tupled)

  // -----------------------------------------------------------------------------------------------

  test("threeClustersOneUnreachableGivesTwoOkSectionsAndOneUnavailable") {
    using(client(Right(threeClusters))) { (overview, _) =>
      overview.overview(caller, correlationId).map { dto =>
        val rows = dto.clusters.toOption.getOrElse(fail(s"the outer section failed: ${dto.clusters}"))

        assertEquals(dto.clusters.status, "ok")
        assertEquals(rows.map(_.cluster.summary.status), List("ok", "ok", "unavailable"))

        // "Remains clickable" is a property of the payload: the failing row still carries everything a
        // link and a table cell need.
        val dead = rows.last.cluster
        assertEquals(dead.id.value, "dead")
        assertEquals(dead.name, "cluster dead")
        assertEquals(dead.bootstrapServers, "dead.example.com:9092")
      }
    }
  }

  test("theResponseIsBoundedByTheConfiguredTimeoutNotByTheDeadCluster") {
    // The R-8 assertion, and it is written against the *configured* value rather than a literal, so that
    // changing the default cannot silently invalidate the bound. The stub takes exactly as long as the
    // upstream budget allows and then fails, which is what a dead cluster service looks like from here; the
    // aggregation must add nothing of its own on top of it.
    val budget = UpstreamServiceConfig.DefaultTimeout
    val slow = client(Left(InfrastructureError.Timeout("cluster", budget.toMillis)), after = budget)

    val program = using(slow) { (overview, _) =>
      overview.overview(caller, correlationId).flatMap(dto => IO.monotonic.map((dto, _)))
    }

    TestControl.executeEmbed(program).map { (dto, elapsed) =>
      assertEquals(elapsed, budget)
      assertEquals(dto.clusters.status, "unavailable")
    }
  }

  test("aFailedCallServesTheLastKnownRowsAsStaleWithTheirOriginalFetchedAt") {
    // ADR-043 §2's cached fallback. The timestamp is the one the rows were fetched at, not now: a stale
    // marker whose time is the current time tells a user nothing about how old the data is.
    Ref.of[IO, Either[KuiError, ClustersResponse]](Right(threeClusters)).flatMap { script =>
      val scripted = new ServiceClient[IO] {
        val service: ServiceId = clusterService
        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = script.get.map(_.map(_.asInstanceOf[O]))

        def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def stream[I](
            endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
            input: I
        )(ctx: CallContext): Stream[IO, SseEvent] = Stream.empty
      }

      using(scripted) { (overview, _) =>
        for {
          first <- overview.overview(caller, correlationId)
          _ <- script.set(Left(InfrastructureError.Unreachable("cluster", "connection refused")))
          second <- overview.overview(caller, correlationId)
        } yield {
          val fetchedAt = first.clusters match {
            case Section.Ok(_, when) => when
            case other => fail(s"the first call should have succeeded: $other")
          }

          second.clusters match {
            case Section.Stale(rows, when, reason) =>
              assertEquals(rows.map(_.cluster.id.value), List("prod-eu", "staging", "dead"))
              assertEquals(when, fetchedAt)
              assertEquals(reason, ReasonCode.UpstreamUnavailable)
            case other => fail(s"a failed call with a cache must be stale: $other")
          }
        }
      }
    }
  }

  test("aFailedCallWithNoCacheIsAnUnavailableOuterSectionAndAnEmptyList") {
    using(client(Left(InfrastructureError.Unreachable("cluster", "connection refused")))) {
      (overview, _) =>
        overview.overview(caller, correlationId).map { dto =>
          assertEquals(dto.clusters.status, "unavailable")
          assertEquals(dto.clusters.toOption, None)
          dto.clusters match {
            case Section.Unavailable(reason, message, _) =>
              assertEquals(reason, ReasonCode.UpstreamUnavailable)
              assert(message.contains("cluster"), message)
            case other => fail(s"expected an unavailable section: $other")
          }
        }
    }
  }

  test("theOverviewNeverFails, whatever the upstream answered") {
    // Every failure shape the client can produce, through the same path. A dashboard that raises because one
    // of its inputs failed is the outage the section shape exists to prevent.
    val failures: List[KuiError] = List(
      InfrastructureError.Unreachable("cluster", "refused"),
      InfrastructureError.Timeout("cluster", 1000L),
      InfrastructureError.CircuitOpen("cluster", at),
      InfrastructureError.AuthFailed("cluster"),
      ApplicationError.NotFound("cluster", "x", ErrorCode.ClusterNotFound),
      ApplicationError.Forbidden("no"),
      ApplicationError.Invalid("bad", Nil)
    )

    failures.traverse_ { failure =>
      using(client(Left(failure)))((overview, _) =>
        overview.overview(caller, correlationId).map(dto => assertEquals(dto.clusters.toOption, None))
      )
    }
  }

  test("anInfrastructureFailureIsReportedToTheCapabilitySignals") {
    // An aggregation that swallowed the failure would leave the sidebar green while the page in front of
    // the user showed an outage, with nothing on screen explaining the difference.
    using(client(Left(InfrastructureError.Unreachable("cluster", "refused")))) { (overview, registry) =>
      for {
        _ <- overview.overview(caller, correlationId)
        _ <- IO.sleep(RegistryConfig.Default.debounce + 50.millis)
        state <- registry.state(CapabilityKey(clusterService, None))
      } yield assert(
        state match {
          case CapabilityState.Available => false
          case _ => true
        },
        s"an unreachable cluster service must be visible in the registry, but the state is $state"
      )
    }
  }

  test("anApplicationFailureIsNotReported") {
    // ADR-039 §6, the other direction: a business failure says something about the request, not about the
    // service, and reporting it would let anyone dim a feature for everyone else.
    using(client(Left(ApplicationError.Forbidden("no")))) { (overview, registry) =>
      for {
        _ <- overview.overview(caller, correlationId)
        _ <- IO.sleep(RegistryConfig.Default.debounce + 50.millis)
        state <- registry.state(CapabilityKey(clusterService, None))
      } yield assert(
        state match {
          case CapabilityState.Unavailable(_, _, _) => false
          case _ => true
        },
        s"a business failure must not dim a capability, but the state is $state"
      )
    }
  }

  test("aClusterThatIsUnreachableDoesNotChangeTheServiceCapability") {
    // Decision D4, asserted on the response a user actually sees: one dead Kafka cluster must not dim the
    // cluster feature for the other two.
    using(client(Right(threeClusters))) { (overview, registry) =>
      for {
        dto <- overview.overview(caller, correlationId)
        _ <- IO.sleep(RegistryConfig.Default.debounce + 50.millis)
        state <- registry.state(CapabilityKey(clusterService, None))
      } yield {
        assertEquals(dto.clusters.status, "ok")
        assert(
          state match {
            case CapabilityState.Unavailable(_, _, _) => false
            case _ => true
          },
          s"one unreachable cluster must not dim the service capability, but the state is $state"
        )
      }
    }
  }

  test("aRowWithNoRegistryEntryIsNeverMissingAStatus") {
    // "Not asked yet" and "not deployed" mean different things to whoever is looking, and a row with no
    // status at all forces the browser to invent one.
    using(client(Right(threeClusters))) { (overview, _) =>
      overview.overview(caller, correlationId).map { dto =>
        val rows = dto.clusters.toOption.getOrElse(Nil)

        assertEquals(rows.map(_.capability.status), List("available", "available", "degraded"))
      }
    }
  }

  test("capabilityStateIsMergedPerRow") {
    val degraded = CapabilityState.Degraded(
      DegradedReason(ReasonCode.UpstreamTimeout, "slow", Some(30000L), Some(1200L))
    )

    using(client(Right(threeClusters))) { (overview, registry) =>
      for {
        _ <- registry.report(CapabilityKey(clusterService, Some(ClusterId.unsafe("prod-eu"))), degraded)
        dto <- overview.overview(caller, correlationId)
        rows = dto.clusters.toOption.getOrElse(Nil)
      } yield assertEquals(rows.map(_.capability.status), List("degraded", "available", "degraded"))
    }
  }

  test("theGatewayDoesNotInventARowForAClusterOnlyTheRegistryKnows") {
    // The list is the cluster service's answer; the registry only decorates it. A gateway that added rows
    // of its own would be holding cluster state, which is the one thing it must not do.
    using(client(Right(threeClusters))) { (overview, registry) =>
      for {
        _ <- registry.report(
          CapabilityKey(clusterService, Some(ClusterId.unsafe("ghost"))),
          CapabilityState.Available
        )
        dto <- overview.overview(caller, correlationId)
      } yield assertEquals(
        dto.clusters.toOption.getOrElse(Nil).map(_.cluster.id.value),
        List("prod-eu", "staging", "dead")
      )
    }
  }
}
