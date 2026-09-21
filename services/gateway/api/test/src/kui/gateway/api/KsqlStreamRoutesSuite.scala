package kui.gateway.api

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.{Deferred, IO, Ref}
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.gateway.api.routing.{PolicyRbacPreCheck, RbacPreCheck}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.{Sse, SseEvent}
import kui.http.upstream.CircuitEvent
import kui.kernel.error.KuiError
import kui.kernel.{ClusterId, ServiceId}
import kui.ksql.contract.KsqlStreamEndpoint
import kui.ksql.contract.dto.QueryRowDto
import kui.security.SignedPrincipal
import kui.security.rbac.{Action, ClusterFlags, DefaultRole, RbacPolicy, Resource as RbacResource}
import kui.testkit.fakes.FakeStructuredLogger

/** The push-query relay's gateway-specific promises, exercised through a real listener.
  *
  * The same five questions `AlertsStreamRoutesSuite` asks of the alerts relay, because it is the same
  * mechanism and the mistakes available to it are the same ones. Two are worth restating for this route: the
  * statement is a query parameter, which is the input the derivation would have decoded and the relay
  * forwards untouched; and the permission is `KSQL:EXECUTE` rather than a read, because a push query is a
  * query ksqlDB runs and holds open.
  */
final class KsqlStreamRoutesSuite extends CatsEffectSuite {

  private val ksql = ServiceId.unsafe("ksql")
  private val cluster = ClusterId.unsafe("prod-eu")
  private val statement = "SELECT * FROM orders EMIT CHANGES"

  private val path = "/api/v1/clusters/prod-eu/ksql/stream"

  /** The public address with the statement attached as a query parameter.
    *
    * `addParam` rather than a pasted query string, so the spaces and the asterisk are escaped by the client
    * rather than by hand. **The statement carries no trailing `;`, and that is measured rather than tidy:**
    * `;` is a sub-delimiter RFC 3986 allows in a query, sttp therefore leaves it literal, and the server
    * reads a literal `;` as a *parameter separator* — so a statement sent that way arrives at the relay
    * truncated at the semicolon, which is where every real ksqlDB statement ends. Sending the escaped form
    * `%3B` instead was tried here and arrives intact, which is what a browser's `encodeURIComponent` does.
    * Both directions were run. The hazard is for a hand-written `curl`, not for the screen.
    */
  private def address(server: GatewayTestServer.Running): sttp.model.Uri =
    server.at(path).addParam(KsqlStreamEndpoint.StatementParam, statement)

  final private case class Opened(endpoint: String, path: String, context: CallContext, input: Any)

  private def client(
      source: Stream[IO, SseEvent]
  ): IO[(ServiceClient[IO], Ref[IO, List[Opened]])] =
    Ref.of[IO, List[Opened]](Nil).map { opened =>
      val serviceClient = new ServiceClient[IO] {
        val service: ServiceId = ksql

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
          Stream
            .eval(
              opened.update(
                _ :+ Opened(
                  endpoint.info.name.getOrElse("<unnamed>"),
                  endpoint.showPathTemplate(),
                  ctx,
                  input
                )
              )
            )
            .drain ++ source

        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty
      }

      (serviceClient, opened)
    }

  private def request(server: GatewayTestServer.Running): IO[(Int, Array[Byte])] =
    basicRequest
      .get(address(server))
      .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
      .send(server.backend)
      .flatMap(response => response.body.compile.to(Array).map(response.code.code -> _))

  /** The status, and whatever body arrives within [[BodyWindow]]. See `AlertsStreamRoutesSuite` for why a
    * case asserting a refusal on a streaming route must not read the body to completion.
    */
  private def boundedRead(server: GatewayTestServer.Running): IO[(Int, String)] =
    basicRequest
      .get(address(server))
      .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
      .send(server.backend)
      .flatMap(response =>
        response.body
          .interruptAfter(BodyWindow)
          .compile
          .to(Array)
          .map(bytes => response.code.code -> new String(bytes, StandardCharsets.UTF_8))
      )

  private val BodyWindow: FiniteDuration = 2.seconds

