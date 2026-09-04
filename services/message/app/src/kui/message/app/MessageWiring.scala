package kui.message.app

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.config.ClusterConfig
import kui.contracts.capability.ServiceCapabilities
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.kernel.{ClusterId, Secret}
import kui.message.api.MessageApi
import kui.message.application.BrowseUseCase
import kui.message.application.cursor.CursorCodec
import kui.message.infrastructure.{
  BrowseTuning,
  ClusterSerdeSource,
  ConfiguredClusterProfiles,
  KafkaBrowseConsumer,
  KafkaRecordSource
}
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.serde.{ClusterSerdes, SerdeProfile}

/** Everything the message service needs in order to be served, with no listener started.
  *
  * The same shape as `ClusterWiring` and `TopicWiring`, for the same reason (ADR-010): stopping one step
  * short of a running server is what lets the all-in-one deployment take these routes, add every other
  * service's, and start one listener over the lot.
  */
final case class MessageServer[F[_]](
    routes: List[ServerEndpoint[Fs2Streams[F], F]],
    interceptors: List[Interceptor[F]],
    readiness: List[ReadinessCheck[F]],
    capabilities: F[ServiceCapabilities]
)

/** The message service's composition root: the one place in this service that constructs anything concrete.
  *
  * ==What it contacts, and when==
  *
  * Nothing. No Kafka connection is opened while this is being built, and none is held afterwards either —
  * which is the difference between this service and the topic service, and it is worth understanding. The
  * topic service keeps a background scrape and a snapshot per cluster because a topic list is a thing you
  * ask about repeatedly. A browse is not: it opens a consumer, reads what was asked for, and closes it. So
  * the only long-lived things here are the serdes, and they are values.
  *
  * That is why a broker being down delays nothing at startup and fails nothing at startup. It shows up
  * where it should — on the stream that tried to read it, as a terminal `error` event naming the cluster.
  *
  * ==Why the `Resource` still matters==
  *
  * `ClusterSerdes` is a `Resource` because a Schema-Registry serde owns an HTTP client and two caches. None
  * is configured today, so the resource is trivial; it is written as one anyway, because the day a registry
  * serde is added is not the day to discover that nothing in this file has a lifetime.
  */
object MessageWiring {

  /** The instrumentation scope this service's tracer and meter are named after. */
  val Instrumentation: String = "kui.message"

  /** Builds everything except the listener.
    *
    * @param clusters
    *   the configured clusters, from `kui.clusters[]`. In the all-in-one deployment this is the same list
    *   every other service was given, read once from the same file — see
    *   [[kui.message.infrastructure.ConfiguredClusterProfiles]] for why this shape does not go through the
    *   HTTP profile client.
    * The cursor signing key is generated here, once per process. That is the honest shape for the
    * all-in-one deployment, which is one process: a cursor outlives a page but not a restart, and its
    * one-hour lifetime makes that indistinguishable from expiry to anyone using it. The moment this service
    * runs as several replicas the key becomes configuration (`kui.streaming.cursorKey`), because a cursor
    * minted by one replica and rejected by its neighbour is the exact failure the signed cursor exists to
    * remove — see `CursorCodec.hmacSha256`.
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      telemetry: Telemetry[F],
      principals: PrincipalCodec[F],
      logger: StructuredLogger[F],
      tuning: BrowseTuning = BrowseTuning.Default
  ): Resource[F, MessageServer[F]] =
    for {
      meter <- Resource.eval(telemetry.meter(Instrumentation))
      rejections <- Resource.eval(PrincipalVerification.rejectionCounter[F](meter))
      interceptors <- Resource.eval(MessageApi.interceptors[F](telemetry, rejections, logger))
      profiles = ConfiguredClusterProfiles.of[F](clusters)
      serdes <- serdesFor[F](clusters)
      cursorKey <- Resource.eval(newCursorKey[F])
      source = new KafkaRecordSource[F](
        KafkaBrowseConsumer.resource[F](profiles.connectionFor, logger),
        tuning
      )
      browse = BrowseUseCase.make[F](
        profiles,
        new ClusterSerdeSource[F](serdes),
        source,
        CursorCodec.hmacSha256[F](cursorKey)
      )
      // Readiness is deliberately empty, and for a stronger reason than the topic service's. "Can this
      // service answer" is true as soon as it is wired: it holds no snapshot, so there is nothing to be
      // waiting for. A readiness check that dialled a broker would take the message service out of
      // rotation whenever *one* cluster was slow, which would take browsing away from every other
      // cluster at the same time.
      readiness = List.empty[ReadinessCheck[F]]
    } yield MessageServer(
      routes =
        MessageApi.routes[F](browse, profiles.ids, readiness, principals, rejections, logger, telemetry),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = MessageApi.capabilityDocument[F](profiles.ids)
    )

  /** A fresh signing key for this process's cursors.
    *
    * `SecureRandom` and not a fixed literal: a predictable key would let anyone mint a cursor naming any
    * cluster, and a cursor is trusted precisely because it was signed.
    */
  private def newCursorKey[F[_]: Async]: F[Secret[Array[Byte]]] =
    Async[F].delay {
      val bytes = new Array[Byte](CursorKeyBytes)
      new java.security.SecureRandom().nextBytes(bytes)
      Secret(bytes)
    }

  /** 256 bits, which is the block size HMAC-SHA256 wants. */
  val CursorKeyBytes: Int = 32

  /** One `ClusterSerdes` per configured cluster: the built-ins, and — when `libs/serde-confluent` is on the
    * classpath and configured — whatever else that cluster has.
    *
    * The factory list is empty here because no registry serde is configured in this build. That is not a
    * stub: `ClusterSerdes.resource` with no factories is exactly "the built-ins and the fallback", which is
    * a complete and correct serde set for a cluster nobody has configured serdes for, and it is what makes
    * the seeded quickstart render JSON as JSON and plain log lines as text without anybody configuring
    * anything.
    */
  private def serdesFor[F[_]: Async](
      clusters: List[ClusterConfig]
  ): Resource[F, Map[ClusterId, ClusterSerdes[F]]] =
    clusters
      .traverse(cluster =>
        ClusterSerdes
          .resource[F](SerdeProfile.unconfigured(cluster.id, ProfileVersion), List.empty)
          .map(cluster.id -> _)
      )
      .map(_.toMap)

  /** The profile version the serde registry keys its caches on.
    *
    * One, and constant, because a statically configured cluster's serde profile changes when the process is
    * restarted with a different file — and a restart replaces the cache along with everything else. It
    * starts mattering when profiles can be edited at run time (M5).
    */
  val ProfileVersion: Long = 1L
}
