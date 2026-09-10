package kui.gateway.api

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.{Deferred, IO, Ref}
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.alerts.contract.AlertsStreamEndpoint
import kui.alerts.contract.dto.AlertChangeDto
import kui.contracts.ErrorEnvelope
import kui.gateway.api.routing.{PolicyRbacPreCheck, RbacPreCheck}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.{Sse, SseEvent}
import kui.http.upstream.CircuitEvent
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ServiceId}
import kui.security.rbac.{
  Action,
  ClusterFlags,
  DefaultRole,
  Permission,
  RbacPolicy,
  Resource as RbacResource
}
import kui.security.SignedPrincipal
import kui.testkit.fakes.FakeStructuredLogger

/** The alerts stream's gateway-specific promises, exercised through a real listener. */
final class AlertsStreamRoutesSuite extends CatsEffectSuite {

  private val alerts = ServiceId.unsafe("alerts")
  private val cluster = ClusterId.unsafe("prod-eu")
  private val path = "/api/v1/clusters/prod-eu/alerts/stream"

  private final case class Opened(endpoint: String, path: String, context: CallContext)

  private def client(
      source: Stream[IO, SseEvent]
  ): IO[(ServiceClient[IO], Ref[IO, List[Opened]])] =
    Ref.of[IO, List[Opened]](Nil).map { opened =>
      val serviceClient = new ServiceClient[IO] {
        val service: ServiceId = alerts

        def call[I, O](endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def callPublic[I, O](endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any], input: I)(
            ctx: CallContext
        ): IO[Either[KuiError, O]] = IO.raiseError(new UnsupportedOperationException)

        def stream[I](
            endpoint: Endpoint[SignedPrincipal, I, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]],
            input: I
        )(ctx: CallContext): Stream[IO, SseEvent] =
          Stream.eval(
            opened.update(
              _ :+ Opened(
                endpoint.info.name.getOrElse("<unnamed>"),
                endpoint.showPathTemplate(),
                ctx
              )
            )
          ).drain ++ source

        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty
      }

