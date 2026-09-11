package kui.alerts.application

import cats.effect.IO
import munit.CatsEffectSuite

import kui.alerts.domain.{AlertResolutionKind, AlertRule}
import kui.kernel.ClusterId
import kui.kernel.error.ErrorCode
import kui.security.audit.MutationOutcome
import kui.testkit.fakes.FakeStructuredLogger

/** The read: the counts, the marker and the two situations a zero must not stand for. */
final class AlertFeedSuite extends CatsEffectSuite {

  import AlertsRig.*

  private def rig: IO[(FakeStore, AlertUseCases[IO])] =
    for {
      store <- FakeStore.create
      audit <- RecordingSink.create
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new Profiles, audit, logger)
    } yield (store, AlertUseCases.make[IO](new Profiles, store, guard))

  test("a feed with no events answers ok with an empty list") {
    for {
      (_, alerts) <- rig
      feed <- alerts.feed(caller, cluster, 50, markRead = false)
    } yield {
      assertEquals(feed.map(_.events), Right(Nil))
      assertEquals(feed.map(_.openCount), Right(0))
      assertEquals(feed.map(_.total), Right(0))
      // And it is `Right`: an empty feed is an answer, never a 404 and never an error. The card that
      // draws it sits beside cards that work.
      assert(feed.isRight)
    }
  }

  test("the open count is the count of open events and not of all of them") {
    val open = event(AlertRule.OfflinePartitions, "")
    val resolved =
      event(AlertRule.DiskUsage, "broker-1:/var").resolvedBy(at, AlertResolutionKind.Cleared, None)

    for {
      (store, alerts) <- rig
      _ <- store.seed(cluster, List(open, resolved))
      feed <- alerts.feed(caller, cluster, 50, markRead = false)
    } yield {
      assertEquals(feed.map(_.total), Right(2), clue = "both rows are drawn; the design's card shows both")
      assertEquals(feed.map(_.openCount), Right(1))
      assertEquals(feed.map(_.openByRule), Right(Map(AlertRule.OfflinePartitions -> 1)))
    }
  }

  test("a page smaller than the feed does not move the counts") {
    // An alert count recomputed from a page is not the open count, which is wave 6's own rule. The three
    // events here are three literals rather than `limit + 1`, so the page size cannot be widened without
    // this case noticing.
    val events = List(
      event(AlertRule.OfflinePartitions, "", at),
      event(AlertRule.UnderReplicatedPartitions, "", at.plusSeconds(1)),
      event(AlertRule.DiskUsage, "broker-1:/var", at.plusSeconds(2))
    )

    for {
      (store, alerts) <- rig
      _ <- store.seed(cluster, events)
      feed <- alerts.feed(caller, cluster, 1, markRead = false)
    } yield {
      assertEquals(feed.map(_.events.size), Right(1))
      assertEquals(feed.map(_.total), Right(3))
      assertEquals(feed.map(_.openCount), Right(3))
    }
  }

  test("markRead moves the marker after the unread count is taken, so the caller still learns it") {
    for {
      (store, alerts) <- rig
      _ <- store.seed(cluster, List(event(AlertRule.OfflinePartitions, "")))
      first <- alerts.feed(caller, cluster, 50, markRead = true)
      second <- alerts.feed(caller, cluster, 50, markRead = false)
    } yield {
      assertEquals(
        first.map(_.unreadCount),
        Right(1),
        clue = "the read that clears the bell still reports it"
      )
      assertEquals(second.map(_.unreadCount), Right(0))
    }
  }

  test("a feed read without markRead leaves the bell exactly as it was") {
    // The card polls this endpoint. A poll that cleared somebody's unread mark would mean the bell never
    // lit up on any screen that also draws the card, which is every screen.
    for {
      (store, alerts) <- rig
      _ <- store.seed(cluster, List(event(AlertRule.OfflinePartitions, "")))
      _ <- alerts.feed(caller, cluster, 50, markRead = false)
      again <- alerts.feed(caller, cluster, 50, markRead = false)
    } yield assertEquals(again.map(_.unreadCount), Right(1))
  }

  test("a cluster KUI has never heard of is a 404 and not an empty feed") {
    for {
      (_, alerts) <- rig
      feed <- alerts.feed(caller, ClusterId.unsafe("nowhere"), 50, markRead = false)
    } yield assertEquals(feed.left.map(_.code), Left(ErrorCode.ClusterNotFound))
  }

  test("the guard writes no audit record for a read") {
    // Reading a feed is not a mutation and must not appear in the trail: an audit log that recorded every
    // dashboard poll is one nobody can search for the acknowledgement they are looking for.
    for {
      store <- FakeStore.create
      audit <- RecordingSink.create
      logger <- FakeStructuredLogger[IO]
      guard = MutationGuard.make[IO](new Profiles, audit, logger)
      alerts = AlertUseCases.make[IO](new Profiles, store, guard)
      _ <- alerts.feed(caller, cluster, 50, markRead = true)
      records <- audit.written.get
    } yield assertEquals(records.map(_.outcome), List.empty[MutationOutcome])
  }
}
