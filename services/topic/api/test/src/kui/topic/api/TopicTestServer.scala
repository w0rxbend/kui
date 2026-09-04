package kui.topic.api

import java.time.Instant

import scala.concurrent.duration.{DurationInt, FiniteDuration}

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref, Resource}
import org.typelevel.otel4s.metrics.Counter
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import sttp.client4.Backend
import sttp.client4.impl.cats.implicits.*
import sttp.client4.testing.BackendStub
import sttp.tapir.server.stub4.TapirStubInterpreter

import kui.cache.{Snapshot, SnapshotCell, SnapshotStatus}
import kui.http.principal.PrincipalVerification
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.kernel.{ClusterId, Secret, ServiceId, TopicName, UserName}
import kui.observability.Telemetry
import kui.security.*
import kui.testkit.fakes.FakeStructuredLogger
import kui.topic.application.*
import kui.topic.domain.{
  CreatedTopic,
  DeletionPlan,
  NewTopicSpec,
  PartitionPlan,
  TopicConfigChange,
  TopicConfigView,
  TopicDetail,
  TopicError,
  TopicSnapshot
}

/** The topic service, assembled the way `services/topic/app` will assemble it, with no socket.
  *
  * Everything under test here — the interceptor order, the principal check, the section states, the error
  * envelope, the status code — is decided by the routes and the interceptors, and Tapir's stub interpreter
  * runs exactly those. A bound port would add seconds to every case and would prove one extra thing (that
  * Netty works) which `libs/http` already proves once for every service.
  *
  * The one thing it does not fake is the principal codec: it signs real tokens with a real key, because a
  * suite that verified tokens through a fake codec would pass just as happily against a service that had
  * forgotten to check the signature.
  */
final case class TopicTestServer(
    backend: Backend[IO],
    logger: FakeStructuredLogger[IO],
    principals: PrincipalCodec[IO],
    telemetry: OtelJavaTestkit[IO],
    refreshes: Ref[IO, List[ClusterId]]
)

object TopicTestServer {

  val Now: Instant = Instant.parse("2026-09-03T10:11:12Z")

  val Cluster: ClusterId = ClusterId.unsafe("local")
  val Missing: ClusterId = ClusterId.unsafe("nowhere")

  def path(rest: String): String = s"/internal/v1/clusters/${Cluster.value}$rest"

  def uri(rest: String): String = s"http://topic$rest"

  private val KeyMaterial: Array[Byte] = Array.fill[Byte](32)(7)

  /** `notBefore` is the epoch, not a plausible date.
    *
    * One suite runs its case under `TestControl`, whose virtual clock starts at the epoch, and a key that
    * became active in 2020 is a key with a `notBefore` in the future when "now" is 1970 — which fails
    * signing with a message about key rotation that has nothing to do with what is being tested.
    */
  private val Key: SigningKey = SigningKey("test-1", Secret(KeyMaterial), Instant.EPOCH)

  val Issuer: String = "kui-gateway"

  def codec: PrincipalCodec[IO] =
    JwsPrincipalCodec
      .make[IO](NonEmptyList.of(Key), Issuer)
      .getOrElse(throw new IllegalStateException("the test signing key is too short for HS256"))

  /** A token bound to one method and path, good for a minute unless the caller says otherwise.
    *
    * ADR-020 binds a token to exactly one call, so every request in these suites needs its own. The lifetime
    * is relative to the real clock rather than to [[Now]] because expiry is checked against the *service's*
    * clock.
    */
  def token(
      requestPath: String,
      method: String = "GET",
      audience: ServiceId = TopicApi.Id,
      validFor: FiniteDuration = 60.seconds,
      subject: String = "alice"
  ): IO[SignedPrincipal] =
    IO.realTimeInstant.flatMap(now =>
      codec.sign(
        PrincipalClaims(
          subject = UserName.unsafe(subject),
          roles = Set.empty,
          kind = PrincipalKind.Session,
          sessionRef = None,
          issuedAt = now,
          expiresAt = now.plusSeconds(validFor.toSeconds),
          audience = audience,
          // The digest covers the method and the *path*, with no query string: that is what the service
          // builds from `request.uri.path`, and ADR-020 binds a token to a call rather than to a filter.
          requestDigest = RequestDigest.ofRequestLine(method, requestPath.takeWhile(_ != '?'))
        )
      )
    )

