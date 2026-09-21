package kui.metrics.infrastructure.prometheus

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.kernel.error.{InfrastructureError, KuiError}

final class PrometheusQueryCacheSuite extends CatsEffectSuite {

  private val transient: KuiError = InfrastructureError.Upstream("prometheus", 503)
  private val permanent: KuiError = InfrastructureError.AuthFailed("prometheus")
  private val transientOnly: KuiError => Boolean = _ == transient

  private def cache(maxBytes: Long = 1024L) =
    PrometheusQueryCache.resource[IO, String, String](
      freshTtl = 10.seconds,
      staleTtl = 30.seconds,
      maxBytes = maxBytes,
      weigh = _.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong
    )

  test("fresh hits avoid a second load and expiry refreshes under effect time") {
    val program = cache().use { stored =>
      for {
        loads <- Ref.of[IO, Int](0)
        load = loads.updateAndGet(_ + 1).map(value => Right(s"value-$value"))
        first <- stored.getOrLoad("key")(load)(transientOnly)
        hit <- stored.getOrLoad("key")(load)(transientOnly)
        _ <- IO.sleep(11.seconds)
        refreshed <- stored.getOrLoad("key")(load)(transientOnly)
        count <- loads.get
      } yield (first, hit, refreshed, count)
    }

    TestControl.executeEmbed(program).map { case (first, hit, refreshed, count) =>
      assertEquals(first.map(_.value), Right("value-1"))
      assertEquals(first.map(_.access), Right(QueryCacheAccess.Loaded))
      assertEquals(hit.map(_.access), Right(QueryCacheAccess.Hit))
      assertEquals(refreshed.map(_.value), Right("value-2"))
      assertEquals(count, 2)
    }
  }

  test("identical loads coalesce while different keys execute concurrently") {
    val program = cache().use { stored =>
      for {
        gate <- Deferred[IO, Unit]
        started <- Ref.of[IO, Set[String]](Set.empty)
        loads <- Ref.of[IO, Int](0)
        load = (key: String) => started.update(_ + key) *> loads.update(_ + 1) *> gate.get.as(Right(key))
        first <- stored.getOrLoad("same")(load("same"))(transientOnly).start
        _ <- waitUntil(started.get.map(_.contains("same")))
        second <- stored.getOrLoad("same")(load("duplicate"))(transientOnly).start
        _ <- waitUntil(stored.stats.map(_.coalescedWaiters == 1))
        other <- stored.getOrLoad("other")(load("other"))(transientOnly).start
        _ <- waitUntil(started.get.map(_.size == 2))
        beforeRelease <- loads.get
        _ <- gate.complete(())
        results <- (first.joinWithNever, second.joinWithNever, other.joinWithNever).tupled
      } yield (beforeRelease, results)
    }

    TestControl.executeEmbed(program).map { case (loads, (first, second, other)) =>
      assertEquals(loads, 2)
      assertEquals(first.map(_.value), Right("same"))
      assertEquals(second.map(_.value), Right("same"))
      assertEquals(second.map(_.access), Right(QueryCacheAccess.Coalesced))
      assertEquals(other.map(_.value), Right("other"))
    }
  }

  test("cancelling one waiter does not cancel the shared load needed by another") {
    val program = cache().use { stored =>
      for {
        started <- Deferred[IO, Unit]
        gate <- Deferred[IO, Unit]
        loads <- Ref.of[IO, Int](0)
        load = loads.update(_ + 1) *> started.complete(()).void *> gate.get.as(Right("answer"))
        cancelled <- stored.getOrLoad("key")(load)(transientOnly).start
        _ <- started.get
        waiting <- stored.getOrLoad("key")(load)(transientOnly).start
        _ <- waitUntil(stored.stats.map(_.coalescedWaiters == 1))
        _ <- cancelled.cancel
        _ <- gate.complete(())
        answer <- waiting.joinWithNever
        count <- loads.get
        stats <- stored.stats
      } yield (answer, count, stats)
    }

    TestControl.executeEmbed(program).map { case (answer, count, stats) =>
      assertEquals(answer.map(_.value), Right("answer"))
      assertEquals(answer.map(_.access), Right(QueryCacheAccess.Coalesced))
      assertEquals(count, 1)
      assertEquals(stats.inFlight, 0)
    }
  }

