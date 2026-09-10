package kui.alerts.infrastructure

import java.time.Instant

import scala.concurrent.duration.DurationInt

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite

import kui.alerts.application.EvaluateAlerts
import kui.alerts.domain.*
import kui.cache.CacheMetrics
import kui.kernel.{ClusterId, UserName}
import kui.security.{Principal, PrincipalKind}

/** The two bounds, the read markers and the change stream. */
final class InMemoryAlertStoreSuite extends CatsEffectSuite {

  private val cluster = ClusterId.unsafe("prod-eu")
  private val at = Instant.parse("2026-09-07T12:00:00Z")
  private val retention = 7.days

  private val ada = Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session)
  private val grace = Principal(UserName.unsafe("grace"), Set.empty, PrincipalKind.Session)

  private def store = InMemoryAlertStore.resource[IO](retention, CacheMetrics.noop[IO])

  private def event(subject: String, openedAt: Instant): AlertEvent =
    AlertEvent.open(
      AlertKey(AlertRule.DiskUsage, subject),
      AlertSeverity.Warning,
      openedAt,
      s"$subject is full",
      ""
    )

  private def opening(events: List[AlertEvent]): Evaluation =
    Evaluation(events, Nil, Nil, AlertRuleState.empty, AlertRule.All.map(RuleReport(_, RuleOutcome.evaluated)))

  test("an event older than the retention window is dropped on the next pass") {
    val stale = event("broker-1:/old", at.minusMillis(retention.toMillis + 1000L))
    val fresh = event("broker-1:/new", at)

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(stale, fresh)), at)
        feed <- held.feed(cluster, ada, 100, None)
      } yield assertEquals(feed.events.map(_.key.subject), List("broker-1:/new"))
    }
  }

  test("an event inside the window survives, so the boundary is the window and not the pass") {
    val justInside = event("broker-1:/old", at.minusMillis(retention.toMillis - 1000L))

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(justInside)), at)
        feed <- held.feed(cluster, ada, 100, None)
      } yield assertEquals(feed.total, 1)
    }
  }

  test("retention runs from when an event opened, not from when it was last seen firing") {
    // `bounded`'s own scaladoc states the anchor — *"An event is kept for `retention` after it opened,
    // resolved or not"* — and nothing asserted it: swapping `_.openedAt` for `_.lastSeenAt` left the
    // whole alerts tree green. The consequence is not cosmetic. A condition that keeps firing has its
    // `lastSeenAt` moved on every pass, so anchoring there would keep a persistently-firing event for
    // ever and the bound would stop being a bound. It is also the wrong question: an incident review
    // reads a week of events, and `retention` is what the operator configured that week to mean.
    val old = at.minusMillis(retention.toMillis + 1000L)
    val stale = event("broker-1:/persistent", old)

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(stale)), old)
        // Still firing, so this pass moves `lastSeenAt` to now and leaves `openedAt` where it was.
        _ <- held.record(cluster, Evaluation(Nil, List(stale.id), Nil, AlertRuleState.empty, Nil), at)
        feed <- held.feed(cluster, ada, 100, None)
      } yield assertEquals(feed.total, 0, clue = "an event past its retention survived by still firing")
    }
  }

  test("the store keeps at most 500 events per cluster, oldest first out") {
    // 620 and 500 are both literals. Building the input as `MaxEventsPerCluster + 1` is how wave 5's
    // schema packet shipped a bound that could be raised a thousandfold with every suite green, and it is
    // the first thing an adversary looks for.
    val many = (1 to 620).toList.map(index => event(s"broker-1:/disk-$index", at.minusSeconds(620L - index)))

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(many), at)
        feed <- held.feed(cluster, ada, 1000, None)
      } yield {
        assertEquals(feed.total, 500)
        assertEquals(InMemoryAlertStore.MaxEventsPerCluster, 500)
        // Newest kept: the last event seeded is the newest, and the first is the one dropped.
        assert(feed.events.exists(_.key.subject == "broker-1:/disk-620"))
        assert(!feed.events.exists(_.key.subject == "broker-1:/disk-1"))
      }
    }
  }

  test("one pass can see every event the store is allowed to hold") {
    // `EvaluateAlerts` asks for `OpenEventsPage` events so the fold can tell an already-open condition
    // from a new one. If the store could hold more than the pass reads, the pass would re-open the
    // events it could not see, and the feed would grow one duplicate per minute.
    assert(
      InMemoryAlertStore.MaxEventsPerCluster <= EvaluateAlerts.OpenEventsPage,
      clue = s"${InMemoryAlertStore.MaxEventsPerCluster} > ${EvaluateAlerts.OpenEventsPage}"
    )
  }

  test("a resolved event is closed rather than removed, so the design's card can draw it") {
    val open = event("broker-1:/var", at)

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(open)), at)
        _ <- held.record(
          cluster,
          Evaluation(Nil, Nil, List(open.id), AlertRuleState.empty, Nil),
          at.plusSeconds(60)
        )
        feed <- held.feed(cluster, ada, 100, None)
      } yield {
        assertEquals(feed.total, 1)
        assertEquals(feed.openCount, 0)
        assertEquals(feed.events.head.resolution.map(_.kind), Some(AlertResolutionKind.Cleared))
      }
    }
  }

  test("a still-firing event has its lastSeenAt moved and its openedAt left alone") {
    val open = event("broker-1:/var", at)

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(open)), at)
        _ <- held.record(
          cluster,
          Evaluation(Nil, List(open.id), Nil, AlertRuleState.empty, Nil),
          at.plusSeconds(300)
        )
        feed <- held.feed(cluster, ada, 100, None)
      } yield {
        assertEquals(feed.events.head.openedAt, at)
        assertEquals(feed.events.head.lastSeenAt, at.plusSeconds(300))
      }
    }
  }

  test("the open count is the whole store's and not the page's, against the shipped store") {
    // Three open events and a page of one, asserted against `InMemoryAlertStore` rather than against a
    // fixture that re-implements it. `AlertsRig.FakeStore` and `AlertsTestServer.CountingStore` each
    // hand-write this method, so a case that reads one of them proves the fixture counts correctly and
    // says nothing about the store the process runs.
    //
    // The `limit = 0` read is the one that matters. `AlertUseCases.acknowledge` and `AlertsRoutes.changes`
    // both ask for zero rows and read `openCount` off the answer, so a count taken over the page would
    // make every acknowledgement response and every SSE frame carry `openCount = 0` — the pill and the
    // bell going dark on a cluster with three open events, with no gate in this repository red.
    val open = List(
      event("broker-1:/var", at),
      event("broker-2:/var", at.plusSeconds(1)),
      event("broker-3:/var", at.plusSeconds(2))
    )

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(open), at.plusSeconds(2))
        page <- held.feed(cluster, ada, 1, None)
        none <- held.feed(cluster, ada, 0, None)
      } yield {
        assertEquals(page.events.size, 1)
        assertEquals(page.total, 3)
        assertEquals(page.openCount, 3)
        assertEquals(page.openByRule, Map(AlertRule.DiskUsage -> 3))

        assertEquals(none.events, Nil)
        assertEquals(none.openCount, 3, clue = "the read the stream and the acknowledgement make")
        assertEquals(none.openByRule, Map(AlertRule.DiskUsage -> 3))
      }
    }
  }

  test("markRead moves the marker after the unread count is taken, against the shipped store") {
    // The read that clears the bell still reports what it cleared. Moving the marker write ahead of the
    // count in `feed` answers `unreadCount = 0` for ever with every other case green, because the case
    // beside this one reads the count *back* on a second request and zero is what it expects there.
    val opened = List(event("broker-1:/var", at), event("broker-2:/var", at.plusSeconds(1)))

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(opened), at.plusSeconds(1))
        first <- held.feed(cluster, ada, 100, Some(at.plusSeconds(60)))
        second <- held.feed(cluster, ada, 100, None)
      } yield {
        assertEquals(first.unreadCount, 2, clue = "the read that clears the bell still reports it")
        assertEquals(first.lastReadAt, None, clue = "and it reports the marker it replaced, not the new one")
        assertEquals(second.unreadCount, 0)
        assertEquals(second.lastReadAt, Some(at.plusSeconds(60)))
      }
    }
  }

  test("an acknowledgement publishes a frame, so the bell moves without a reload") {
    // The service's one write, and the only thing an SSE subscriber can be woken by that is not a rule
    // pass. `record`'s publication has a case above; dropping `acknowledge`'s left every case green,
    // which means acknowledging on one tab would leave every other tab's bell showing the old count
    // until its next poll.
    //
    // Deterministic in the same way as the case above: the subscription is confirmed live by recording
    // until a frame arrives, and only then is the acknowledgement made. The wait is bounded so that a
    // store which publishes nothing fails on the count rather than on the suite's timeout.
    val open = event("broker-1:/var", at)

    store.use { held =>
      for {
        seen <- Ref.of[IO, List[ClusterId]](Nil)
        watching <- held.changes.evalMap(id => seen.update(_ :+ id)).compile.drain.start
        _ <- (held.record(cluster, opening(List(open)), at) >> IO.sleep(20.millis))
          .untilM_(seen.get.map(_.nonEmpty))
        before <- seen.get.map(_.size)
        closed <- held.acknowledge(cluster, open.id, at.plusSeconds(30), "ada")
        _ <- IO.sleep(20.millis).untilM_(seen.get.map(_.size > before)).timeoutTo(2.seconds, IO.unit)
        after <- seen.get
        _ <- watching.cancel
      } yield {
        assertEquals(closed.map(_.isOpen), Right(false))
        assertEquals(after.size, before + 1, clue = "the acknowledgement woke no subscriber")
        assertEquals(after.distinct, List(cluster))
      }
    }
  }

  test("a read marker is one principal's and does not clear anybody else's bell") {
    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(event("broker-1:/var", at))), at)
        _ <- held.feed(cluster, ada, 100, Some(at.plusSeconds(60)))
        hers <- held.feed(cluster, ada, 100, None)
        his <- held.feed(cluster, grace, 100, None)
      } yield {
        assertEquals(hers.unreadCount, 0)
        assertEquals(his.unreadCount, 1)
      }
    }
  }

  test("the rule state survives a pass, so a rebalance clock is not restarted every minute") {
    val remembered = AlertRuleState(Map(kui.kernel.GroupId.unsafe("payments") -> at))

    store.use { held =>
      for {
        _ <- held.record(cluster, Evaluation(Nil, Nil, Nil, remembered, Nil), at)
        state <- held.ruleState(cluster)
      } yield assertEquals(state, remembered)
    }
  }

  test("the last pass's rule reports are what a later read reports") {
    val refused = RuleReport(
      AlertRule.DiskUsage,
      RuleOutcome.NotEvaluated(kui.kernel.error.InfrastructureError.Unreachable("kafka-admin", "no"))
    )

    store.use { held =>
      for {
        _ <- held.record(cluster, Evaluation(Nil, Nil, Nil, AlertRuleState.empty, List(refused)), at)
        feed <- held.feed(cluster, ada, 100, None)
      } yield assertEquals(feed.reports, List(refused))
    }
  }

  test("a cluster that has never been evaluated has no evaluatedAt, so its zeros are readable") {
    store.use { held =>
      held.feed(cluster, ada, 100, None).map { feed =>
        assertEquals(feed.evaluatedAt, None)
        assertEquals(feed.openCount, 0)
      }
    }
  }

  test("acknowledging an event that is not there and one that is already closed are both 409s") {
    val open = event("broker-1:/var", at)

    store.use { held =>
      for {
        _ <- held.record(cluster, opening(List(open)), at)
        missing <- held.acknowledge(cluster, event("broker-1:/nowhere", at).id, at, "ada")
        first <- held.acknowledge(cluster, open.id, at, "ada")
        again <- held.acknowledge(cluster, open.id, at, "ada")
      } yield {
        assertEquals(missing.left.map(_.code), Left(kui.kernel.error.ErrorCode.InvalidState))
        assertEquals(first.map(_.isOpen), Right(false))
        assertEquals(again.left.map(_.code), Left(kui.kernel.error.ErrorCode.InvalidState))
      }
    }
  }

  test("a pass that changed nothing publishes no frame, so an idle cluster does not wake every bell") {
    // Deterministic rather than timed. The subscription is confirmed live by publishing until a frame
    // arrives; then a no-change pass and a change are published in that order, and the Topic delivers in
    // order — so if the no-change pass had published, the count would have risen by two by the time the
    // change's frame arrived. A sleep-and-look would have given a false pass on a loaded machine, which is
    // what this case did on its first run under `./scripts/run-tests.sh`.
    store.use { held =>
      for {
        seen <- Ref.of[IO, List[ClusterId]](Nil)
        watching <- held.changes.evalMap(id => seen.update(_ :+ id)).compile.drain.start
        _ <- (held.record(cluster, opening(List(event("broker-1:/probe", at))), at) >> IO.sleep(20.millis))
          .untilM_(seen.get.map(_.nonEmpty))
        before <- seen.get.map(_.size)
        _ <- held.record(cluster, Evaluation(Nil, Nil, Nil, AlertRuleState.empty, Nil), at)
        _ <- held.record(cluster, opening(List(event("broker-1:/var", at))), at)
        _ <- IO.sleep(20.millis).untilM_(seen.get.map(_.size > before))
        after <- seen.get
        _ <- watching.cancel
      } yield {
        assertEquals(after.size, before + 1)
        assertEquals(after.distinct, List(cluster))
      }
    }
  }
}
