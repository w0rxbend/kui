package kui.serde.confluent

import scala.concurrent.duration.*

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*

import kui.cache.CacheMetrics
import kui.config.SafeUrl
import kui.kernel.ClusterId
import kui.kernel.error.KuiError
import kui.testkit.KuiIOSuite

/** The asymmetry between the two caches, which is the whole design of this class.
  *
  * A schema id is immutable, so its cache has a size and no expiry; the *latest* version of a subject is
  * what changes when a schema evolves, so its cache must expire. `CachingSchemaRegistry`'s scaladoc argues
  * both at length, and the second was carried by nothing: replacing `ttl = Some(config.subjectCacheTtl)`
  * with `ttl = None` left all 279 cases of `libs/kafka`, `libs/kafka-auth` and `libs/serde-confluent`
  * green, and a produce form would go on validating against a schema the topic had moved past for as long
  * as the process ran.
  */
final class CachingSchemaRegistrySuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod")

  private val schema: RegistrySchema =
    RegistrySchema(42, SchemaType.Avro, """{"type":"string"}""")

  /** Short enough that the case costs a fraction of a second, long enough that the two lookups before the
    * sleep cannot straddle it. The margin is an order of magnitude in both directions: expiry is decided
    * against `Clock[IO].monotonic`, which only moves forwards, so the failure this case can produce is a
    * true one.
    */
  private val ttl: FiniteDuration = 50.millis

  private def config: SchemaRegistryConfig =
    SchemaRegistryConfig(
      urls = NonEmptyList.one(SafeUrl.unsafe("http://registry:8081")),
      subjectCacheTtl = ttl
    )

  /** The same configuration with a TTL long enough that a few hundred lookups cannot straddle it.
    *
    * The bound cases below are about size and nothing else; a 50 ms TTL would let an expiry decide their
    * outcome and they would be measuring the clock instead.
    */
  private def roomyConfig: SchemaRegistryConfig =
    SchemaRegistryConfig(
      urls = NonEmptyList.one(SafeUrl.unsafe("http://registry:8081")),
      subjectCacheTtl = 1.minute
    )

  /** Which cache each read was attributed to, in order.
    *
    * `CacheMetrics.noop` is what this suite used, so the `cache` attribute — the only thing that tells a
    * dashboard whose hit rate it is looking at — was read by nothing at all. Swapping the two names left
    * every case here green while turning the schema cache's hit rate into the subject cache's.
    */
  final private class Naming(seen: Ref[IO, List[(String, String)]]) extends CacheMetrics[IO] {
    def hit(cache: String, cluster: ClusterId): IO[Unit] = seen.update(_ :+ (cache -> "hit"))
    def miss(cache: String, cluster: ClusterId): IO[Unit] = seen.update(_ :+ (cache -> "miss"))
    def staleRead(cache: String, cluster: ClusterId): IO[Unit] = seen.update(_ :+ (cache -> "stale"))
    def refreshFailed(cache: String, cluster: ClusterId): IO[Unit] =
      seen.update(_ :+ (cache -> "refreshFailed"))
  }

  /** Counts what reached the registry rather than what the caller asked for. */
  final private class Counting(calls: Ref[IO, Int]) extends SchemaRegistry[IO] {
    def schemaById(id: Int): IO[Either[KuiError, RegistrySchema]] =
      calls.update(_ + 1).as(Right(schema))

    def latestForSubject(subject: String): IO[Either[KuiError, Option[RegistrySchema]]] =
      calls.update(_ + 1).as(Right(Some(schema)))
  }

  private def cached(calls: Ref[IO, Int]): cats.effect.Resource[IO, SchemaRegistry[IO]] =
    CachingSchemaRegistry.resource[IO](new Counting(calls), config, cluster, CacheMetrics.noop[IO])

  test("the latest version of a subject is asked for again once its TTL has passed") {
    for {
      calls <- Ref.of[IO, Int](0)
      _ <- cached(calls).use { registry =>
        for {
          _ <- registry.latestForSubject("orders-value")
          _ <- registry.latestForSubject("orders-value")
          served <- calls.get
          _ <- IO.sleep(ttl * 10)
          _ <- registry.latestForSubject("orders-value")
          renewed <- calls.get
        } yield {
          assertEquals(served, 1, clue = "the second lookup inside the TTL was not served from the cache")
          assertEquals(
            renewed,
            2,
            clue = "the latest version of a subject was reused after its TTL: a produce form would " +
              "keep validating against a schema the topic has moved past"
          )
        }
      }
    } yield ()
  }

  test("a schema fetched by its id is never re-fetched, because an id is immutable") {
    // The other side of the asymmetry, so the case above cannot be satisfied by expiring both caches.
    for {
      calls <- Ref.of[IO, Int](0)
      _ <- cached(calls).use { registry =>
        for {
          _ <- registry.schemaById(42)
          _ <- IO.sleep(ttl * 10)
          _ <- registry.schemaById(42)
          made <- calls.get
        } yield assertEquals(made, 1, clue = "a schema id was asked about twice")
      }
    } yield ()
  }

  test("each cache is counted under its own name, so a hit rate belongs to the cache it came from") {
    /*
     * Ungated until now: exchanging the values of `SchemaCacheName` and `SubjectCacheName` left
     * `./mill libs.serdeConfluent.test` green. Both names reach `BoundedCache` as the `cache` metric
     * attribute and nothing else; with `CacheMetrics.noop` in every fixture, no assertion had ever read
     * one.
     *
     * What that costs is not a test failure but an operator's conclusion. The by-id cache is the one that
     * carries a page of five hundred records on one lookup, so its hit rate is near one; the subject cache
     * expires on a timer and its hit rate is low by design. Swapped, a dashboard says the throughput cache
     * is missing constantly and the produce form's cache never expires, and the obvious remedy — raise
     * `schemaCacheSize` — is applied to the wrong cache.
     */
    for {
      calls <- Ref.of[IO, Int](0)
      seen <- Ref.of[IO, List[(String, String)]](Nil)
      _ <- CachingSchemaRegistry
        .resource[IO](new Counting(calls), config, cluster, new Naming(seen))
        .use(registry =>
          registry.schemaById(42) >> registry.schemaById(42) >> registry.latestForSubject("orders-value")
        )
      events <- seen.get
    } yield {
      // The two ids are one miss and one hit, and both belong to the by-id cache.
      assertEquals(
        events.filter(_._1 == CachingSchemaRegistry.SchemaCacheName).map(_._2),
        List("miss", "hit"),
        clue = s"the by-id cache's reads were not attributed to it: $events"
      )
      assertEquals(events.filter(_._1 == CachingSchemaRegistry.SubjectCacheName).map(_._2), List("miss"))
      // And the constants are the documented strings, so exchanging the two values is caught even though
      // the wiring above would still be self-consistent after the swap.
      assertEquals(CachingSchemaRegistry.SchemaCacheName, "serde.registry.schemas")
      assertEquals(CachingSchemaRegistry.SubjectCacheName, "serde.registry.subjects")
    }
  }

  test("the by-id cache is built with the size the operator configured, not one this class chose") {
    /*
     * Ungated until now: replacing `config.schemaCacheSize` with a literal `512L` left
     * `./mill libs.serdeConfluent.test` green, because the configured value reached nothing any case read.
     *
     * Only the reliable direction of Caffeine's bound is asserted. Caffeine evicts *approximately* at
     * `maximumSize`, so "the entry after the bound is evicted" is not a safe assertion — but "a cache
     * bounded at a thousand still holds a thousand" is, because a bound is never enforced early. A
     * hard-coded 512 therefore shows up as misses on ids the operator paid for room to keep, and an
     * operator whose registry holds forty thousand schemas has no way to fix it from configuration.
     */
    val configured: Long = 1000L

    for {
      calls <- Ref.of[IO, Int](0)
      _ <- CachingSchemaRegistry
        .resource[IO](
          new Counting(calls),
          roomyConfig.copy(schemaCacheSize = configured),
          cluster,
          CacheMetrics.noop[IO]
        )
        .use { registry =>
          val ids = (1 to configured.toInt).toList
          ids.traverse_(registry.schemaById) >> ids.traverse_(registry.schemaById)
        }
      made <- calls.get
    } yield assertEquals(
      made,
      configured.toInt,
      clue = "a schema id was fetched twice: the cache was built smaller than the configuration asked for"
    )
  }

  test("the subject cache holds the topics an operator browses inside one TTL window") {
    /*
     * Ungated until now: `maxSize = 512L` -> `5L` for the subject cache left
     * `./mill libs.serdeConfluent.test` green, because every case used exactly one subject.
     *
     * The bound's own comment says what it is for — "to stop an unbounded walk of a ten-thousand-topic
     * cluster from holding every subject at once, not because the entries are large" — which is a
     * statement about how many subjects must fit. A bound of five would send the produce form back to the
     * registry on nearly every keystroke while the TTL, the thing this cache exists to enforce, still
     * looked perfectly healthy.
     */
    val browsed: Int = 400

    for {
      calls <- Ref.of[IO, Int](0)
      _ <- CachingSchemaRegistry
        .resource[IO](new Counting(calls), roomyConfig, cluster, CacheMetrics.noop[IO])
        .use { registry =>
          val subjects = (1 to browsed).toList.map(index => s"topic-$index-value")
          subjects.traverse_(registry.latestForSubject) >> subjects.traverse_(registry.latestForSubject)
        }
      made <- calls.get
    } yield assertEquals(
      made,
      browsed,
      clue = "a subject was asked for twice inside one TTL window: the cache is bounded too tightly"
    )
  }
}