  /** One `row` frame, rendered by the ksql contract's own encoder rather than written out as JSON.
    *
    * The relay never looks inside a frame, so the payload could be anything — but a hand-written literal here
    * would be a fourth spelling of a wire this project has already been bitten by having three of, and it
    * would stop telling the truth the moment the DTO moved.
    */
  private def row(values: String*): SseEvent =
    SseEvent.data(KsqlStreamEndpoint.EventName, QueryRowDto(values.toList.map(Some(_))).asJson)

  test("the public route keeps the ksql stream endpoint identity and rewrites only its prefix") {
    val internal = KsqlStreamEndpoint.endpoint[IO]
    val public = KsqlStreamRoutes.publicEndpoint[IO]

    assertEquals(public.showPathTemplate(), "/api/v1/clusters/{clusterId}/ksql/stream?statement={statement}")
    assertEquals(public.info.name, internal.info.name)
    assertEquals(public.info.summary, internal.info.summary)
    assertEquals(
      public.attribute(kui.contracts.rbac.EndpointAuthorization.Key),
      internal.attribute(kui.contracts.rbac.EndpointAuthorization.Key)
    )
  }

  test("the public route relays the ksql service's bytes exactly, statement and all") {
    val envelope = ErrorEnvelope(
      code = "KUI-UPSTREAM-KSQL",
      message = "the ksqlDB server ended the query",
      details = Nil,
      correlationId = "0123456789abcdef",
      timestamp = java.time.Instant.parse("2026-09-11T10:00:00Z"),
      retryable = true
    )
    val events = List(row("1", "espresso"), SseEvent.heartbeat, SseEvent.error(envelope))
    val source = Stream.emits(events).covary[IO]

    for {
      expected <- Sse.encode(source).compile.to(Array)
      built <- client(source)
      (upstream, opened) = built
      answer <- GatewayTestServer
        .resource(extraRoutes = KsqlStreamRoutes[IO](upstream, RbacPreCheck.allowAll[IO]))
        .use(request)
      (status, actual) = answer
      calls <- opened.get
    } yield {
      assertEquals(status, 200)
      assertEquals(actual.toList, expected.toList)
      assertEquals(calls.map(_.endpoint), List("ksql.stream"))
      assertEquals(
        calls.map(_.path),
        List("/internal/v1/clusters/{clusterId}/ksql/stream?statement={statement}")
      )
      assertEquals(calls.flatMap(_.context.cluster), List(cluster))
      // The statement reaches the service as it was typed. It is the one input this relay carries that the
      // alerts and message relays do not, it travels in the query string because `EventSource` sends no
      // body, and a relay that dropped or re-encoded it would run a different query from the one on screen.
      assertEquals(calls.map(_.input), List((cluster, statement)), calls.map(_.input).toString)
    }
  }

  test("a ksql stream that ends without a terminal event reaches the browser as an error frame") {
    // The same rule as `AlertsStreamRoutesSuite`'s, closed on this relay in the same case shape and for the
    // same reason: `StreamProxySuite` exercises `withTerminalEvent` in isolation, and nothing but a case
    // like this one asserts that a *relay* calls it. Replacing `StreamProxy.withTerminalEvent(upstream, …)`
    // in `relay` with a bare `upstream` leaves every other case in this file green.
    //
    // It matters more here than anywhere else in the product. A push query over a live topic is silent
    // whenever the topic is silent, so a browser has no way at all to tell "no orders yet" from "the ksql
    // process died" except by the frame that says which.
    val source = Stream.emit(row("2", "cortado")).covary[IO]

    for {
      built <- client(source)
      (upstream, _) = built
      answer <- GatewayTestServer
        .resource(extraRoutes = KsqlStreamRoutes[IO](upstream, RbacPreCheck.allowAll[IO]))
        .use(request)
      (status, body) = answer
    } yield {
      assertEquals(status, 200)

      val frames = SseFrames.parse(body)
      assertEquals(
        frames.map(_.name),
        List(KsqlStreamEndpoint.EventName, "error"),
        "the upstream's own row must be relayed unchanged and the missing terminal supplied after it"
      )

      val envelope = SseFrames.terminalError(body)
      assertEquals(envelope.code, "KUI-UPSTREAM-UNAVAILABLE")
      assert(
        envelope.message.contains(KsqlStreamRoutes.Upstream),
        s"the terminal frame does not say which upstream went away: ${envelope.message}"
      )
      assert(envelope.retryable, "an upstream that went away is worth reconnecting to")
    }
  }

