package kui.serde.confluent

import scala.util.control.NoStackTrace

import cats.effect.{Async, Resource}
import cats.syntax.all.*

import kui.cache.{BoundedCache, CacheMetrics}
import kui.kernel.ClusterId
import kui.kernel.error.KuiError

/** The registry, with ADR-016's two caches in front of it.
  *
  * Both caches exist for the same reason and expire differently, and the difference is the whole design:
  *
  *   - **Schemas by id.** A schema id is immutable — the registry never reissues one — so a cached schema
  *     cannot go stale, only unused. Size-bounded, no expiry. This is the cache that matters for throughput:
  *     a page of five hundred records written by one producer carries five hundred copies of the same id, and
  *     without this cache that is five hundred registry calls for one screen.
  *   - **The latest schema of a subject.** This one *must* expire. Registering a new version is how a schema
  *     evolves, and a KUI that cached "latest" forever would keep validating produce forms against a schema
  *     the topic had moved past. A short TTL is the trade: a few seconds of staleness in the produce form
  *     against a registry call for every keystroke that opens it.
  *
  * A failed lookup is never cached. That is `BoundedCache`'s own rule for a failing `load`, and this class
  * has to repeat it by hand because a failure here is an `Either` inside a successful effect rather than a
  * failed one — which is what makes it easy to cache by accident, and what makes a registry that was down for
  * one second stay "down" for the life of the cache.
  */
object CachingSchemaRegistry {

  final private class LookupFailure(val error: KuiError)
      extends RuntimeException("schema registry lookup failed")
      with NoStackTrace

  /** The `cache` metric attribute for the by-id cache: one short stable string per *kind* of cache, never a
    * per-cluster value (`BoundedCache`'s own rule — the cluster is its own attribute).
    */
  val SchemaCacheName: String = "serde.registry.schemas"

  val SubjectCacheName: String = "serde.registry.subjects"

  def resource[F[_]: Async](
      underlying: SchemaRegistry[F],
      config: SchemaRegistryConfig,
      cluster: ClusterId,
      metrics: CacheMetrics[F]
  ): Resource[F, SchemaRegistry[F]] =
    for {
      schemas <- BoundedCache.make[F, java.lang.Integer, RegistrySchema](
        SchemaCacheName,
        cluster,
        config.schemaCacheSize,
        ttl = None,
        metrics
      )
      subjects <- BoundedCache.make[F, String, Option[RegistrySchema]](
        SubjectCacheName,
        cluster,
        // Bounded by the number of topics an operator browses in a TTL window, which is small. The bound is
        // here to stop an unbounded walk of a ten-thousand-topic cluster from holding every subject at once,
        // not because the entries are large.
        maxSize = 512L,
        ttl = Some(config.subjectCacheTtl),
        metrics
      )
    } yield new Impl[F](underlying, schemas, subjects)

  final private class Impl[F[_]: Async](
      underlying: SchemaRegistry[F],
      schemas: BoundedCache[F, java.lang.Integer, RegistrySchema],
      subjects: BoundedCache[F, String, Option[RegistrySchema]]
  ) extends SchemaRegistry[F] {

    def schemaById(id: Int): F[Either[KuiError, RegistrySchema]] =
      cached(schemas, java.lang.Integer.valueOf(id))(underlying.schemaById(id))

    def latestForSubject(subject: String): F[Either[KuiError, Option[RegistrySchema]]] =
      cached(subjects, subject)(underlying.latestForSubject(subject))

    /** Read through once under concurrency, storing only success.
      *
      * The in-flight map is not a second cache: an entry exists only while its lookup is running and is
      * removed on success, failure and cancellation. Current waiters share either answer, while a later
      * caller retries a failure because only successful values reach `BoundedCache`.
      */
    private def cached[K <: AnyRef, V <: AnyRef](cache: BoundedCache[F, K, V], key: K)(
        load: F[Either[KuiError, V]]
    ): F[Either[KuiError, V]] =
      cache
        .getOrLoad(key)(
          load.flatMap(_.fold(error => Async[F].raiseError[V](new LookupFailure(error)), _.pure[F]))
        )
        .map(_.asRight[KuiError])
        .recover { case failure: LookupFailure => Left(failure.error) }
  }
}
