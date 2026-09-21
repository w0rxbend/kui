package kui.cluster.infrastructure.store

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref}

import kui.cluster.domain.{ClusterProfile, ProfileVersion}
import kui.cluster.infrastructure.TestProfiles
import kui.config.store.{StoreChange, StoreRecord}
import kui.kernel.ClusterId
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** Two properties of the listener that `ProfileChangeListenerSuite` leaves open: **when** it retries, and in
  * **what order** it publishes a removal.
  *
  * Both were found by mutation against `./mill services.cluster.infrastructure.test` (153 cases, 14 suites),
  * which stayed green for each:
  *
  *   - replacing `withBackoff(action, (delay * 2).min(ProfileChangeListener.MaxBackoff))` with
  *     `withBackoff(action, delay * 2)` — the cap `ProfileChangeListener.scala:50-56` argues for at length
  *     (*"an uncapped doubling reaches half an hour after a dozen failures and the store may have come back
  *     thirty minutes earlier"*) — left 153/153 green, because the only retry case in that suite fails once;
  *   - dropping `.sortBy(_.value)` from `diff`'s removals left 153/153 green, because no case removes two
  *     clusters at a time.
  *
  * The first case is driven by [[TestControl]] rather than by a real clock: the cap is thirty seconds and a
  * dozen uncapped doublings is over an hour, so a wall-clock assertion is not a slow test, it is an
  * impossible one.
  */
final class ProfileChangeScheduleSuite extends KuiIOSuite {

  private val at: Instant = Instant.parse("2026-09-11T09:00:00Z")

  private def profile(id: String, version: Long): ClusterProfile =
    TestProfiles.profile(id = id, version = version)

  private def recordFor(p: ClusterProfile, version: Long): StoreRecord =
    StoreRecord(
      1,
      ClusterConfigStoreAdapter.keyFor(p.id).toOption.get,
      version,
      StubConfigStore.At,
      "someone",
      deleted = false,
      ClusterRecordCodec.encode(p)
    )

  /** The longest gap the policy permits between two attempts: the cap, plus the jitter added on top of it.
    *
    * `withBackoff` sleeps `delay + jitter` where `jitter` is drawn from `[0, delay / 2]`, so a capped delay
    * can be at most one and a half times [[ProfileChangeListener.MaxBackoff]]. Derived here rather than
    * written as `45.seconds` so that moving the cap moves the assertion with it.
    */
  private val LongestPermittedGap: FiniteDuration =
    ProfileChangeListener.MaxBackoff + ProfileChangeListener.MaxBackoff / 2

  test("aReconcileThatKeepsFailingBacksOffButNeverBeyondTheCap") {
    val failuresBeforeSuccess = 12

    val programme = for {
      attempts <- Ref.of[IO, List[FiniteDuration]](Nil)
      reconcile = (_: List[ClusterProfile]) =>
        IO.monotonic.flatMap(now => attempts.update(now :: _)) *>
          attempts.get.flatMap(seen =>
            if seen.size <= failuresBeforeSuccess then IO.raiseError(new RuntimeException("the store is out"))
            else IO.unit
          )
      store <- StubConfigStore()
      logger <- FakeStructuredLogger[IO]
      _ <- ClusterConfigStoreAdapter
        .resource[IO](store, logger)
        .flatMap(adapter => ProfileChangeListener.resource[IO](adapter, reconcile, logger))
        .use { listener =>
          store.hold(recordFor(profile("prod", 1L), 1L)) *>
            store.push(StoreChange.Upserted(recordFor(profile("prod", 1L), 1L))) *>
            eventually(listener.known)(_.nonEmpty).void
        }
      seen <- attempts.get
    } yield seen.reverse

    TestControl.executeEmbed(programme).map { instants =>
      val gaps = instants.sliding(2).collect { case List(before, after) => after - before }.toList

      assert(
        instants.size >= failuresBeforeSuccess,
        s"the emission was lost after ${instants.size} attempts rather than being retried"
      )
      assert(gaps.nonEmpty, "a retry schedule with no gaps is not a schedule")
      // The doubling is real: without it the first gaps would all be `InitialBackoff`.
      assert(
        gaps.exists(_ > ProfileChangeListener.InitialBackoff * 2),
        s"the backoff never grew at all: $gaps"
      )
      // And it stops growing. This is the line the cap is.
      gaps.zipWithIndex.foreach { (gap, index) =>
        assert(
          gap <= LongestPermittedGap,
          s"attempt ${index + 2} waited ${gap.toSeconds}s, past the " +
            s"${LongestPermittedGap.toSeconds}s the cap allows"
        )
      }
    }
  }

  test("twoClustersRemovedInOneEmissionArePublishedInAStableOrder") {
    // `diff` emits the removals of a complete list, and a `Set` difference has no order of its own. Two
    // replicas of KUI reading the same store must log and publish the same sequence, or an operator
    // comparing two replicas' logs sees a difference that is not one.
    val known = Map(
      ClusterId.unsafe("staging") -> ProfileVersion.unsafe(4L),
      ClusterId.unsafe("analytics") -> ProfileVersion.unsafe(2L),
      ClusterId.unsafe("prod") -> ProfileVersion.unsafe(9L)
    )

    val (_, events) = ProfileChangeListener.diff(known, List(profile("prod", 9L)), at)
    val removed = events.filter(_.kind == ProfileChanged.Kind.Removed).map(_.clusterId.value)

    assertEquals(removed, List("analytics", "staging"))
    // The same question asked from a map built the other way round, so the case cannot pass on the
    // iteration order a three-element map happens to have.
    val (_, mirrored) = ProfileChangeListener.diff(
      Map(
        ClusterId.unsafe("prod") -> ProfileVersion.unsafe(9L),
        ClusterId.unsafe("staging") -> ProfileVersion.unsafe(4L),
        ClusterId.unsafe("analytics") -> ProfileVersion.unsafe(2L)
      ),
      List(profile("prod", 9L)),
      at
    )

    assertEquals(mirrored.filter(_.kind == ProfileChanged.Kind.Removed).map(_.clusterId.value), removed)
  }

  private def eventually[A](read: IO[A])(holds: A => Boolean): IO[A] =
    read.flatMap(value =>
      if holds(value) then IO.pure(value) else IO.sleep(10.millis) *> eventually(read)(holds)
    )
}
