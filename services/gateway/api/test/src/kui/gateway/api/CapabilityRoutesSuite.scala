package kui.gateway.api

import java.time.Instant
import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import cats.syntax.all.*
import io.circe.parser.decode
import munit.CatsEffectSuite
import sttp.client4.*

import kui.contracts.capability.{
  CapabilityEntry,
  CapabilityKey,
  CapabilitySnapshot,
  CapabilityState,
  ReasonCode
}
import kui.contracts.ErrorEnvelope
import kui.gateway.application.capability.{
  CapabilityRegistry,
  CapabilitySignals,
  RegistryConfig,
  Trigger
}
import kui.gateway.contract.GatewayEndpoints
import kui.kernel.ServiceId
import kui.testkit.fakes.FakeStructuredLogger

/** That a browser can ask what works, and then be told about every change without asking again.
  *
  * Every assertion here goes over a real socket. The subject is the streaming wiring — whether the first
  * frame really is a snapshot, whether a second browser really gets its own copy, whether one
  * disconnecting really leaves the other alone — and a stub interpreter would be asserting the wiring it
  * had replaced.
  */
final class CapabilityRoutesSuite extends CatsEffectSuite {

  private val cluster = ServiceId.unsafe("cluster")
  private val topic = ServiceId.unsafe("topic")
  private val clusterKey = CapabilityKey(cluster, None)

  private val outage: CapabilityState =
    CapabilityState.Unavailable(ReasonCode.UpstreamUnavailable, "refused", Instant.EPOCH)