  /** What the snapshot for [[Cluster]] holds, and whether it is current.
    *
    * The three shapes are the three a screen has to render differently: a good snapshot, a snapshot that
    * could not be renewed, and a cluster that has never been read at all.
    */
  def online(snapshot: TopicSnapshot): Snapshot[TopicSnapshot] =
    Snapshot(Some(snapshot), SnapshotStatus.Online, Some(snapshot.scrapedAt))

  def stale(snapshot: TopicSnapshot, error: KuiError, since: Instant): Snapshot[TopicSnapshot] =
    Snapshot(Some(snapshot), SnapshotStatus.Offline(error, since), Some(snapshot.scrapedAt))

  def neverScraped(error: KuiError, since: Instant): Snapshot[TopicSnapshot] =
    Snapshot(None, SnapshotStatus.Offline(error, since), None)

  /** A `TopicSnapshots` over one fixed cluster.
    *
    * `refreshes` records what was asked to be refreshed, and `refreshBlocks` is how the suite proves the
    * refresh endpoint does not wait for a scrape: when it is set, the *cell's* refresh never completes, and a
    * route that awaited it would never answer.
    */
  final class StubSnapshots(
      state: Snapshot[TopicSnapshot],
      val refreshes: Ref[IO, List[ClusterId]],
      refreshBlocks: Option[Deferred[IO, Unit]] = None
  ) extends TopicSnapshots[IO] {

    private val cell: SnapshotCell[IO, TopicSnapshot] = new SnapshotCell[IO, TopicSnapshot] {
      def get: IO[Snapshot[TopicSnapshot]] = IO.pure(state)
      def refresh: IO[Snapshot[TopicSnapshot]] =
        refreshBlocks.fold(IO.pure(state))(_.get.as(state))
      def invalidate: IO[Snapshot[TopicSnapshot]] = IO.pure(state)
      def updates: fs2.Stream[IO, Snapshot[TopicSnapshot]] = fs2.Stream.emit(state)
    }

    def of(cluster: ClusterId): IO[Option[SnapshotCell[IO, TopicSnapshot]]] =
      IO.pure(Option.when(cluster == Cluster)(cell))

    def requestRefresh(cluster: ClusterId): IO[Boolean] =
      if cluster == Cluster then refreshes.update(_ :+ cluster).as(true) else IO.pure(false)
  }

