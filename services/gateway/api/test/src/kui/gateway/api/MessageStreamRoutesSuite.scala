package kui.gateway.api

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.sse.SseEvent
import kui.http.upstream.CircuitEvent
import kui.kernel.error.KuiError
import kui.kernel.ServiceId
import kui.security.SignedPrincipal

/** The browse relay's terminal-event promise, driven through a real listener.
  *
  * The message browse is the **first** of this module's three relays and the one the other two were copied
  * from, and the rule all three are written around — a browser never sees an SSE connection just stop — was
  * held by nothing here: replacing `StreamProxy.withTerminalEvent(upstream, …)` in `relay` with a bare
  * `upstream` left all 44 gateway suites and 397 cases green, measured in wave 8. `StreamProxySuite` tests
  * the helper in isolation; this is the case that says the relay calls it.
  *
  * The rest of this relay's behaviour — cancellation, the prefix rewrite, the untouched frames — is asserted
  * through the full browse path in the message service's own suites and in `e2e/messages.spec.ts`, so this
  * file deliberately holds one case rather than a fourth copy of a rig.
  */
final class MessageStreamRoutesSuite extends CatsEffectSuite {

  private val message = ServiceId.unsafe("message")
  private val path = "/api/v1/clusters/prod-eu/topics/orders/messages/stream"

  private def client(source: Stream[IO, SseEvent]): IO[(ServiceClient[IO], Ref[IO, Int])] =
    Ref.of[IO, Int](0).map { opened =>
      val serviceClient = new ServiceClient[IO] {
        val service: ServiceId = message

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
          Stream.eval(opened.update(_ + 1)).drain ++ source

        def circuitStates: Stream[IO, CircuitEvent] = Stream.empty
      }

      (serviceClient, opened)
    }

  test("a browse that ends without a terminal event reaches the browser as an error frame") {
    // A browse whose upstream died mid-body is the exact failure ADR-035 exists for: the browser has read
    // some records and the connection stops, so it can only draw "the search finished, apparently" — and a
    // user then reads a truncated result as the whole of what is on the topic.
    val source = Stream.emit(SseEvent.data("record", Json.obj("offset" -> Json.fromInt(7)))).covary[IO]

    for {
      built <- client(source)
      (upstream, opened) = built
      answer <- GatewayTestServer
        .resource(extraRoutes = MessageStreamRoutes[IO](upstream))
        .use { server =>
          basicRequest
            .get(server.at(path))
            .response(asStreamAlwaysUnsafe(Fs2Streams[IO]))
            .send(server.backend)
            .flatMap(response => response.body.compile.to(Array).map(response.code.code -> _))
        }
      (status, body) = answer
      calls <- opened.get
    } yield {
      assertEquals(status, 200)
      assertEquals(calls, 1, "the relay did not open the upstream browse")

      val frames = SseFrames.parse(body)
      assertEquals(
        frames.map(_.name),
        List("record", "error"),
        "the upstream's own record must be relayed unchanged and the missing terminal supplied after it"
      )

      val envelope = SseFrames.terminalError(body)
      assertEquals(envelope.code, "KUI-UPSTREAM-UNAVAILABLE")
      assert(
        envelope.message.contains(MessageStreamRoutes.Upstream),
        s"the terminal frame does not say which upstream went away: ${envelope.message}"
      )
    }
  }
}
