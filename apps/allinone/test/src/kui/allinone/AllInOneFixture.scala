package kui.allinone

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.effect.IO
import cats.effect.kernel.Resource
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

import kui.cluster.api.ClusterApi
import kui.cluster.app.{ClusterServer, ClusterServiceConfig, ClusterWiring}
import kui.config.ServerConfig
import kui.config.UpstreamServiceConfig
import kui.gateway.api.client.SttpServiceClient
import kui.gateway.application.client.{CallContext, ServiceClient}
import kui.http.KuiServer
import kui.kernel.{CorrelationId, Host, Port, ServiceId, UserName}
import kui.observability.Telemetry
import kui.security.{Principal, PrincipalCodec, PrincipalKind}
import kui.testkit.fakes.FakeStructuredLogger

/** The cluster service, reachable both ways at once.
  *
  * Every suite in this module needs the same two things: the service wired exactly as
  * `services/cluster/app` wires it, and a way to call it that is either a socket or a function call. Building
  * both from the *same* `ClusterServer` value is what makes `InProcessServiceClientSuite`'s central
  * assertion meaningful — the two clients are not talking to two similar services, they are talking to one
  * service through two transports, so any difference in the answer can only have come from the transport.
  */
object AllInOneFixture {

  /** The correlation id every call in these suites carries, so a log line can be traced back to a case. */
  val Correlation: CorrelationId = CorrelationId.unsafe("0123456789abcdef")

  val Ada: Principal = Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session)

  def context: CallContext = CallContext(Ada, Correlation, None)

  /** The all-in-one codec. One instance, shared by the caller and the callee, exactly as
    * `AllInOneWiring.resource` shares it — a second instance would still interoperate, but sharing is what
    * the production code does and a fixture that diverged there would be testing something else.
    */
  val principals: PrincipalCodec[IO] = PrincipalCodec.inProcess[IO]

  /** A listener that gives its port back immediately and drains in a millisecond rather than in ten seconds,
    * because these suites start and stop it many times.
    */
  private val ephemeral: ServerConfig = ServerConfig(Host.unsafe("localhost"), Port.unsafe(0), "/")

  private val DrainForTests: FiniteDuration = 10.millis

  /** The cluster service, wired the way its own process wires it. */
  def cluster(
      logger: FakeStructuredLogger[IO],
      codec: PrincipalCodec[IO] = principals
  ): Resource[IO, ClusterServer[IO]] =
    ClusterWiring.make[IO](ClusterServiceConfig.Default, Telemetry.noop[IO], codec, logger)

  /** A client that calls the given service without a socket. */
  def inProcessClient(
      service: ClusterServer[IO],
      codec: PrincipalCodec[IO] = principals
  ): ServiceClient[IO] =
    InProcessServiceClient.make[IO](ClusterApi.Id, service.routes, service.interceptors, codec)

  /** A client that calls the given service over a real loopback socket.
    *
    * `SttpServiceClient.over` rather than `SttpServiceClient.resource`, so that the only difference between
    * this client and the in-process one is the backend underneath. That is what the comparison in
    * `InProcessServiceClientSuite` is about, and adding the resilience wrapper to one side and not the other
    * would make a difference in the answers mean two things at once.
    *
    * It also sidesteps a rule that is right in production and wrong here: `UpstreamConfig` defaults to
    * `UrlPolicy.Strict`, which refuses a loopback upstream because in a real deployment an upstream on
    * `localhost` is almost always a misconfiguration. A suite binding an ephemeral port on `localhost` is the
    * one case where a loopback address is exactly what was meant.
    */
  def httpClient(
      service: ClusterServer[IO],
      logger: FakeStructuredLogger[IO],
      codec: PrincipalCodec[IO] = principals
  ): Resource[IO, ServiceClient[IO]] =
    for {
      binding <- KuiServer.resource[IO](
        ephemeral,
        service.routes,
        service.interceptors,
        logger,
        DrainForTests
      )
      backend <- HttpClientFs2Backend.resource[IO]()
    } yield SttpServiceClient.over[IO](
      ClusterApi.Id,
      s"http://localhost:${binding.port}",
      codec,
      backend,
      // The same bound the all-in-one gives its in-process client, so this half of the comparison is
      // configured like the half it is being compared against.
      UpstreamServiceConfig.DefaultTimeout
    )

  /** Both clients over one service, ready to be asked the same question. */
  final case class BothTransports(
      inProcess: ServiceClient[IO],
      overHttp: ServiceClient[IO],
      logger: FakeStructuredLogger[IO]
  )

  def bothTransports: Resource[IO, BothTransports] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      service <- cluster(logger)
      http <- httpClient(service, logger)
    } yield BothTransports(inProcessClient(service), http, logger)

  /** The service id the gateway configures, routes and signs for. */
  val Cluster: ServiceId = ClusterApi.Id
}