  final class StubDetail(answer: Either[TopicError, Fresh[TopicDetail]]) extends TopicDetailUseCase[IO] {
    def detail(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, Fresh[TopicDetail]]] =
      if cluster != Cluster then IO.pure(Left(TopicError.ClusterNotFound(cluster))) else IO.pure(answer)
  }

  final class StubConfig(answer: Either[TopicError, TopicConfigView]) extends TopicConfigUseCase[IO] {
    def config(cluster: ClusterId, topic: TopicName): IO[Either[TopicError, TopicConfigView]] =
      if cluster != Cluster then IO.pure(Left(TopicError.ClusterNotFound(cluster))) else IO.pure(answer)
  }

  final class StubCapabilities(report0: List[(ClusterId, TopicCapability)])
      extends TopicCapabilityUseCase[IO] {
    def report: IO[List[(ClusterId, TopicCapability)]] = IO.pure(report0)
  }

  /** Every administration route, answering whatever the fixture was built with.
    *
    * One stub for all six because they share a use case, and because what these suites assert about them is
    * routing, decoding and the error envelope — not the Kafka behaviour, which is the application layer's
    * own suites' subject.
    */
  final class StubAdmin(
      created: Either[KuiError, CreatedTopic],
      configured: Either[KuiError, TopicConfigView],
      partitionPlan: Either[KuiError, Planned[PartitionPlan]],
      partitionsApplied: Either[KuiError, PartitionPlan],
      deletionPlan: Either[KuiError, Planned[DeletionPlan]],
      deleted: Either[KuiError, DeletionPlan]
  ) extends TopicAdminUseCase[IO] {

    def create(cluster: ClusterId, spec: NewTopicSpec): IO[Either[KuiError, CreatedTopic]] =
      IO.pure(created)

    def alterConfig(
        cluster: ClusterId,
        topic: TopicName,
        change: TopicConfigChange
    ): IO[Either[KuiError, TopicConfigView]] = IO.pure(configured)

    def planPartitions(
        cluster: ClusterId,
        topic: TopicName,
        target: Int
    ): IO[Either[KuiError, Planned[PartitionPlan]]] = IO.pure(partitionPlan)

    def applyPartitions(
        cluster: ClusterId,
        topic: TopicName,
        token: String
    ): IO[Either[KuiError, PartitionPlan]] = IO.pure(partitionsApplied)

    def planDelete(cluster: ClusterId, topic: TopicName): IO[Either[KuiError, Planned[DeletionPlan]]] =
      IO.pure(deletionPlan)

    def applyDelete(
        cluster: ClusterId,
        topic: TopicName,
        token: String
    ): IO[Either[KuiError, DeletionPlan]] = IO.pure(deleted)
  }

  /** The answer every administration route gives unless a suite says otherwise: a refusal that names the
    * fixture, so a suite that reaches one of these routes by accident sees why rather than a 500.
    */
  val NotConfigured: KuiError =
    kui.kernel.error.ApplicationError.InvalidState("no administration answer is configured in this fixture")

  /** The whole service, ready to be spoken to. */
  def resource(
      snapshot: Snapshot[TopicSnapshot],
      detail: Either[TopicError, Fresh[TopicDetail]] =
        Left(TopicError.Unreachable("no detail configured in this fixture", retryable = false)),
      config: Either[TopicError, TopicConfigView] = Right(TopicConfigView.of(Nil)),
      capabilities: List[(ClusterId, TopicCapability)] = Nil,
      refreshBlocks: Option[Deferred[IO, Unit]] = None,
      created: Either[KuiError, CreatedTopic] = Left(NotConfigured),
      configured: Either[KuiError, TopicConfigView] = Left(NotConfigured),
      partitionPlan: Either[KuiError, Planned[PartitionPlan]] = Left(NotConfigured),
      partitionsApplied: Either[KuiError, PartitionPlan] = Left(NotConfigured),
      deletionPlan: Either[KuiError, Planned[DeletionPlan]] = Left(NotConfigured),
      deleted: Either[KuiError, DeletionPlan] = Left(NotConfigured)
  ): Resource[IO, TopicTestServer] =
    OtelJavaTestkit.inMemory[IO]().evalMap { testkit =>
      for {
        logger <- FakeStructuredLogger[IO]
        rejections <- rejectionCounter(testkit)
        refreshes <- Ref.of[IO, List[ClusterId]](Nil)
        telemetry = Telemetry.fromProviders(testkit.tracerProvider, testkit.meterProvider)
        interceptors <- TopicApi.interceptors[IO](telemetry, rejections, logger)
      } yield {
        val catsMonadError: sttp.monad.MonadError[IO] = summon
        val routes = TopicApi.routes[IO](
          new StubSnapshots(snapshot, refreshes, refreshBlocks),
          new StubDetail(detail),
          new StubConfig(config),
          new StubAdmin(created, configured, partitionPlan, partitionsApplied, deletionPlan, deleted),
          new StubCapabilities(capabilities),
          Nil,
          codec,
          rejections,
          logger
        )

        TopicTestServer(
          TapirStubInterpreter(interceptors, BackendStub[IO](catsMonadError))
            .whenServerEndpointsRunLogic(routes)
            .backend(),
          logger,
          codec,
          testkit,
          refreshes
        )
      }
    }

  def rejectionCounter(testkit: OtelJavaTestkit[IO]): IO[Counter[IO, Long]] =
    testkit.meterProvider.get("kui.topic").flatMap(PrincipalVerification.rejectionCounter[IO])

  /** A failure that is worth retrying, for the stale cases. */
  val Timeout: KuiError = InfrastructureError.Timeout("describeTopics", 30_000L)
}
