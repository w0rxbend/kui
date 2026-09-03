package kui.http.upstream

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import sttp.client4.Backend
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.model.StatusCode

import kui.config.{SafeUrl, UrlPolicy}
import kui.kernel.{PositiveInt, ServiceId}
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger

/** What a stubbed upstream does when it is called. */
enum ResponseKind {
  case Ok
  case Status(code: Int)

  /** Refuses the connection, the way a machine that is down does. */
  case Refused

  /** Answers, but only after a while. Used with virtual time. */
  case Slow(after: FiniteDuration, andThen: ResponseKind)

  /** Never answers at all, so the caller's own timeout is what ends the call. */
  case Never
}

/** A stubbed upstream that remembers how it was used.
  *
  * The counts are the point. Several of HTTP-003's promises can only be checked by watching the
  * backend rather than the result: "an open circuit does not call the backend" is a statement about
  * calls that did *not* happen, and "the bulkhead caps concurrency" is about how many overlapped.
  */
trait RecordingUpstream {
  def backend: Backend[IO]

  /** How many requests reached this backend. */
  def calls: IO[Int]

  /** The most that were ever in flight at the same moment. */
  def peakInFlight: IO[Int]

  /** The hosts that were contacted, in order. */
  def hosts: IO[List[String]]

  /** Changes what it answers from now on. */
  def set(kind: ResponseKind): IO[Unit]

  /** Changes what it answers for one host only. */
  def setFor(host: String, kind: ResponseKind): IO[Unit]
}

object UpstreamFixture {

  final class RefusedConnection extends java.net.ConnectException("connection refused (stub)")

  def url(raw: String): SafeUrl =
    SafeUrl.from(raw, UrlPolicy.Dev).fold(error => sys.error(error.message), identity)

  /** A config with everything turned down to test-sized numbers, and the development URL policy so
    * that loopback stubs are reachable.
    */
  def config(name: String, urls: NonEmptyList[SafeUrl]): UpstreamConfig =
    UpstreamConfig(
      name = name,
      urls = urls,
      callTimeout = 10.seconds,
      maxConcurrent = PositiveInt.unsafe(4),
      maxRetries = 2,
      failureThreshold = PositiveInt.unsafe(3),
      resetTimeout = 30.seconds,
      failoverGrace = 5.seconds,
      retryBase = 100.milliseconds,
      urlPolicy = UrlPolicy.Dev
    )

  def single(name: String = "registry", base: String = "http://registry-a:8081"): UpstreamConfig =
    config(name, NonEmptyList.one(url(base)))

  /** A client wired to a stub, with no telemetry and a fake logger. */
  def client(config: UpstreamConfig, backend: Backend[IO]): Resource[IO, UpstreamClient[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      client <- UpstreamClient.resource[IO](
        config,
        backend,
        Telemetry.noop[IO],
        ServiceId.unsafe("gateway"),
        logger
      )
    } yield client

  /** The same, keeping the logger so a suite can read what was written. */
  def clientAndLog(
      config: UpstreamConfig,
      backend: Backend[IO]
  ): Resource[IO, (UpstreamClient[IO], FakeStructuredLogger[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      client <- UpstreamClient.resource[IO](
        config,
        backend,
        Telemetry.noop[IO],
        ServiceId.unsafe("gateway"),
        logger
      )
    } yield (client, logger)

  def recording(initial: ResponseKind): IO[RecordingUpstream] =
    for {
      behaviour <- Ref.of[IO, ResponseKind](initial)
      perHost <- Ref.of[IO, Map[String, ResponseKind]](Map.empty)
      counter <- Ref.of[IO, Int](0)
      inFlight <- Ref.of[IO, Int](0)
      peak <- Ref.of[IO, Int](0)
      contacted <- Ref.of[IO, Vector[String]](Vector.empty)
    } yield new RecordingUpstream {

      def calls: IO[Int] = counter.get
      def peakInFlight: IO[Int] = peak.get
      def hosts: IO[List[String]] = contacted.get.map(_.toList)
      def set(kind: ResponseKind): IO[Unit] = behaviour.set(kind)
      def setFor(host: String, kind: ResponseKind): IO[Unit] = perHost.update(_.updated(host, kind))

      val backend: Backend[IO] = BackendStub[IO](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
        .thenRespondF { request =>
          val host = request.uri.host.getOrElse("")

          val enter = counter.update(_ + 1) *>
            contacted.update(_ :+ host) *>
            inFlight.updateAndGet(_ + 1).flatMap(now => peak.update(math.max(_, now)))

          val leave = inFlight.update(_ - 1)

          // `guarantee` and not `flatMap`: a call the caller cancels — which is what a timeout
          // does — must still leave the in-flight count correct, or the bulkhead assertions would
          // be measuring the fixture's own bug.
          enter.bracket(_ =>
            for {
              overrides <- perHost.get
              fallback <- behaviour.get
              response <- answer(overrides.getOrElse(host, fallback))
            } yield response
          )(_ => leave)
        }

      private def answer(kind: ResponseKind): IO[sttp.client4.Response[StubBody]] =
        kind match {
          case ResponseKind.Ok => IO.pure(ResponseStub.adjust("""{"ok":true}""", StatusCode.Ok))
          case ResponseKind.Status(code) =>
            IO.pure(ResponseStub.adjust("""{"upstream":"detail"}""", StatusCode.unsafeApply(code)))
          case ResponseKind.Slow(after, next) => IO.sleep(after) *> answer(next)
          case ResponseKind.Refused => IO.raiseError(new RefusedConnection)
          case ResponseKind.Never => IO.never
        }
    }
}
