package kui.consumer.application

import java.time.Instant

import munit.FunSuite

import kui.cache.{Snapshot, SnapshotStatus}
import kui.kernel.error.{InfrastructureError, KuiError}

/** That a group list KUI already has survives its cluster going away.
  *
  * `SnapshotFreshness.of` states the rule in its own scaladoc — "a snapshot with a value and an offline
  * status is `Stale` and not `Unavailable`: data from the last successful scrape, with the time it was taken
  * beside it, is what the whole snapshot design exists to keep on screen when a cluster stops answering" —
  * and nothing asserted it. Answering `Unavailable` from that arm left `./mill services.consumer.__.test`
  * at 185/185 green, and the Consumers table would then empty itself the first time a coordinator went
  * down.
  */
final class SnapshotFreshnessSuite extends FunSuite {

  private val At: Instant = Instant.parse("2026-09-06T10:00:00Z")

  private val Since: Instant = Instant.parse("2026-09-06T10:04:00Z")

  private val Broken: KuiError = InfrastructureError.Unreachable("kafka", "the coordinator is not answering")

  private val Fallback: KuiError = InfrastructureError.Unreachable("kafka", "nothing has been loaded")

  test("a scrape that succeeded is fresh, and says when") {
    val fresh = SnapshotFreshness.of(Snapshot(Some(7), SnapshotStatus.Online, Some(At)), Fallback)

    assertEquals(fresh, SnapshotFreshness.Fresh(At))
    assertEquals(fresh.observedAt, Some(At))
    assert(fresh.isFresh)
  }

  test("rows KUI already has are stale when the cluster stops answering, and are not thrown away") {
    val snapshot = Snapshot(Some(7), SnapshotStatus.Offline(Broken, Since), Some(At))

    SnapshotFreshness.of(snapshot, Fallback) match {
      case SnapshotFreshness.Stale(at, reason) =>
        assertEquals(at, At)
        // The whole error and not its message: the API layer classifies it into a reason code, and a
        // sentence flattened here is a reason code nobody can compute.
        assertEquals(reason, Broken)
      case other => fail(s"a snapshot with a value must stay renderable: $other")
    }
  }

  test("a cluster that has never answered is unavailable, carrying the failure that stopped it") {
    val never = Snapshot(None, SnapshotStatus.Offline(Broken, Since), None)

    assertEquals(SnapshotFreshness.of(never, Fallback), SnapshotFreshness.Unavailable(Broken))
    assertEquals(SnapshotFreshness.of(never, Fallback).observedAt, None)
  }

  test("a cell that has not loaded yet is unavailable with the caller's own sentence") {
    val starting = Snapshot(None, SnapshotStatus.Initializing, None)

    assertEquals(SnapshotFreshness.of(starting, Fallback), SnapshotFreshness.Unavailable(Fallback))
  }
}
