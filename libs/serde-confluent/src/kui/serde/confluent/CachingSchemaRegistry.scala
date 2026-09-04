package kui.serde.confluent

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

    /** Read through, store only success.
      *
      * `getOrLoad` is not used, and the reason is worth stating: it caches whatever the load returns, and
      * what this load returns is an `Either` whose `Left` is a failure. Storing that would turn one refused
      * connection into a cache entry that keeps answering "the registry is down" after the registry has come
      * back. The cost of doing it this way is that a burst of concurrent misses can produce more than one
      * upstream call for the same key; that is bounded by the upstream's own bulkhead and is the cheaper of
      * the two mistakes.
      */
    private def cached[K <: AnyRef, V <: AnyRef](cache: BoundedCache[F, K, V], key: K)(
        load: F[Either[KuiError, V]]
    ): F[Either[KuiError, V]] =
      cache.get(key).flatMap {
        case Some(hit) => hit.asRight[KuiError].pure[F]
        case None =>
          load.flatTap {
            case Right(value) => cache.put(key, value)
            case Left(_) => Async[F].unit
          }
      }
  }
}