  test("cancelling the browser response cancels the upstream ksql query") {
    // The chain M9 needs: the browser tab closes, the relay is cancelled, the ksql service's fiber is
    // cancelled, and the push query it opened against ksqlDB is terminated. A push query left running after
    // its reader has gone is a query ksqlDB keeps executing forever, which is the one resource leak this
    // service can create that outlives the process that asked for it.
    for {
      started <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      source = (
        Stream.eval(started.complete(())).drain ++ Stream.emit(row("3", "flat white")) ++
          Stream.never[IO]
      ).onFinalize(cancelled.complete(()).void)
      built <- client(source)
      (upstream, _) = built
      _ <- GatewayTestServer
        .resource(extraRoutes = KsqlStreamRoutes[IO](upstream, RbacPreCheck.allowAll[IO]))
        .use { server =>
          basicRequest
            .get(address(server))
            .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
            .send(server.backend)
            .flatMap(response => response.body.take(1).compile.drain)
        }
      _ <- started.get.timeout(5.seconds)
      _ <- cancelled.get.timeout(5.seconds)
    } yield assert(true)
  }

  test("a principal without KSQL:EXECUTE is refused the push query before ksqlDB is asked") {
    // Both directions, over the gateway's real `PolicyRbacPreCheck`, for the reason the alerts suite states:
    // a case injecting `denyAll` refuses a caller holding every grant in the vocabulary just as readily as
    // one holding none, so the endpoint's own `ResourceRequirement` could be changed to anything and the
    // whole gateway suite would stay green.
    //
    // `KSQL:VIEW` is the interesting half of the refusal and is why this case does not simply withhold the
    // whole resource: a reader granted only VIEW may list ksqlDB's objects and must not be able to start a
    // query that runs until somebody stops it. `Action.closure` expands EXECUTE to include VIEW and not the
    // other way round, and this is where that direction is worth something.
    for {
      logger <- FakeStructuredLogger[IO]
      source = Stream.emit(row("4", "lungo")).covary[IO] ++ Stream.never[IO]
      built <- client(source)
      (upstream, opened) = built
      check = (policy: RbacPolicy) =>
        new PolicyRbacPreCheck[IO](policy, _ => IO.pure(ClusterFlags.Writable), logger)
      refused <- GatewayTestServer
        .resource(extraRoutes = KsqlStreamRoutes[IO](upstream, check(ksqlViewOnly)))
        .use(boundedRead)
      afterRefusal <- opened.get
      allowedResponse <- GatewayTestServer
        .resource(extraRoutes = KsqlStreamRoutes[IO](upstream, check(ksqlExecuteOnly)))
        .use { server =>
          basicRequest
            .get(address(server))
            .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
            .send(server.backend)
            .flatMap(response => response.body.take(1).compile.drain.as(response.code.code))
        }
      afterAllowance <- opened.get
    } yield {
      val (refusedStatus, refusedBody) = refused
      assertEquals(refusedStatus, 403, refusedBody)
      val envelope = decode[ErrorEnvelope](refusedBody).fold(error => fail(error.getMessage), identity)
      assertEquals(envelope.code, "KUI-FORBIDDEN")
      // The point of the case: no upstream subscription, so a caller who may not run a query costs the ksql
      // service nothing and costs ksqlDB nothing — no connection, no fiber, no query left executing.
      assertEquals(afterRefusal, Nil, "a caller with only KSQL:VIEW opened a push query")

      assertEquals(allowedResponse, 200)
      assertEquals(afterAllowance.size, 1, afterAllowance.toString)
    }
  }

  /** `KSQL:VIEW` and nothing else in the vocabulary: a reader who may list ksqlDB's objects. */
  private val ksqlViewOnly: RbacPolicy =
    RbacPolicy(
      Nil,
      Some(DefaultRole(List(RbacPolicy.permission(RbacResource.Ksql, None, Set(Action.KsqlView)))))
    )

  /** `KSQL:EXECUTE` and nothing else. `Action.closure` adds `KsqlView` to it, which is the implication the
    * contract's object listing relies on and this case's allowed half relies on too.
    */
  private val ksqlExecuteOnly: RbacPolicy =
    RbacPolicy(
      Nil,
      Some(DefaultRole(List(RbacPolicy.permission(RbacResource.Ksql, None, Set(Action.KsqlExecute)))))
    )
}
