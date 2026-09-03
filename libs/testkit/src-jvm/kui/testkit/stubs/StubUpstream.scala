package kui.testkit.stubs

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.Json
import sttp.client4.Backend
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.model.StatusCode

/** How the stubbed upstream behaves for the next call.
  *
  * The four cases are the failures ADR-037's resilience rules exist for, and every one of them is hard to
  * produce against a real service on purpose: an upstream that is merely slow, one that answers with a status
  * nobody expected, and one that is not listening at all. A retry policy, a timeout and a circuit breaker
  * cannot be tested without being able to cause these.
  */
enum UpstreamBehaviour {
  case Ok(body: Json)
  case Status(code: Int, body: Json)

  /** Waits, then behaves as `andThen`. Nesting rather than a flag so that "slow, and then fails" is
    * expressible, which is the case that breaks naive timeout handling.
    */
  case Slow(delay: FiniteDuration, andThen: UpstreamBehaviour)

  case ConnectionRefused
}

/** One call the upstream received, for asserting what a client actually sent — how many retries, with which
  * headers, to which path.
  */
final case class RecordedRequest(method: String, uri: String, body: String)

/** A fake HTTP upstream a test can reconfigure while a client is running.
  *
  * It is a hand-written fake over sttp's stub backend rather than a mocking framework (ADR-018): the
  * behaviour is a value, so a test reads as "the upstream starts failing, then recovers" instead of as a
  * sequence of expectations.
  */
trait StubUpstream {

  /** The backend to hand to the client under test. */
  def backend: Backend[IO]

  /** Changes how the upstream answers from the next call onward. */
  def set(behaviour: UpstreamBehaviour): IO[Unit]

  /** Every call received so far, oldest first. */
  def requests: IO[List[RecordedRequest]]

  /** Forgets the recorded calls, for a test with several phases. */
  def reset: IO[Unit]
}

object StubUpstream {

  /** What a refused connection looks like to a client: the same exception a real one raises. */
  final class ConnectionRefusedException extends RuntimeException("connection refused (stub upstream)")

  def apply(initial: UpstreamBehaviour = UpstreamBehaviour.Ok(Json.obj())): IO[StubUpstream] =
    for {
      behaviour <- Ref.of[IO, UpstreamBehaviour](initial)
      recorded <- Ref.of[IO, Vector[RecordedRequest]](Vector.empty)
    } yield new StubUpstream {

      def set(next: UpstreamBehaviour): IO[Unit] = behaviour.set(next)

      def requests: IO[List[RecordedRequest]] = recorded.get.map(_.toList)

      def reset: IO[Unit] = recorded.set(Vector.empty)

      val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF { request =>
          val record = recorded.update(
            _ :+ RecordedRequest(
              request.method.method,
              request.uri.toString,
              request.body.show
            )
          )
          record >> behaviour.get.flatMap(answer)
        }

      private def answer(behaviour: UpstreamBehaviour): IO[sttp.client4.Response[StubBody]] =
        behaviour match {
          case UpstreamBehaviour.Ok(body) =>
            IO.pure(ResponseStub.adjust(body.noSpaces, StatusCode.Ok))
          case UpstreamBehaviour.Status(code, body) =>
            IO.pure(ResponseStub.adjust(body.noSpaces, StatusCode.unsafeApply(code)))
          case UpstreamBehaviour.Slow(delay, next) => IO.sleep(delay) >> answer(next)
          case UpstreamBehaviour.ConnectionRefused => IO.raiseError(new ConnectionRefusedException)
        }
    }
}