      (serviceClient, opened)
    }

  private def request(server: GatewayTestServer.Running): IO[(Int, Array[Byte])] =
    basicRequest
      .get(server.at(path))
      .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
      .send(server.backend)
      .flatMap(response => response.body.compile.to(Array).map(response.code.code -> _))

  test("the public route keeps the alerts endpoint identity and rewrites only its prefix") {
    val internal = AlertsStreamEndpoint.endpoint[IO]
    val public = AlertsStreamRoutes.publicEndpoint[IO]

    assertEquals(public.showPathTemplate(), path.replace("prod-eu", "{clusterId}"))
    assertEquals(public.info.name, internal.info.name)
    assertEquals(public.info.summary, internal.info.summary)
    assertEquals(
      public.attribute(kui.contracts.rbac.EndpointAuthorization.Key),
      internal.attribute(kui.contracts.rbac.EndpointAuthorization.Key)
    )
  }

  test("the public route relays the alerts service's bytes exactly") {
    val envelope = ErrorEnvelope(
      code = "KUI-UPSTREAM-UNAVAILABLE",
      message = "the alerts service stopped sending",
      details = Nil,
      correlationId = "0123456789abcdef",
      timestamp = Instant.parse("2026-09-08T10:00:00Z"),
      retryable = true
    )
    val events = List(
      SseEvent.data(
        AlertsStreamEndpoint.EventName,
        AlertChangeDto(cluster.value, 3, Instant.parse("2026-09-08T09:59:00Z")).asJson
      ),
      SseEvent.heartbeat,
      SseEvent.error(envelope)
    )
    val source = Stream.emits(events).covary[IO]

    for {
      expected <- Sse.encode(source).compile.to(Array)
      built <- client(source)
      (upstream, opened) = built
      answer <- GatewayTestServer
        .resource(extraRoutes = AlertsStreamRoutes[IO](upstream, RbacPreCheck.allowAll[IO]))
        .use(request)
      (status, actual) = answer
      calls <- opened.get
    } yield {
      assertEquals(status, 200)
      assertEquals(actual.toList, expected.toList)
      assertEquals(calls.map(_.endpoint), List("alerts.stream"))
      assertEquals(calls.map(_.path), List("/internal/v1/clusters/{clusterId}/alerts/stream"))
      assertEquals(calls.flatMap(_.context.cluster), List(cluster))
    }
  }

  test("cancelling the browser response cancels the upstream alerts subscription") {
    for {
      started <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      source = (
        Stream.eval(started.complete(())).drain ++
          Stream.emit(
            SseEvent.data(
              AlertsStreamEndpoint.EventName,
              AlertChangeDto(cluster.value, 1, Instant.EPOCH).asJson
            )
          ) ++ Stream.never[IO]
      ).onFinalize(cancelled.complete(()).void)
      built <- client(source)
      (upstream, _) = built
      _ <- GatewayTestServer
        .resource(extraRoutes = AlertsStreamRoutes[IO](upstream, RbacPreCheck.allowAll[IO]))
        .use { server =>
          basicRequest
            .get(server.at(path))
            .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
            .send(server.backend)
            .flatMap(response => response.body.take(1).compile.drain)
        }
      _ <- started.get.timeout(5.seconds)
      _ <- cancelled.get.timeout(5.seconds)
    } yield assert(true)
  }

  test("an RBAC denial is answered before the upstream alerts stream is opened") {
    val source = Stream.never[IO]

    for {
      built <- client(source)
      (upstream, opened) = built
      response <- GatewayTestServer
        .resource(extraRoutes = AlertsStreamRoutes[IO](upstream, RbacPreCheck.denyAll[IO]("denied")))
        .use(_.get(path))
      calls <- opened.get
    } yield {
      assertEquals(response.code.code, 403, response.body)
      val envelope = decode[ErrorEnvelope](response.body).fold(error => fail(error.getMessage), identity)
      assertEquals(envelope.code, "KUI-FORBIDDEN")
      assertEquals(calls, Nil, "the denied request opened an upstream stream")
    }
  }

  test("a principal without ALERTS:VIEW is refused the stream before an upstream connection is made") {
    // The case above proves the *seam* is consulted and says nothing about which permission decides. It
    // injects `denyAll`, which refuses a caller holding every grant in the vocabulary just as readily as one
    // holding none, so `AlertsStreamEndpoint`'s `ResourceRequirement.unnamed(Alerts, AlertsView)` could be
    // changed to any other action, or dropped for a permission the deployment always grants, and the whole
    // gateway suite would stay green. This one runs the gateway's real `PolicyRbacPreCheck` over two
    // policies that differ in exactly that one grant.
    //
    // Both directions, because a suite that only watched the refusal would pass against a check that
    // refuses everyone, and a stream nobody may open is not a permission model.
    for {
      logger <- FakeStructuredLogger[IO]
      // One frame and then silence. `Stream.never` alone would leave the allowed half waiting on a byte
      // that is never produced, which reads as a hang rather than as a refusal.
      source = Stream.emit(
        SseEvent.data(
          AlertsStreamEndpoint.EventName,
          AlertChangeDto(cluster.value, 2, Instant.EPOCH).asJson
        )
      ).covary[IO] ++ Stream.never[IO]
      built <- client(source)
      (upstream, opened) = built
      check = (policy: RbacPolicy) =>
        new PolicyRbacPreCheck[IO](policy, _ => IO.pure(ClusterFlags.Writable), logger)
      refused <- GatewayTestServer
        .resource(extraRoutes = AlertsStreamRoutes[IO](upstream, check(everythingButAlerts)))
        .use(_.get(path))
      afterRefusal <- opened.get
      allowedResponse <- GatewayTestServer
        .resource(extraRoutes = AlertsStreamRoutes[IO](upstream, check(alertsViewOnly)))
        .use { server =>
          basicRequest
            .get(server.at(path))
            .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
            .send(server.backend)
            .flatMap(response => response.body.take(1).compile.drain.as(response.code.code))
        }
      afterAllowance <- opened.get
    } yield {
      assertEquals(refused.code.code, 403, refused.body)
      val envelope = decode[ErrorEnvelope](refused.body).fold(error => fail(error.getMessage), identity)
      assertEquals(envelope.code, "KUI-FORBIDDEN")
      // The point of the case: the subscription is never opened, so a caller who may not read the feed
      // costs the alerts service nothing at all — no connection, no fiber, no `Topic` subscriber to leak.
      assertEquals(afterRefusal, Nil, "a caller without ALERTS:VIEW opened an upstream stream")

      assertEquals(allowedResponse, 200)
      assertEquals(afterAllowance.size, 1, afterAllowance.toString)
    }
  }

  /** Every action on every resource except the alerts ones, held through the default role.
    *
    * `Resource.Alerts` is excluded whole rather than by naming `AlertsView`, because `AlertsAcknowledge`
    * implies `AlertsView` (`Action.implied`): a policy that granted the acknowledgement and withheld the
    * read would still open this stream, and the case would then be asserting something it does not mean.
    */
  private val everythingButAlerts: RbacPolicy =
    RbacPolicy(
      Nil,
      Some(DefaultRole(grantsFor(RbacResource.values.toList.filterNot(_ == RbacResource.Alerts))))
    )

  /** `ALERTS:VIEW`, and nothing else in the vocabulary. */
  private val alertsViewOnly: RbacPolicy =
    RbacPolicy(
      Nil,
      Some(DefaultRole(List(RbacPolicy.permission(RbacResource.Alerts, None, Set(Action.AlertsView)))))
    )

  private def grantsFor(resources: List[RbacResource]): List[Permission] =
    resources.map(resource => RbacPolicy.allPermission(resource, None))
}
