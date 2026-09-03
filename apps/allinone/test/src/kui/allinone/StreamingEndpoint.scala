package kui.allinone

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import io.circe.Json
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.{stringToPath, Endpoint}

import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.http.sse.{Sse, SseEvent}
import kui.security.{Principal, SignedPrincipal}

/** A Server-Sent Events endpoint that exists only for this module's suites.
  *
  * ==Why it is invented here rather than borrowed==
  *
  * No M0 contract publishes a streaming endpoint. `/api/v1/capabilities/stream` is the gateway's own route
  * and is not proxied, and the cluster service serves a single request/response echo. So the property AIO-001
  * asks for — that cancelling an in-process stream stops the producer, rather than leaking a fiber that runs
  * until the process ends — has nothing real to be asserted against yet.
  *
  * It matters anyway, and it matters now. From M3 the message browser tails a topic through exactly this
  * path, one open stream per watching browser, and a transport that ignored cancellation would leak one
  * consumer per closed tab. Finding that out in M3, in the shape that is hardest to reproduce, would be much
  * more expensive than declaring one endpoint here.
  *
  * It is deliberately shaped like a real one: `KuiEndpoint.internal`, so it carries the signed principal and
  * the shared error envelope, under `/internal/v1`, with `Sse.body` as its output — which is the only way a
  * KUI service is allowed to declare a stream.
  */
object StreamingEndpoint {

  /** How often the endpoint emits while nothing is cancelling it. Short, so a suite spends milliseconds
    * rather than seconds establishing that events are flowing.
    */
  private val Tick = 10.millis

  val ticks: Endpoint[SignedPrincipal, Unit, ErrorEnvelope, Stream[IO, Byte], Fs2Streams[IO]] =
    KuiEndpoint.internal.get
      .in("internal" / "v1" / "ticks")
      .out(Sse.body[IO])
      .name("test.ticks")

  /** The route, plus the count of events it has actually produced.
    *
    * The counter is the assertion's subject. "Cancellation propagated" is not observable from the consumer's
    * side — a consumer that stops reading looks the same whether the producer stopped or carried on — so the
    * only honest way to check it is to ask the producer how much work it did after the consumer went away.
    *
    * @param verify
    *   how the token is checked. It is the same codec the caller signs with, so this route exercises the
    *   real principal path rather than skipping it: a streaming endpoint that forgot to authenticate would
    *   be a much worse bug than a leaked fiber.
    */
  def route(
      produced: Ref[IO, Int],
      verify: SignedPrincipal => IO[Either[ErrorEnvelope, Principal]]
  ): ServerEndpoint[Fs2Streams[IO], IO] =
    ticks
      .serverSecurityLogic[Principal, IO](verify)
      .serverLogicSuccess(_ =>
        _ =>
          IO.pure(
            Sse.encode(
              Stream
                .awakeEvery[IO](Tick)
                .evalTap(_ => produced.update(_ + 1))
                .map(elapsed => SseEvent.data("tick", Json.fromLong(elapsed.toMillis)))
            )
          )
      )
}