  test("fresh and stale TTL boundaries are inclusive and expire immediately after") {
    val program = cache().use { stored =>
      for {
        loads <- Ref.of[IO, Int](0)
        load = loads.updateAndGet(_ + 1).map(value => Right(s"value-$value"))
        _ <- stored.getOrLoad("fresh")(load)(transientOnly)
        _ <- IO.sleep(10.seconds)
        atFreshBoundary <- stored.getOrLoad("fresh")(load)(transientOnly)
        _ <- IO.sleep(1.nanosecond)
        afterFreshBoundary <- stored.getOrLoad("fresh")(load)(transientOnly)
        _ <- IO.sleep(30.seconds)
        atStaleBoundary <- stored.getOrLoad("fresh")(IO.pure(Left(transient)))(transientOnly)
        _ <- IO.sleep(1.nanosecond)
        afterStaleBoundary <- stored.getOrLoad("fresh")(IO.pure(Left(transient)))(transientOnly)
        count <- loads.get
      } yield (atFreshBoundary, afterFreshBoundary, atStaleBoundary, afterStaleBoundary, count)
    }

    TestControl.executeEmbed(program).map {
      case (atFreshBoundary, afterFreshBoundary, atStaleBoundary, afterStaleBoundary, count) =>
        assertEquals(atFreshBoundary.map(_.access), Right(QueryCacheAccess.Hit))
        assertEquals(afterFreshBoundary.map(_.value), Right("value-2"))
        assertEquals(atStaleBoundary.map(_.freshness), Right(QueryCacheFreshness.Stale))
        assertEquals(afterStaleBoundary, Left(transient))
        assertEquals(count, 2)
    }
  }

  test("stale fallback is transient-only and a load crossing stale expiry is refused") {
    val program = cache().use { stored =>
      for {
        _ <- stored.getOrLoad("permanent")(IO.pure(Right("protected")))(transientOnly)
        _ <- stored.getOrLoad("crossing")(IO.pure(Right("old")))(transientOnly)
        _ <- IO.sleep(11.seconds)
        refused <- stored.getOrLoad("permanent")(IO.pure(Left(permanent)))(transientOnly)
        _ <- IO.sleep(18.seconds)
        crossed <- stored
          .getOrLoad("crossing")(IO.sleep(2.seconds).as(Left(transient)))(transientOnly)
      } yield (refused, crossed)
    }

    TestControl.executeEmbed(program).map { case (refused, crossed) =>
      assertEquals(refused, Left(permanent))
      assertEquals(crossed, Left(transient))
    }
  }

  test("failed loads are not cached and always clear their in-flight entry") {
    val program = cache().use { stored =>
      for {
        first <- stored.getOrLoad("key")(IO.pure(Left(permanent)))(transientOnly)
        afterFailure <- stored.stats
        second <- stored.getOrLoad("key")(IO.pure(Right("recovered")))(transientOnly)
        afterRecovery <- stored.stats
      } yield (first, afterFailure, second, afterRecovery)
    }

    TestControl.executeEmbed(program).map { case (first, failed, second, recovered) =>
      assertEquals(first, Left(permanent))
      assertEquals(failed.entries, 0)
      assertEquals(failed.inFlight, 0)
      assertEquals(second.map(_.value), Right("recovered"))
      assertEquals(recovered.entries, 1)
      assertEquals(recovered.inFlight, 0)
    }
  }

