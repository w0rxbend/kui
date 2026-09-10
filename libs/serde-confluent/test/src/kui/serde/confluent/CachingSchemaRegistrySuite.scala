package kui.serde.confluent

import scala.concurrent.duration.*

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}

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
}
