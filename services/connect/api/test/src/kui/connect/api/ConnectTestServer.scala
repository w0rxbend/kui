package kui.connect.api

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.kernel.Resource
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import org.typelevel.otel4s.metrics.MeterProvider
import sttp.client4.*
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.model.Uri
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.connect.application.*
import kui.connect.domain.*
import kui.contracts.KuiEndpoint
import kui.http.principal.{PrincipalVerification, RbacGuard}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, ConnectName, ConnectorName, RoleName, Secret, TaskId, UserName}
import kui.security.*
import kui.security.rbac.{ClusterFlags, RbacPolicy}
import kui.testkit.fakes.FakeStructuredLogger

/** This service's routes over Tapir's stub interpreter: the real interceptor chain, the real principal
  * verification and the real permission guard, with no socket.
  *
  * A bound port would add seconds per case and prove only that Netty works, which `libs/http` proves once for
  * every service. What cannot be proved anywhere else is the *order* — that a caller without
  * `CONNECT:OPERATE` is refused before the worker is called — and the order only exists once the guard, the
  * route and the use case are assembled the way the composition root assembles them.
  */
object ConnectTestServer {

  val cluster: ClusterId = ClusterId.unsafe("prod-eu")
  val readOnly: ClusterId = ClusterId.unsafe("prod-us")
  val bare: ClusterId = ClusterId.unsafe("no-connect")

  val payments: ConnectName = ConnectName.unsafe("payments")
  val analytics: ConnectName = ConnectName.unsafe("analytics")

  val elastic: ConnectorName = ConnectorName.unsafe("elastic-sink")

  val at: Instant = Instant.parse("2020-01-01T00:00:00Z")

  val operator: RoleName = RoleName.unsafe("operator")

  /** The rebalance refusal, exactly as `ConnectHttp` builds it from a worker's 409. */
  val rebalancing: KuiError = ApplicationError.Refused(
    ErrorCode.ConnectRebalancing,
    s"the Kafka Connect cluster '${analytics.value}' is rebalancing and cannot answer yet"
  )

  /** A worker that answers what it was told to and records **every** call it was asked to make, refused or
    * not.
    *
    * That counter is the whole point of this fixture: a stub that answered a constant could not tell a caller
    * who was refused from one who was never allowed to reach it, and "refused before the worker is called" is
    * a statement about which of those happened.
    */
  final class CountingWorker(
      answer: Either[KuiError, ConnectorFacts],
      val asked: Ref[IO, List[(ConnectorName, ConnectorOperation)]],
      val reads: Ref[IO, Int]
  ) extends ConnectWorkerPort[IO] {

    def connectors: IO[Either[KuiError, ConnectorFacts]] = reads.update(_ + 1).as(answer)

    def operate(connector: ConnectorName, operation: ConnectorOperation): IO[Either[KuiError, Unit]] =
      asked.update(_ :+ (connector, operation)).as(().asRight[KuiError])
  }

  final class Source(workers: Map[(ClusterId, ConnectName), ConnectWorkerPort[IO]])
      extends ClusterConnectSource[IO] {

    private val views = List(
      ConnectProfileView(cluster, "Production EU", readOnly = false, connects = List(payments)),
      ConnectProfileView(readOnly, "Production US", readOnly = true, connects = List(payments)),
      ConnectProfileView(bare, "Staging", readOnly = false, connects = Nil)
    )

    def profileOf(id: ClusterId): IO[Either[KuiError, ConnectProfileView]] =
      IO.pure(
        views
          .find(_.cluster == id)
          .toRight(ApplicationError.NotFound("cluster", id.value, ErrorCode.ClusterNotFound): KuiError)
      )

    def all: IO[List[ConnectProfileView]] = IO.pure(views)

    def worker(id: ClusterId, connect: ConnectName): IO[Option[ConnectWorkerPort[IO]]] =
      IO.pure(workers.get((id, connect)))
  }

  /** Thirty-two bytes, which is the shortest key HS256 accepts. */
  private val key: SigningKey =
    SigningKey("test-1", Secret(Array.fill[Byte](32)(7)), Instant.parse("2020-01-01T00:00:00Z"))

