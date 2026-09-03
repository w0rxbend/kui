package kui.allinone

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*

import kui.contracts.ErrorEnvelope
import kui.gateway.application.client.ServiceClient
import kui.security.{Principal, PrincipalCodec, SignedPrincipal}
import kui.testkit.KuiIOSuite

/** That an in-process stream stops producing when its reader goes away.
  *
  * From M3 a browser tailing a topic holds one of these open for as long as the tab is open, and closes it by
  * navigating away. If cancellation did not reach the producer, every closed tab would leave a fiber — and,
  * in the real endpoint, a Kafka consumer — running until the process ended. That is the leak this suite
  * exists to catch, on the transport where it is least obvious: there is no socket to close, so nothing in
  * the operating system will notice on the code's behalf.
  *
  * The endpoint under test is [[StreamingEndpoint]] rather than a published contract, because no M0 contract
  * declares a stream yet. See that file for why it is worth inventing one.
  */
final class InProcessStreamingSuite extends KuiIOSuite {

  private val principals: PrincipalCodec[IO] = AllInOneFixture.principals

  /** Verifies the token the way a real service does, so the stream travels the authenticated path. */
  private def verify(token: SignedPrincipal): IO[Either[ErrorEnvelope, Principal]] =
    IO.realTimeInstant.flatMap(now =>
      principals
        .verify(
          token,
          AllInOneFixture.Cluster,
          kui.security.RequestDigest.ofRequestLine("GET", "/internal/v1/ticks"),
          now
        )
        .map(_.leftMap(error => ErrorEnvelope.of(kui.kernel.error.KuiError.remote(
          kui.kernel.error.ErrorCode.Unauthenticated,
          error.metricLabel,
          Nil
        ), AllInOneFixture.Correlation, now)))
    )

  private def client(produced: Ref[IO, Int]): ServiceClient[IO] =
    InProcessServiceClient.make[IO](
      AllInOneFixture.Cluster,
      List(StreamingEndpoint.route(produced, verify)),
      interceptors = Nil,
      principals
    )

  test("cancellationPropagatesForStreamingEndpoints") {
    for {
      produced <- Ref.of[IO, Int](0)
      // Take a handful of events and then stop reading. `take` completes the consumer, which is exactly
      // what a browser closing a tab does to the stream on this side of the process.
      events <- client(produced)
        .stream(StreamingEndpoint.ticks, ())(AllInOneFixture.context)
        .take(3)
        .compile
        .toList
        .timeout(10.seconds)
      atCancellation <- produced.get
      // Long enough for a producer that ignored the cancellation to emit dozens more at a 10ms tick, and
      // short enough not to slow the suite down noticeably.
      _ <- IO.sleep(300.millis)
      afterwards <- produced.get
    } yield {
      assertEquals(events.size, 3, "the consumer must receive the events it asked for")
      assertEquals(
        afterwards,
        atCancellation,
        s"the producer emitted ${afterwards - atCancellation} more events after its reader stopped; " +
          "cancellation did not reach it"
      )
    }
  }
}