  test("detailed lookups retain loaded versus coalesced access even when a shared load fails") {
    val program = cache().use { stored =>
      for {
        started <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        load = started.complete(()).void *> release.get.as(Left(permanent))
        owner <- stored.lookup("key")(load)(transientOnly).start
        _ <- started.get
        joined <- stored.lookup("key")(load)(transientOnly).start
        _ <- waitUntil(stored.stats.map(_.coalescedWaiters == 1))
        _ <- release.complete(())
        ownerResult <- owner.joinWithNever
        joinedResult <- joined.joinWithNever
      } yield (ownerResult, joinedResult)
    }

    TestControl.executeEmbed(program).map { case (owner, joined) =>
      assertEquals(owner.result, Left(permanent))
      assertEquals(owner.access, QueryCacheAccess.Loaded)
      assertEquals(owner.freshness, QueryCacheFreshness.Fresh)
      assertEquals(joined.result, Left(permanent))
      assertEquals(joined.access, QueryCacheAccess.Coalesced)
      assertEquals(joined.freshness, QueryCacheFreshness.Fresh)
    }
  }

  test("decoded response weight is a hard bound") {
    val program = cache(maxBytes = 5L).use { weighted =>
      for {
        _ <- weighted.getOrLoad("a")(IO.pure(Right("aaa")))(transientOnly)
        _ <- weighted.getOrLoad("b")(IO.pure(Right("bbb")))(transientOnly)
        weightedStats <- weighted.stats
      } yield weightedStats
    }

    TestControl.executeEmbed(program).map { stats =>
      assertEquals(stats.entries, 1)
      assertEquals(stats.weightBytes, 3L)
    }
  }

  test("the decoded-result weigher accounts for UTF-8 labels, samples, timestamps and diagnostics") {
    val at = PrometheusTimestamp.fromInstant(Instant.EPOCH)
    val labels = MetricLabels(Map("é" -> "値"))
    val diagnostics = QueryDiagnostics(warningCount = 2, infoCount = 1)
    val instant = QueryAnswer(
      InstantQueryResult(Vector(InstantSeries(labels, QuerySample(at, finite(1.0))))),
      QueryFreshness.Fresh(Instant.EPOCH),
      diagnostics
    )
    val range = QueryAnswer(
      RangeQueryResult(
        Vector(
          RangeSeries(
            labels,
            Vector(
              QuerySample(at, finite(1.0)),
              QuerySample(at, SampleValue.NonFinite(NonFiniteKind.NaN))
            )
          )
        )
      ),
      QueryFreshness.Stale(Instant.EPOCH, Instant.EPOCH.plusSeconds(1)),
      diagnostics
    )

    assertEquals(PrometheusQueryWeight.instant(instant), 205L)
    assertEquals(PrometheusQueryWeight.range(range), 245L)
  }

  test("an individually overweight decoded result is returned but not retained") {
    val at = PrometheusTimestamp.fromInstant(Instant.EPOCH)
    val answer = QueryAnswer(
      InstantQueryResult(
        Vector(
          InstantSeries(MetricLabels(Map("large" -> "value")), QuerySample(at, finite(1.0)))
        )
      ),
      QueryFreshness.Fresh(Instant.EPOCH),
      QueryDiagnostics.Empty
    )
    val program = PrometheusQueryCache
      .resource[IO, String, QueryAnswer[InstantQueryResult]](
        1.hour,
        2.hours,
        maxBytes = PrometheusQueryWeight.instant(answer) - 1L,
        PrometheusQueryWeight.instant
      )
      .use { stored =>
        for {
          result <- stored.getOrLoad("large")(IO.pure(Right(answer)))(transientOnly)
          stats <- stored.stats
        } yield (result, stats)
      }

    TestControl.executeEmbed(program).map { case (result, stats) =>
      assertEquals(result.map(_.value), Right(answer))
      assertEquals(stats.entries, 0)
      assertEquals(stats.weightBytes, 0L)
    }
  }

