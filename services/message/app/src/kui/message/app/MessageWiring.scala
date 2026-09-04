package kui.message.app

import cats.Parallel
import cats.effect.kernel.{Async, Resource}
import cats.syntax.all.*
import fs2.io.file.Files
import org.typelevel.log4cats.StructuredLogger
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.Backend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.Interceptor

import kui.cache.CacheMetrics
import kui.config.{ClusterConfig, UrlPolicy}
import kui.contracts.capability.ServiceCapabilities
import kui.filter.{CelFilterEngine, FilterLimits, FilterMetrics, MessageFilterPort}
import kui.http.health.ReadinessCheck
import kui.http.principal.PrincipalVerification
import kui.kafka.{AdminClientPool, AdminMetrics}
import kui.kernel.{ClusterId, Secret}
import kui.message.api.{MessageApi, MessageRoutes}
import kui.message.application.cursor.CursorCodec
import kui.message.application.produce.{MutationGuard, ProduceUseCase, ResendUseCase}
import kui.message.application.purge.{PurgeToken, PurgeUseCase}
import kui.message.application.{BrowseUseCase, FilterUseCase}
import kui.message.infrastructure.{
  BrowseTuning,
  CelFilterSource,
  ClusterSerdeSource,
  ConfiguredClusterProfiles,
  KafkaBrowseConsumer,
  KafkaRecordDeleter,
  KafkaRecordProducer,
  KafkaRecordSource
}
import kui.observability.Telemetry
import kui.observability.audit.LoggingAuditSink
import kui.security.PrincipalCodec
import kui.serde.{ClusterSerdes, SerdeFactory, SerdeProfile}

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
  * topic service keeps a background scrape and a snapshot per cluster because a topic list is a thing you ask
  * about repeatedly. A browse is not: it opens a consumer, reads what was asked for, and closes it. So the
  * only long-lived things here are the serdes, and they are values.
  *
  * That is why a broker being down delays nothing at startup and fails nothing at startup. It shows up where
  * it should — on the stream that tried to read it, as a terminal `error` event naming the cluster.
  *
  * ==Why the `Resource` still matters==
  *
  * `ClusterSerdes` is a `Resource` because a Schema-Registry serde owns an HTTP client and two caches, and a
  * cluster that configures `schemaRegistry` now really does hold all three. Releasing this resource is what
  * closes the connection pool and drops the caches; a cluster with no registry configured still gets a
  * trivial one, so both shapes go down the same code path.
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
    *   HTTP profile client. The cursor signing key is generated here, once per process. That is the honest
    *   shape for the all-in-one deployment, which is one process: a cursor outlives a page but not a restart,
    *   and its one-hour lifetime makes that indistinguishable from expiry to anyone using it. The moment this
    *   service runs as several replicas the key becomes configuration (`kui.streaming.cursorKey`), because a
    *   cursor minted by one replica and rejected by its neighbour is the exact failure the signed cursor
    *   exists to remove — see `CursorCodec.hmacSha256`.
    */
  def make[F[_]: {Async, Parallel, Files}](
      clusters: List[ClusterConfig],
      cursorKey: Option[Secret[String]],
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
      cacheMetrics <- Resource.eval(CacheMetrics.otel4s[F](meter))
      serdes <- serdesFor[F](clusters, telemetry, logger, cacheMetrics)
      signingKey <- Resource.eval(cursorKeyFor[F](cursorKey, logger))
      // One CEL engine per cluster (MS-007). It is a `Resource` because it owns a bounded, TTL'd cache of
      // compiled programs, and it is built here rather than on the first filtered browse because CEL's
      // first compile in a JVM is slower than a filter's whole per-record deadline — warming it at startup
      // is what stops the first records of the first filtered browse timing out for no visible reason.
      filterEngines <- filterEnginesFor[F](clusters, meter, cacheMetrics)
      filterSource = new CelFilterSource[F](filterEngines)
      source = new KafkaRecordSource[F](
        KafkaBrowseConsumer.resource[F](profiles.connectionFor, logger),
        tuning
      )
      serdeSource = new ClusterSerdeSource[F](serdes)
      browse = BrowseUseCase.make[F](
        profiles,
        serdeSource,
        source,
        CursorCodec.hmacSha256[F](signingKey),
        filterSource
      )
      filters = FilterUseCase.make[F](filterSource)
      // ADR-047's three parts, wired once and shared by both writes. The guard is the only way this
      // service changes a cluster: it holds the read-only refusal and the audit record, and it is what
      // returns the result, so a use case cannot be added that writes without going through them.
      //
      // Who did it is not wired here. It is a parameter of every `guard` call, threaded from the
      // principal the gateway signed and the route verified (ADR-020), so an audit record names the
      // person who made the request rather than a constant this file chose.
      guard = MutationGuard.make[F](profiles, LoggingAuditSink.make[F](logger), logger)
      producers = KafkaRecordProducer.resource[F](profiles.connectionFor, logger)
      produce = ProduceUseCase.make[F](producers, serdeSource, guard)
      // A resend reads through the same record source a browse does, so the seek arithmetic has one
      // implementation, and it is bounded by the same budget a browse is — a copy of ten million
      // records is refused by a ceiling rather than attempted.
      resend = ResendUseCase.make[F](producers, source, guard, MessageRoutes.DefaultBudget)
      // The purge (MS-008). It is the one operation in this service that takes data away, so it gets
      // its own port and its own admin client rather than borrowing the browse consumer's: emptying a
      // topic is a pair of admin calls — `listOffsets` for the plan, `deleteRecords` for the apply —
      // and neither belongs on a consumer that exists to read records.
      //
      // The plan is signed with the same `kui.streaming.cursorKey` the browse cursor uses (ADR-026):
      // one secret and one rotation procedure, with the two uses kept apart by the operation name
      // inside the payload.
      adminMetrics <- Resource.eval(AdminMetrics.otel[F](telemetry))
      adminPool <- AdminClientPool.resource[F](adminMetrics, Some(logger))
      deleter = new KafkaRecordDeleter[F](adminPool, profiles.connectionFor, logger)
      purge = PurgeUseCase.make[F](
        deleter,
        profiles,
        guard,
        PurgeToken.make[F](signingKey),
        logger
      )
      // Readiness is deliberately empty, and for a stronger reason than the topic service's. "Can this
      // service answer" is true as soon as it is wired: it holds no snapshot, so there is nothing to be
      // waiting for. A readiness check that dialled a broker would take the message service out of
      // rotation whenever *one* cluster was slow, which would take browsing away from every other
      // cluster at the same time.
      readiness = List.empty[ReadinessCheck[F]]
    } yield MessageServer(
      routes = MessageApi.routes[F](
        browse,
        filters,
        produce,
        resend,
        purge,
        profiles.ids,
        readiness,
        principals,
        rejections,
        logger,
        telemetry
      ),
      interceptors = interceptors,
      readiness = readiness,
      capabilities = MessageApi.capabilityDocument[F](profiles.ids)
    )

  /** One CEL filter engine per configured cluster.
    *
    * Per cluster and not per process, because the compiled-program cache is keyed on a cluster: a filter is
    * written against one cluster's records, and an operator reading cache statistics is asking about one
    * cluster at a time. A cluster with no engine is refused a filter with `KUI-UNSUPPORTED` rather than
    * quietly matching everything — see `CelFilterSource`, which is the only file in this service that names
    * CEL at all.
    */
  private def filterEnginesFor[F[_]: Async](
      clusters: List[ClusterConfig],
      meter: org.typelevel.otel4s.metrics.Meter[F],
      cacheMetrics: CacheMetrics[F]
  ): Resource[F, Map[ClusterId, MessageFilterPort[F]]] =
    Resource
      .eval(FilterMetrics.otel4s[F](meter))
      .flatMap(metrics =>
        clusters.traverse(cluster =>
          CelFilterEngine
            .resource[F](cluster.id, FilterLimits.default, metrics, cacheMetrics)
            .map(cluster.id -> _)
        )
      )
      .map(_.toMap)

  /** The configured key, or a fresh one, saying out loud which of the two happened.
    *
    * A cursor minted by one replica and rejected by its neighbour is the exact failure the signed cursor
    * exists to remove, and it looks to a user like a "load more" button that works one press in two. The one
    * deployment where a generated key is right is the one this log line lets an operator confirm they are in.
    */
  private def cursorKeyFor[F[_]: Async](
      configured: Option[Secret[String]],
      logger: StructuredLogger[F]
  ): F[Secret[Array[Byte]]] =
    configured match {
      case Some(secret) =>
        Async[F]
          .pure(Secret(secret.value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .flatTap(_ => logger.info("browse cursors are signed with the configured kui.streaming.cursorKey"))
      case None =>
        newCursorKey[F].flatTap(_ =>
          logger.info(
            "no kui.streaming.cursorKey is configured; browse cursors are signed with a key generated " +
              "for this process. A second replica rejects this one's cursors. Configure the key before " +
              "running more than one."
          )
        )
    }

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

  /** One `ClusterSerdes` per configured cluster: the built-ins, plus the Schema-Registry serde for every
    * cluster that configures a registry.
    *
    * ==What changed, and why it is worth a paragraph==
    *
    * This function used to pass `List.empty` factories and `SerdeProfile.unconfigured`, which meant every
    * cluster had the built-in serdes and nothing else. The consequence was visible to anyone with a real
    * cluster: an Avro payload has no valid UTF-8 reading and no JSON reading, so it fell all the way to the
    * fallback and rendered as bytes. `libs/serde-confluent` could decode it and nothing constructed the
    * factory; `SerdeResolution.Rules` could route to it and nothing ever built a non-empty `Rules`. Both
    * halves existed and neither was reachable, which is this project's characteristic defect and the reason
    * SD-001 and SD-003 are one task.
    *
    * ==The HTTP client==
    *
    * Opened once for the process and shared by every cluster's registry, and only when some cluster has one.
    * Each cluster still gets its own `UpstreamClient` on top of it — its own bulkhead, its own circuit
    * breaker, its own failover list — so one registry being down cannot take another cluster's decoding with
    * it. What is shared is the connection pool, which is what a pool is for.
    *
    * A registry that cannot be reached does not fail this step. The factory probes it once, and a failure
    * becomes a disabled `SchemaRegistry` row in the picker carrying the reason (ADR-032), while the rest of
    * the cluster's serdes work normally.
    */
  private def serdesFor[F[_]: Async](
      clusters: List[ClusterConfig],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      metrics: CacheMetrics[F]
  ): Resource[F, Map[ClusterId, ClusterSerdes[F]]] =
    for {
      policy <- Resource.eval(Async[F].delay(UrlPolicy.fromEnv(sys.env)))
      backend <- registryBackend[F](clusters)
      _ <- Resource.eval(clusters.traverse_(describe[F](_, logger)))
      built <- clusters.traverse(cluster =>
        ClusterSerdes
          .resource[F](
            SerdeProfile(
              cluster.id,
              ProfileVersion,
              ClusterSerdeFactories.rules(cluster),
              Map.empty[String, String]
            ),
            factoriesFor[F](cluster, backend, telemetry, logger, metrics, policy)
          )
          .map(cluster.id -> _)
      )
    } yield built.toMap

  /** The process's one HTTP connection pool, or none at all.
    *
    * A deployment where no cluster configures a registry opens no client and registers no upstream
    * instruments. That is not a micro-optimisation: an idle upstream publishes a permanently zero series on
    * every dashboard that charts it, and a name in a metric that nothing can ever make non-zero is a name an
    * operator learns to ignore.
    */
  private def registryBackend[F[_]: Async](
      clusters: List[ClusterConfig]
  ): Resource[F, Option[Backend[F]]] =
    if ClusterSerdeFactories.anyRegistryConfigured(clusters) then
      HttpClientFs2Backend.resource[F]().map(backend => Some(backend: Backend[F]))
    else Resource.pure[F, Option[Backend[F]]](None)

  private def factoriesFor[F[_]: Async](
      cluster: ClusterConfig,
      backend: Option[Backend[F]],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      metrics: CacheMetrics[F],
      policy: UrlPolicy
  ): List[SerdeFactory[F]] =
    backend.toList.flatMap(client =>
      ClusterSerdeFactories.forCluster[F](cluster, client, telemetry, logger, metrics, policy)
    )

  /** One INFO line per cluster that configures a registry, and one WARN for the case KUI cannot honour.
    *
    * "Which registry is this cluster decoding against?" is the first question asked when a payload renders
    * unexpectedly, and after the fact it is unanswerable unless the process said so at startup. The address
    * is safe to log — it is the operator's own text and `SchemaRegistrySettings` prints no credential — and
    * the mechanism is named without its secret.
    */
  private def describe[F[_]: Async](
      cluster: ClusterConfig,
      logger: StructuredLogger[F]
  ): F[Unit] =
    cluster.schemaRegistry.traverse_ { settings =>
      val addresses = ClusterSerdeFactories.addresses(settings).toList.map(_.value).mkString(", ")
      logger.info(
        s"cluster '${cluster.id.value}' decodes registry payloads against $addresses " +
          s"(${settings.auth.describe})"
      ) *>
        Async[F].whenA(!ClusterSerdeFactories.canAuthenticate(settings.auth))(
          logger.warn(
            s"cluster '${cluster.id.value}' configures OAuth client credentials for its schema registry, " +
              "and the message service cannot perform that grant yet (SR-007). Registry calls from this " +
              "process are made anonymously and will be refused if the registry requires a token; the " +
              "serde picker shows the refusal. Use basic authentication here until the grant lands"
          )
        )
    }

  /** The profile version the serde registry keys its caches on.
    *
    * One, and constant, because a statically configured cluster's serde profile changes when the process is
    * restarted with a different file — and a restart replaces the cache along with everything else. It starts
    * mattering when profiles can be edited at run time (M5).
    */
  val ProfileVersion: Long = 1L
}
