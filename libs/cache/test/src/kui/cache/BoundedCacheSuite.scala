package kui.cache

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*

import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite

/** The four promises of ADR-016's bounded cache: it bounds, it expires, it loads once, and it says what it
  * is doing.
  */
final class BoundedCacheSuite extends KuiIOSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")

  private def cacheOf(
      maxSize: Long,
      ttl: Option[FiniteDuration]
  ): Resource[IO, (BoundedCache[IO, String, String], FakeCacheMetrics[IO])] =
    for {
      metrics <- Resource.eval(FakeCacheMetrics.create[IO])
      cache <- BoundedCache.make[IO, String, String]("test.cache", cluster, maxSize, ttl, metrics)
    } yield (cache, metrics)

  test("a value that was put is a value that is got") {
    cacheOf(10, None).use { (cache, _) =>
      cache.put("k", "v") >> cache.get("k").assertEquals(Some("v"))
    }
  }

  test("a key that was never written is a miss, not an error") {
    cacheOf(10, None).use((cache, _) => cache.get("absent").assertEquals(None))
  }

  test("it evicts at the bound rather than growing without limit") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        _ <- (1 to 500).toList.traverse_(n => cache.put(s"k$n", s"v$n"))
        stats <- cache.stats
        // Caffeine evicts approximately, keeping the entries actually being used, so the assertion is
        // that the cache stayed near its bound — not that it never held an eleventh entry for a moment.
        // The number that matters to an operator is that 500 writes into a cache of 10 did not retain 500.
        _ <- IO(assert(stats.size <= 20L, s"size was ${stats.size}"))
        _ <- IO(assert(stats.evictions > 0L, s"evictions were ${stats.evictions}"))
      } yield ()
    }
  }

  test("an entry expires after its time to live, and the cache does not hand back a stale value") {
    // Virtual time. The contract is about what is true an hour later, and a suite that waited an hour
    // would be untestable, while one that waited a second would be flaky.
    TestControl.executeEmbed {
      cacheOf(10, Some(1.hour)).use { (cache, _) =>
        for {
          _ <- cache.put("k", "v")
          _ <- IO.sleep(59.minutes)
          _ <- cache.get("k").assertEquals(Some("v"))
          _ <- IO.sleep(2.minutes)
          _ <- cache.get("k").assertEquals(None)
        } yield ()
      }
    }
  }

  test("a cache with no time to live keeps a value indefinitely, which is right for an immutable key") {
    TestControl.executeEmbed {
      cacheOf(10, None).use { (cache, _) =>
        cache.put("schema-42", "{}") >> IO.sleep(30.days) >> cache.get("schema-42").assertEquals(Some("{}"))
      }
    }
  }

  test("getOrLoad loads exactly once for concurrent callers, so a burst of reads is one upstream call") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        loads <- Ref.of[IO, Int](0)
        load = loads.update(_ + 1) >> IO.cede >> IO.pure("loaded")
        results <- List.fill(50)(cache.getOrLoad("k")(load)).parSequence
        _ <- IO(assertEquals(results.distinct, List("loaded")))
        // Fifty records of a page arriving at the same missing schema must produce one registry call.
        // Fifty would be a cache that turns a read burst into an upstream burst exactly when the
        // upstream is already the slow thing.
        _ <- loads.get.assertEquals(1)
      } yield ()
    }
  }

  test("a failing load is not cached: the next caller tries again") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        attempts <- Ref.of[IO, Int](0)
        failing = attempts.update(_ + 1) >> IO.raiseError[String](new RuntimeException("upstream down"))
        _ <- cache.getOrLoad("k")(failing).attempt
        _ <- cache.getOrLoad("k")(failing).attempt
        _ <- attempts.get.assertEquals(2)
        _ <- cache.get("k").assertEquals(None)
      } yield ()
    }
  }

  test("invalidate removes one key and leaves the rest") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        _ <- cache.put("a", "1") >> cache.put("b", "2")
        _ <- cache.invalidate("a")
        _ <- cache.get("a").assertEquals(None)
        _ <- cache.get("b").assertEquals(Some("2"))
      } yield ()
    }
  }

  test("invalidateAll drops everything, which is what a profile change needs") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        _ <- cache.put("a", "1") >> cache.put("b", "2")
        _ <- cache.invalidateAll
        _ <- cache.stats.map(_.size).assertEquals(0L)
      } yield ()
    }
  }

  test("hits and misses reach the metrics, which is ADR-016's requirement on every cache") {
    cacheOf(10, None).use { (cache, metrics) =>
      for {
        _ <- cache.get("absent")
        _ <- cache.put("k", "v")
        _ <- cache.get("k")
        _ <- metrics.countOf("miss").assertEquals(1)
        _ <- metrics.countOf("hit").assertEquals(1)
        entries <- metrics.entries
        _ <- IO(assert(entries.forall(e => e.cache == "test.cache" && e.cluster == cluster)))
      } yield ()
    }
  }

  test("getOrLoad counts a miss on the load and a hit on every read after it") {
    cacheOf(10, None).use { (cache, metrics) =>
      for {
        _ <- cache.getOrLoad("k")(IO.pure("v"))
        _ <- cache.getOrLoad("k")(IO.pure("v"))
        _ <- cache.getOrLoad("k")(IO.pure("v"))
        _ <- metrics.countOf("miss").assertEquals(1)
        _ <- metrics.countOf("hit").assertEquals(2)
      } yield ()
    }
  }

  test("stats report the counts Caffeine recorded, so an operator can see whether the cache is helping") {
    cacheOf(10, None).use { (cache, _) =>
      for {
        _ <- cache.get("absent")
        _ <- cache.put("k", "v")
        _ <- cache.get("k")
        stats <- cache.stats
        _ <- IO(assertEquals(stats.hits, 1L))
        _ <- IO(assertEquals(stats.misses, 1L))
        _ <- IO(assertEquals(stats.size, 1L))
      } yield ()
    }
  }

  test("releasing the resource empties the cache, so a closed cache holds nothing") {
    for {
      metrics <- FakeCacheMetrics.create[IO]
      escaped <- BoundedCache
        .make[IO, String, String]("test.cache", cluster, 10, None, metrics)
        .use(cache => cache.put("k", "v").as(cache))
      _ <- escaped.get("k").assertEquals(None)
    } yield ()
  }
}