  test("an overweight result cannot overflow accounting or evict an existing value") {
    val program = PrometheusQueryCache
      .resource[IO, String, String](
        1.hour,
        2.hours,
        maxBytes = 10L,
        value => if value == "huge" then Long.MaxValue else value.length.toLong
      )
      .use { stored =>
        for {
          _ <- stored.getOrLoad("small")(IO.pure(Right("small")))(transientOnly)
          huge <- stored.getOrLoad("huge")(IO.pure(Right("huge")))(transientOnly)
          retained <- stored.getOrLoad("small")(IO.pure(Right("reloaded")))(transientOnly)
          stats <- stored.stats
        } yield (huge, retained, stats)
      }

    TestControl.executeEmbed(program).map { case (huge, retained, stats) =>
      assertEquals(huge.map(_.value), Right("huge"))
      assertEquals(retained.map(_.access), Right(QueryCacheAccess.Hit))
      assertEquals(stats.entries, 1)
      assertEquals(stats.weightBytes, 5L)
    }
  }

  test("replacement updates rather than accumulates decoded weight") {
    val program = cache(maxBytes = 10L).use { stored =>
      for {
        _ <- stored.getOrLoad("same")(IO.pure(Right("12345")))(transientOnly)
        _ <- IO.sleep(11.seconds)
        _ <- stored.getOrLoad("same")(IO.pure(Right("x")))(transientOnly)
        stats <- stored.stats
      } yield stats
    }

    TestControl.executeEmbed(program).map { stats =>
      assertEquals(stats.entries, 1)
      assertEquals(stats.weightBytes, 1L)
    }
  }

  test("entry count is a hard bound") {
    val program = PrometheusQueryCache
      .resource[IO, Int, String](1.hour, 2.hours, maxBytes = 4096L, _ => 1L)
      .use { bounded =>
        (0 to PrometheusQueryCache.MaxEntries).toList.traverse_ { key =>
          bounded.getOrLoad(key)(IO.pure(Right(key.toString)))(transientOnly).void
        } *> bounded.stats
      }

    TestControl.executeEmbed(program).map { stats =>
      assertEquals(stats.entries, PrometheusQueryCache.MaxEntries)
      assertEquals(stats.weightBytes, PrometheusQueryCache.MaxEntries.toLong)
    }
  }

  test("source composition partitions the aggregate entry bound across instant and range caches") {
    val perKind = PrometheusQueryClient.CacheEntriesPerKind
    assertEquals(perKind * 2, PrometheusQueryCache.MaxEntries)

    val program = PrometheusQueryCache
      .resource[IO, Int, String](
        1.hour,
        2.hours,
        maxBytes = 4096L,
        _ => 1L,
        maxEntries = perKind
      )
      .use { bounded =>
        (0 to perKind).toList.traverse_ { key =>
          bounded.getOrLoad(key)(IO.pure(Right(key.toString)))(transientOnly).void
        } *> bounded.stats
      }

    TestControl.executeEmbed(program).map { stats =>
      assertEquals(stats.entries, perKind)
      assertEquals(stats.weightBytes, perKind.toLong)
    }
  }

  test("resource release cancels shared loads and clears cache state") {
    val program = for {
      allocated <- cache().allocated
      (stored, release) = allocated
      started <- Deferred[IO, Unit]
      cancelled <- Deferred[IO, Unit]
      waiter <- stored
        .getOrLoad("key")(
          started.complete(()).void *>
            IO.never[Either[KuiError, String]].onCancel(cancelled.complete(()).void)
        )(transientOnly)
        .start
      _ <- started.get
      _ <- release
      _ <- cancelled.get
      result <- waiter.joinWithNever
      stats <- stored.stats
    } yield (result, stats)

    TestControl.executeEmbed(program).map { case (result, stats) =>
      assert(result.isLeft)
      assert(!result.toString.contains("key"), result.toString)
      assertEquals(stats.entries, 0)
      assertEquals(stats.weightBytes, 0L)
      assertEquals(stats.inFlight, 0)
      assertEquals(stats.coalescedWaiters, 0)
      assert(stats.closed)
    }
  }

  private def waitUntil(condition: IO[Boolean]): IO[Unit] =
    condition.flatMap(if _ then IO.unit else IO.cede *> waitUntil(condition))

  private def finite(value: Double): SampleValue.Finite =
    SampleValue.finite(value).fold(problem => fail(problem.toString), identity)
}
