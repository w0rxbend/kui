package kui.gateway.api.client

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.IO
import cats.effect.kernel.{Ref, Resource}
import fs2.Stream
import io.circe.Json
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.{ResponseStub, StreamBackendStub, StubBody}
import sttp.model.{Header, StatusCode}

import kui.config.{SafeUrl, UpstreamServiceConfig, UrlPolicy}
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.kernel.{ClusterId, CorrelationId, PositiveInt, ServiceId, UserName}
import kui.observability.Telemetry
import kui.security.{Principal, PrincipalCodec, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** What a stubbed service does when the gateway calls it. */
enum ServiceBehaviour {
  case Ok(body: Json)
  case Failure(status: Int, body: Json)
  case Events(text: String)
  case Slow(after: FiniteDuration, andThen: ServiceBehaviour)
  case Refused
}

/** What actually went out on the wire, which is what most of GW-002's promises are about. */
final case class SentRequest(method: String, uri: String, headers: List[Header], body: String) {
  def header(name: String): Option[String] = headers.find(_.is(name)).map(_.value)
}

/** A stubbed service, plus the record of every request that reached it.
  *
  * `libs/testkit`'s `StubUpstream` would have been the natural choice and cannot be: it hands back a
  * plain `Backend[IO]`, and `SttpServiceClient` needs a `StreamBackend[IO, Fs2Streams[IO]]` so that the
  * SSE path has a stream capability to send on. Rather than widen a fixture three other lanes already
  * depend on, this suite keeps its own, which additionally records the outbound headers — the thing
  * `StubUpstream` does not keep and that half of these assertions are about.
  */
trait StubService {
  def backend: StreamBackend[IO, Fs2Streams[IO]]
  def set(behaviour: ServiceBehaviour): IO[Unit]
  def sent: IO[List[SentRequest]]
}

object ServiceClientFixture {

  final class RefusedConnection extends java.net.ConnectException("connection refused (stub)")

  val Cluster: ServiceId = ServiceId.unsafe("cluster")
  val Topic: ServiceId = ServiceId.unsafe("topic")

  val principal: Principal =
    Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session)

  def context(cluster: Option[ClusterId] = None): CallContext =
    CallContext(principal, CorrelationId.unsafe("0123456789abcdef"), cluster)

  def url(raw: String): SafeUrl =
    SafeUrl.from(raw, UrlPolicy.Dev).fold(error => sys.error(error.message), identity)

  def config(
      base: String = "http://cluster:8081",
      timeout: FiniteDuration = 10.seconds,
      maxConcurrent: Int = 4
  ): UpstreamServiceConfig =
    UpstreamServiceConfig(url(base), timeout, PositiveInt.unsafe(maxConcurrent))

  def stub(initial: ServiceBehaviour): IO[StubService] =
    for {
      behaviour <- Ref.of[IO, ServiceBehaviour](initial)
      recorded <- Ref.of[IO, Vector[SentRequest]](Vector.empty)
    } yield new StubService {
      def set(next: ServiceBehaviour): IO[Unit] = behaviour.set(next)
      def sent: IO[List[SentRequest]] = recorded.get.map(_.toList)

      val backend: StreamBackend[IO, Fs2Streams[IO]] =
        StreamBackendStub[IO, Fs2Streams[IO]](summon[sttp.monad.MonadError[IO]]).whenAnyRequest
          .thenRespondF { request =>
            recorded.update(
              _ :+ SentRequest(
                request.method.method,
                request.uri.toString,
                request.headers.toList,
                request.body.show
              )
            ) >> behaviour.get.flatMap(answer)
          }

      private def answer(behaviour: ServiceBehaviour): IO[sttp.client4.Response[StubBody]] =
        behaviour match {
          case ServiceBehaviour.Ok(body) => IO.pure(ResponseStub.adjust(body.noSpaces, StatusCode.Ok))
          case ServiceBehaviour.Failure(status, body) =>
            IO.pure(ResponseStub.adjust(body.noSpaces, StatusCode.unsafeApply(status)))
          case ServiceBehaviour.Events(text) =>
            IO.pure(
              ResponseStub.adjust(
                Stream.emits(text.getBytes("UTF-8").toList).covary[IO],
                StatusCode.Ok
              )
            )
          case ServiceBehaviour.Slow(after, next) => IO.sleep(after) >> answer(next)
          case ServiceBehaviour.Refused => IO.raiseError(new RefusedConnection)
        }
    }

  /** A client wired to a stub, with the in-process (unsigned but claim-carrying) principal codec.
    *
    * The in-process codec is the right one here: these assertions are about *which claims* the gateway
    * puts in the token — audience, digest — and it renders them as plain JSON, so the suite can read them
    * back without a key pair. KERN-006's `JwsPrincipalCodec` proves the signing itself.
    */
  def client(
      service: ServiceId,
      stub: StubService,
      config: UpstreamServiceConfig = config()
  ): Resource[IO, ServiceClient[IO]] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      client <- SttpServiceClient.resource[IO](
        service,
        config,
        PrincipalCodec.inProcess[IO],
        Telemetry.noop[IO],
        logger,
        stub.backend
      )
    } yield client
}
