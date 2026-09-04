package kui.message.app

import cats.data.NonEmptyList
import cats.effect.kernel.Async
import org.typelevel.log4cats.StructuredLogger
import sttp.client4.Backend

import kui.cache.CacheMetrics
import kui.config.{
  ClusterConfig,
  RegistryAuthConfig,
  SafeUrl,
  SchemaRegistrySettings,
  UrlPolicy
}
import kui.kernel.ServiceId
import kui.observability.Telemetry
import kui.serde.confluent.{SchemaRegistryAuth, SchemaRegistryConfig, SchemaRegistrySerdeFactory}
import kui.serde.{SerdeFactory, SerdeResolution}

/** Configuration to serdes: the one place that turns what an operator wrote into what decodes a record.
  *
  * ==Why this file exists at all==
  *
  * Both halves have been built for some time and neither could reach the other. `SerdeResolution.Rules`
  * decides which serde reads which topic and was only ever handed `Rules.empty`;
  * `SchemaRegistrySerdeFactory` builds a registry-backed serde and was constructed nowhere, so every Avro
  * payload in the product rendered as bytes. The gap between them was two small translations, and this is
  * both of them — which is why it lives in the composition root, the one module allowed to see
  * `libs/config` and `libs/serde-confluent` at the same time.
  *
  * ==Why the translation rather than a shared type==
  *
  * `libs/config` must load a configuration file in a process that has no `libs/serde-confluent` on its
  * classpath — the gateway and the cluster service both do — so it cannot name that module's types.
  * `libs/serde-confluent` must be usable by a future reader that gets its registry address from somewhere
  * other than a YAML file, so it cannot name `libs/config`'s. Two small records and one function between
  * them is the cost of keeping both of those true, and the function is nine lines.
  */
object ClusterSerdeFactories {

  /** The service name this process's registry calls are attributed to in metrics and traces. */
  val Attribution: ServiceId = ServiceId.unsafe("message")

  /** The serde factories one cluster has: the registry serde when a registry is configured, nothing when it
    * is not.
    *
    * A cluster with no `schemaRegistry` block gets an empty list, and that is a complete answer rather than
    * a degraded one — the built-in serdes and the fallback are what `ClusterSerdes` adds to whatever this
    * returns, so such a cluster browses exactly as it did before this file existed.
    *
    * Note what is *not* here: no check that the registry is reachable. That question is asked by the
    * factory's own probe when the serde is built, once per cluster, and its answer becomes a disabled row in
    * the picker with the reason attached (ADR-032). Asking it here would make a registry outage into a
    * service that will not start.
    */
  def forCluster[F[_]: Async](
      cluster: ClusterConfig,
      backend: Backend[F],
      telemetry: Telemetry[F],
      logger: StructuredLogger[F],
      metrics: CacheMetrics[F],
      policy: UrlPolicy
  ): List[SerdeFactory[F]] =
    cluster.schemaRegistry.toList.map { settings =>
      SchemaRegistrySerdeFactory[F](
        registryConfig(settings, cluster, policy),
        backend,
        telemetry,
        Attribution,
        logger,
        metrics
      )
    }

  /** `SchemaRegistrySettings` (what an operator wrote) as `SchemaRegistryConfig` (what the decoder needs).
    *
    * The two cache knobs come from the cluster's `serde` section rather than from the registry section,
    * because they describe what this reader keeps in memory rather than the registry itself — see
    * `ClusterSerdeConfig` for that reasoning.
    */
  def registryConfig(
      settings: SchemaRegistrySettings,
      cluster: ClusterConfig,
      policy: UrlPolicy
  ): SchemaRegistryConfig =
    SchemaRegistryConfig(
      urls = settings.urls,
      auth = auth(settings.auth),
      callTimeout = settings.callTimeout,
      // The registry is very often the one upstream that legitimately lives on a private network or on
      // loopback — the quickstart's does — so it is held to the same policy the rest of this process's
      // configuration was loaded under rather than to `Strict` regardless. Passing `Strict` here would mean
      // an operator's `KUI_ALLOW_PRIVATE_UPSTREAMS` had no effect on the one upstream that most needs it.
      urlPolicy = policy,
      schemaCacheSize = cluster.serde.schemaCacheSize,
      subjectCacheTtl = cluster.serde.subjectCacheTtl
    )

  /** Credentials, with the one case the decoder cannot honour named rather than silently downgraded.
    *
    * `RegistryAuthConfig.OAuth` is the client-credentials grant: KUI would have to post to a token endpoint,
    * hold the token and refresh it before it expires. The schema *service* implements that flow (SR-007);
    * this decoder does not have it yet, and the honest behaviour is to attach no credential — the registry
    * then answers 401, the factory's probe reports it, and the picker shows a `SchemaRegistry` row disabled
    * with the registry's own refusal. Quietly sending nothing and calling it anonymous would produce the
    * same 401 with no explanation anywhere.
    */
  def auth(configured: RegistryAuthConfig): SchemaRegistryAuth =
    configured match {
      case RegistryAuthConfig.Anonymous => SchemaRegistryAuth.Anonymous
      case RegistryAuthConfig.Basic(username, password) =>
        SchemaRegistryAuth.Basic(username, password.value)
      case RegistryAuthConfig.OAuth(_, _, _, _) => SchemaRegistryAuth.Anonymous
    }

  /** Whether a cluster's registry credentials are of a kind this decoder can actually send.
    *
    * Separate from [[auth]] so that the composition root can log the downgrade once at startup instead of
    * leaving an operator to infer it from a 401.
    */
  def canAuthenticate(configured: RegistryAuthConfig): Boolean =
    configured match {
      case RegistryAuthConfig.OAuth(_, _, _, _) => false
      case _ => true
    }

  /** The operator's `serde` section as the resolution table's `Rules`.
    *
    * Order is preserved exactly: `SerdeResolution` takes the first matching pattern, and "first" means the
    * order the operator wrote, which is why the loader refuses a gap in the index rather than renumbering.
    * One configuration entry can carry a key pattern and a value pattern, and it becomes one rule per
    * target here because resolution asks about one target at a time.
    */
  def rules(cluster: ClusterConfig): SerdeResolution.Rules =
    SerdeResolution.Rules(
      patterns = cluster.serde.patterns.flatMap { entry =>
        entry.topicKeysPattern
          .map(SerdeResolution.PatternRule(_, entry.serde, kui.kernel.serde.Target.Key))
          .toList ++
          entry.topicValuesPattern
            .map(SerdeResolution.PatternRule(_, entry.serde, kui.kernel.serde.Target.Value))
            .toList
      },
      defaultKey = cluster.serde.defaultKey,
      defaultValue = cluster.serde.defaultValue
    )

  /** Whether any configured cluster needs an HTTP client at all.
    *
    * The message service opens no connection pool when no cluster has a registry, which is the ordinary
    * case: a process that holds an idle HTTP client and a set of otel instruments for an upstream nobody
    * configured is a process whose dashboards carry a permanently zero series.
    */
  def anyRegistryConfigured(clusters: List[ClusterConfig]): Boolean =
    clusters.exists(_.schemaRegistry.isDefined)

  /** The addresses a log line names, for the one INFO line this wiring writes per cluster. */
  def addresses(settings: SchemaRegistrySettings): NonEmptyList[SafeUrl] = settings.urls
}