  val codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(key), "kui-gateway")
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  /** @param routes
    *   exactly the list `ConnectApi.routes` built, so that a case can assert what the composition root
    *   serves rather than only what a request happened to reach. Handed out beside the backend because the
    *   stub interpreter keeps no readable record of what it was given.
    */
  final case class Rig(backend: Backend[IO], worker: CountingWorker, routes: List[ServerEndpoint[Any, IO]])

  /** The service, with the deployment's policy and whatever the worker is going to say. */
  def resource(
      rbac: RbacPolicy = RbacPolicy.Disabled,
      answer: Either[KuiError, ConnectorFacts] = Right(ConnectorFacts.complete(List(connector()))),
      onEveryCluster: Boolean = true
  ): Resource[IO, Rig] =
    Resource.eval(
      for {
        logger <- FakeStructuredLogger[IO]
        meter <- MeterProvider.noop[IO].get("kui.connect")
        rejections <- PrincipalVerification.rejectionCounter[IO](meter)
        interceptors <- ConnectApi.interceptors[IO](kui.observability.Telemetry.noop[IO], rejections, logger)
        asked <- Ref.of[IO, List[(ConnectorName, ConnectorOperation)]](Nil)
        reads <- Ref.of[IO, Int](0)
        worker = new CountingWorker(answer, asked, reads)
        workers =
          if onEveryCluster then Map((cluster, payments) -> worker, (readOnly, payments) -> worker)
          else Map((cluster, payments) -> (worker: ConnectWorkerPort[IO]))
        sources = new Source(workers)
        guard = MutationGuard.make[IO](sources, ConnectorOperationSink.noop[IO], logger)
        useCases = ConnectUseCases.make[IO](sources, guard)
        permissions = RbacGuard.fromPolicy[IO](rbac, id => ClusterFlags(id == readOnly), logger)
        // `ConnectApi.routes`, not a second assembly of the same list. Building
        // `HealthEndpoints.make ++ ConnectRoutes` here meant this rig could not see an endpoint being
        // dropped from the composition root: every route case would have gone on passing against a list the
        // product does not serve. `services/alerts` has `AlertsTestServer.compositionRoutes` for exactly
        // this reason, and it is the same defect one level further back.
        routes = ConnectApi.routes[IO](
          useCases,
          Nil,
          ConnectCapabilities.make[IO](sources, logger),
          codec,
          rejections,
          logger,
          permissions
        )
      } yield Rig(
        TapirStubInterpreter(interceptors, BackendStub[IO](summon))
          .whenServerEndpointsRunLogic(routes)
          .backend(),
        worker,
        routes
      )
    )

  def connector(
      name: String = "elastic-sink",
      state: String = "RUNNING",
      tasks: List[ConnectorTask] = List(
        ConnectorTask(TaskId.unsafe(0), ConnectorState("RUNNING"), Some("10.0.0.1:8083"), None),
        ConnectorTask(
          TaskId.unsafe(1),
          ConnectorState("FAILED"),
          Some("10.0.0.2:8083"),
          Some("org.apache.kafka.connect.errors.ConnectException: connection refused to es-01:9200\n\tat x")
        )
      )
  ): Connector =
    Connector(
      connect = payments,
      name = ConnectorName.unsafe(name),
      kind = ConnectorKind.Sink,
      state = ConnectorState(state),
      workerId = Some("10.0.0.1:8083"),
      trace = None,
      tasks = tasks
    )

  def connectorsPath(id: ClusterId): String = s"/internal/v1/clusters/${id.value}/connect/connectors"

  def operationPath(id: ClusterId, connect: String, connector: String, verb: String): String =
    s"/internal/v1/clusters/${id.value}/connect/$connect/connectors/$connector/$verb"

  /** `http://connect<path>`, parsed rather than interpolated: sttp's `uri` interpolator escapes an embedded
    * string as one segment, which would turn every path here into a single literal and every request into a
    * 404 that had nothing to do with the case.
    */
  def address(path: String): Uri = Uri.unsafeParse(s"http://connect$path")

  /** A token for one request line. The digest covers the method and the *path*; a query string is outside it
    * (ADR-020).
    */
  def token(
      method: String,
      path: String,
      roles: Set[RoleName] = Set.empty,
      validFor: FiniteDuration = 60.seconds
  ): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      codec.sign(
        PrincipalClaims(
          subject = UserName.unsafe("alice"),
          roles = roles,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(validFor.toSeconds),
          audience = ConnectApi.Id,
          requestDigest = RequestDigest.ofRequestLine(method, path.takeWhile(_ != '?'))
        )
      )
    )

  def get(rig: Rig, path: String, roles: Set[RoleName] = Set.empty): IO[Response[String]] =
    token("GET", path, roles).flatMap(signed =>
      basicRequest
        .get(address(path))
        .header(KuiEndpoint.PrincipalHeader, signed.value)
        .response(asStringAlways)
        .send(rig.backend)
    )

  def post(rig: Rig, path: String, roles: Set[RoleName] = Set.empty): IO[Response[String]] =
    token("POST", path, roles).flatMap(signed =>
      basicRequest
        .post(address(path))
        .header(KuiEndpoint.PrincipalHeader, signed.value)
        .header(kui.contracts.HttpHeaders.Csrf, "test-csrf")
        .response(asStringAlways)
        .send(rig.backend)
    )

  def body(response: Response[String]): Json =
    parse(response.body).fold(
      failure => sys.error(s"not JSON: ${failure.message} in ${response.body}"),
      identity
    )
}