  /** A gateway with the capability routes mounted, plus the registry behind them. */
  private def fixture(
      services: List[ServiceId] = List(cluster, topic),
      // Short by default, so the idle-heartbeat assertion does not take fifteen seconds. The tests that
      // are about *changes* turn it up, so a heartbeat cannot arrive before the change they are waiting
      // for and make them assert the wrong frame.
      heartbeat: scala.concurrent.duration.FiniteDuration = 200.millis
  ): Resource[IO, (GatewayTestServer.Running, CapabilityRegistry[IO], Ref[IO, List[ServiceId]])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        // No debounce: the debounce is `CapabilityRegistrySuite`'s subject, and these assertions are
        // about frames on a wire.
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        GatewayTestServer.noTelemetry,
        logger
      )
      _ <- Resource.eval(CapabilitySignals.make[IO](RegistryConfig.Default, registry, services))
      probed <- Resource.eval(Ref.of[IO, List[ServiceId]](Nil))
      trigger = new Trigger[IO] {
        def probe(service: ServiceId): IO[Unit] = probed.update(_ :+ service)
      }
      routes = CapabilityRoutes[IO](
        registry,
        trigger,
        GatewayTestServer.noTelemetry,
        logger,
        kui.http.sse.SseConfig(heartbeatInterval = heartbeat)
      )
      server <- GatewayTestServer.resource(extraRoutes = routes)
    } yield (server, registry, probed)

  private def snapshotPath = s"${GatewayEndpoints.ApiPrefix}/capabilities"
  private def streamPath = s"${GatewayEndpoints.ApiPrefix}/capabilities/stream"

  /** Reads frames off the live stream until `count` of them have arrived, then gives up the connection. */
  private def frames(server: GatewayTestServer.Running, count: Int): IO[List[String]] =
    basicRequest
      .get(server.at(streamPath))
      .response(asStreamAlwaysUnsafe(sttp.capabilities.fs2.Fs2Streams[IO]))
      .send(server.backend)
      .flatMap(response =>
        response.body
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .split(_.isEmpty)
          .map(_.toList.mkString("\n"))
          .filter(_.nonEmpty)
          .take(count.toLong)
          .compile
          .toList
      )
      .timeout(20.seconds)

  /** A session and its CSRF token.
    *
    * The probe is a `POST`, so the edge's CSRF check applies to it like any other mutation (ADR-019). It
    * is not an exemption worth carving out: the endpoint makes the gateway call another service, and a
    * page on another origin must not be able to make it do that. UI-010 sends the token the same way,
    * having read it from `/api/v1/auth/me`.
    */
  private def session(server: GatewayTestServer.Running): IO[Map[String, String]] =
    server.get(s"${GatewayEndpoints.ApiPrefix}/auth/me").map { response =>
      val cookie = response.header("Set-Cookie").flatMap(_.split(";").headOption).getOrElse(fail("no cookie"))
      val token = decode[kui.gateway.contract.dto.AuthMeResponse](response.body)
        .fold(error => fail(error.getMessage), _.csrfToken)
      Map("Cookie" -> cookie, kui.gateway.api.auth.SessionMiddleware.CsrfHeaderName -> token)
    }

  private def eventNameOf(frame: String): String =
    frame.linesIterator.find(_.startsWith("event: ")).map(_.drop("event: ".length)).getOrElse("")

  private def dataOf(frame: String): String =
    frame.linesIterator.filter(_.startsWith("data: ")).map(_.drop("data: ".length)).mkString("\n")

  test("snapshotListsEveryConfiguredService") {
    fixture().use { (server, _, _) =>
      server.get(snapshotPath).map { response =>
        val snapshot = decode[CapabilitySnapshot](response.body).fold(error => fail(error.getMessage), identity)
        assertEquals(snapshot.entries.map(_.key.service.value).sorted, List("cluster", "topic"))
        // A service that is configured but has not been checked yet must be present and starting, not
        // missing. "We have not asked" and "it is not deployed" are different answers, and the browser
        // renders them differently.
        snapshot.entries.foreach {
          case CapabilityEntry(_, CapabilityState.Degraded(reason), _, _) =>
            assertEquals(reason.code, ReasonCode.Starting)
          case other => fail(s"expected degraded-starting before any poll, got $other")
        }
      }
    }
  }

  test("theSnapshotOfADeploymentWithNoServicesIsEmptyRatherThanAnError") {
    // The browser must be able to tell "nothing is configured" from "the gateway is not answering"; only
    // the second shows the full-screen error.
    fixture(services = Nil).use { (server, _, _) =>
      server.get(snapshotPath).map { response =>
        assertEquals(response.code.code, 200)
        assertEquals(decode[CapabilitySnapshot](response.body).map(_.entries), Right(Nil))
      }
    }
  }

  test("streamSendsAFullSnapshotAsTheFirstEvent") {
    fixture().use { (server, _, _) =>
      frames(server, 1).map { received =>
        val first = received.head
        assertEquals(eventNameOf(first), CapabilityRoutes.EventName)
        val snapshot = decode[CapabilitySnapshot](dataOf(first)).fold(error => fail(error.getMessage), identity)
        assertEquals(snapshot.entries.map(_.key.service.value).sorted, List("cluster", "topic"))
        // The exact wire framing, byte for byte, because the browser's parser is tested against these
        // same bytes. Anything that changes the framing has to break here first.
        assert(first.startsWith(s"event: ${CapabilityRoutes.EventName}\ndata: {"), first.take(80))
      }
    }
  }

  test("streamSendsOneEventPerChangeAfterTheSnapshot") {
    fixture(heartbeat = 30.seconds).use { (server, registry, _) =>
      for {
        received <- (
          frames(server, 3),
          IO.sleep(500.millis) *>
            registry.report(clusterKey, outage) *>
            registry.report(CapabilityKey(topic, None), CapabilityState.Available)
        ).parMapN((frames, _) => frames)
      } yield {
        assertEquals(received.size, 3)
        received.foreach(frame =>
          assertEquals(eventNameOf(frame), CapabilityRoutes.EventName)
        )
        // The snapshot, then exactly one frame per change, in the order the changes happened.
        val changes = received.tail.map(dataOf)
        assert(changes.head.contains("unavailable"), changes.head)
        assert(changes(1).contains("topic"), changes(1))
      }
    }
  }

  test("streamSendsAHeartbeatWhileIdle") {
    // A proxy that closes an idle connection would silently break the whole mechanism; the heartbeat is
    // what stops it, so its absence has to fail a test.
    fixture().use { (server, _, _) =>
      frames(server, 2).map { received =>
        assertEquals(eventNameOf(received(1)), "heartbeat")
      }
    }
  }

  test("twoConcurrentSubscribersBothReceiveEveryChange") {
    fixture(heartbeat = 30.seconds).use { (server, registry, _) =>
      (
        frames(server, 2),
        frames(server, 2),
        IO.sleep(700.millis) *> registry.report(clusterKey, outage)
      ).parMapN { (first, second, _) =>
        List(first, second).foreach { received =>
          assertEquals(received.size, 2)
          assertEquals(eventNameOf(received.head), CapabilityRoutes.EventName)
        }
      }
    }
  }

  test("disconnectingASubscriberDoesNotAffectTheOther") {
    fixture(heartbeat = 30.seconds).use { (server, registry, _) =>
      for {
        // One browser opens the stream, reads its snapshot, and goes away.
        _ <- frames(server, 1)
        // The other is still there and still gets its changes.
        received <- (
          frames(server, 2),
          IO.sleep(700.millis) *> registry.report(clusterKey, outage)
        ).parMapN((frames, _) => frames)
      } yield assertEquals(received.size, 2)
    }
  }

  test("reconnectingWithLastEventIdStillReceivesAFullSnapshot") {
    // Documented behaviour, not an oversight: the capability stream carries no cursor, because capability
    // state is small and idempotent. Resending all of it costs one frame and cannot leave a client
    // holding a half-applied history.
    fixture().use { (server, _, _) =>
      basicRequest
        .get(server.at(streamPath))
        .header("Last-Event-ID", "whatever-a-browser-remembered")
        .response(asStreamAlwaysUnsafe(sttp.capabilities.fs2.Fs2Streams[IO]))
        .send(server.backend)
        .flatMap(
          _.body
            .through(fs2.text.utf8.decode)
            .through(fs2.text.lines)
            .split(_.isEmpty)
            .map(_.toList.mkString("\n"))
            .filter(_.nonEmpty)
            .take(1)
            .compile
            .toList
        )
        .timeout(20.seconds)
        .map { received =>
          assertEquals(eventNameOf(received.head), CapabilityRoutes.EventName)
          assert(decode[CapabilitySnapshot](dataOf(received.head)).isRight, received.head)
        }
    }
  }

  test("probeReturnsTheRecomputedStateAndTriggersAPoll") {
    fixture().use { (server, registry, probed) =>
      for {
        _ <- registry.report(clusterKey, CapabilityState.Available)
        headers <- session(server)
        response <- server.post(s"$snapshotPath/cluster/probe", headers)
        asked <- probed.get
      } yield {
        assertEquals(response.code.code, 202)
        val entry = decode[CapabilityEntry](response.body).fold(error => fail(error.getMessage), identity)
        assertEquals(entry.key, clusterKey)
        assertEquals(entry.state, CapabilityState.Available)
        assertEquals(asked, List(cluster))
      }
    }
  }

  test("probeForAnUnknownServiceIsRejectedWithKuiValidation") {
    fixture().use { (server, _, probed) =>
      for {
        headers <- session(server)
        response <- server.post(s"$snapshotPath/not-a-service/probe", headers)
        asked <- probed.get
      } yield {
        val envelope = decode[ErrorEnvelope](response.body).fold(error => fail(error.getMessage), identity)
        assertEquals(envelope.code, "KUI-VALIDATION")
        // 400, not the 404 the task sketch named: `ErrorEnvelope.statusOf` is the one code-to-status
        // table in the system and maps KUI-VALIDATION to 400. The code is what the UI branches on.
        assertEquals(response.code.code, 400)
        assertEquals(asked, Nil, "an unknown service must not cause a poll")
      }
    }
  }

  test("aMidStreamFailureArrivesAsAnErrorEventRatherThanATruncatedConnection") {
    // ADR-035 exists because Kafbat closes the connection when something fails after the response
    // headers are already on the wire. A browser cannot tell that apart from a proxy timeout: it
    // reconnects, re-runs the same failing subscription, and loops with nothing on screen to say why.
    // Exactly one terminal event, and here it is the `error` half.
    val failing = new CapabilityRegistry[IO] {
      def snapshot: IO[Map[CapabilityKey, CapabilityState]] = IO.pure(Map.empty)
      def entries: IO[List[CapabilityEntry]] = IO.pure(Nil)
      def state(key: CapabilityKey): IO[CapabilityState] = IO.pure(CapabilityState.NotConfigured)
      def changes: fs2.Stream[IO, kui.contracts.capability.CapabilityChange] = subscribeStream
      def subscribe: Resource[IO, fs2.Stream[IO, kui.contracts.capability.CapabilityChange]] =
        Resource.pure(subscribeStream)
      def report(key: CapabilityKey, state: CapabilityState, name: Option[String]): IO[Unit] = IO.unit
      def probeNow(service: ServiceId): IO[Unit] = IO.unit
      def attachProbe(probe: ServiceId => IO[Unit]): IO[Unit] = IO.unit

      // The subscription survives long enough for the snapshot frame to be written, then fails --
      // the shape of a registry subscription or a telemetry meter raising mid-stream.
      private def subscribeStream: fs2.Stream[IO, kui.contracts.capability.CapabilityChange] =
        fs2.Stream.sleep[IO](300.millis) >> fs2.Stream.raiseError[IO](new RuntimeException("boom"))
    }

    val resource = for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      trigger = new Trigger[IO] {
        def probe(service: ServiceId): IO[Unit] = IO.unit
      }
      routes = CapabilityRoutes[IO](
        failing,
        trigger,
        GatewayTestServer.noTelemetry,
        logger,
        kui.http.sse.SseConfig(heartbeatInterval = 30.seconds)
      )
      server <- GatewayTestServer.resource(extraRoutes = routes)
    } yield server

    resource.use { server =>
      frames(server, 2).map { received =>
        assertEquals(eventNameOf(received.head), CapabilityRoutes.EventName)
        assertEquals(eventNameOf(received(1)), "error")
        val envelope = decode[ErrorEnvelope](dataOf(received(1)))
          .fold(error => fail(s"${dataOf(received(1))} ($error)"), identity)
        assertEquals(envelope.code, "KUI-INTERNAL")
        assert(envelope.correlationId.nonEmpty, envelope.toString)
      }
    }
  }

  test("theStreamCarriesTheGatewaysCorrelationIdLikeEveryOtherResponse") {
    fixture().use { (server, _, _) =>
      basicRequest
        .get(server.at(streamPath))
        .response(asStreamAlwaysUnsafe(sttp.capabilities.fs2.Fs2Streams[IO]))
        .send(server.backend)
        .flatMap(response =>
          response.body.take(1).compile.drain.as(response.header("X-Kui-Correlation-Id"))
        )
        .map(id => assert(id.exists(_.nonEmpty), "the stream response carries no correlation id"))
    }
  }
}
