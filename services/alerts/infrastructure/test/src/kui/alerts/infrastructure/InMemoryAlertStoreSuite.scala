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
