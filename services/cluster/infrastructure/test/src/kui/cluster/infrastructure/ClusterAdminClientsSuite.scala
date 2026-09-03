package kui.cluster.infrastructure

import cats.effect.IO
import cats.syntax.all.*

import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The registry that keeps each cluster's admin client in step with the profile it was built from.
  *
  * The client lifecycle itself is `libs/kafka`'s `AdminClientPool` and is tested there. What is asserted
  * here is the one thing the pool cannot know: that a profile whose version moved is a different connection
  * wearing the same cluster id, and that its client is thrown away before anything talks through it again.
  */
final class ClusterAdminClientsSuite extends KuiIOSuite {

  private def registry[A](use: (ClusterAdminClients[IO], RecordingAdminPool) => IO[A]): IO[A] =
    for {
      pool <- RecordingAdminPool()
      logger <- FakeStructuredLogger[IO]
      result <- ClusterAdminClients.resource[IO](pool, logger).use(clients => use(clients, pool))
    } yield result

  test("theFirstCallRegistersTheClusterAndEvictsNothing") {
    registry { (clients, pool) =>
      for {
        connection <- clients.connectionFor(TestProfiles.profile())
        open <- clients.openClients
        events <- pool.events.get
      } yield {
        assertEquals(connection.id.value, "local")
        assertEquals(open, 1)
        assertEquals(events, Nil)
      }
    }
  }

  test("tenConcurrentCallsForOneClusterEvictNothing") {
    // The pool's own per-cluster gate makes ten concurrent first calls create one client. What must not
    // happen here is ten *evictions*: an eviction storm on a cluster that is merely busy would rebuild the
    // client under every one of those calls.
    registry { (clients, pool) =>
      for {
        _ <- List.fill(10)(TestProfiles.profile()).parTraverse(clients.connectionFor)
        open <- clients.openClients
        events <- pool.events.get
      } yield {
        assertEquals(open, 1)
        assertEquals(events, Nil)
      }
    }
  }

  test("aNewerProfileVersionEvictsTheClient") {
    // The whole reason this component exists. Without it, a cluster whose bootstrap list or credentials were
    // edited in the metadata store would keep being served by the client built from the old ones.
    registry { (clients, pool) =>
      for {
        _ <- clients.connectionFor(TestProfiles.profile(version = 1L))
        _ <- clients.connectionFor(TestProfiles.profile(version = 2L, bootstrap = "broker-9:9092"))
        events <- pool.events.get
        open <- clients.openClients
      } yield {
        assertEquals(events, List("evict:local"))
        assertEquals(open, 1)
      }
    }
  }

  test("anOlderOrEqualProfileVersionDoesNotEvict") {
    // A replica that replays the log sees records it has already applied. Evicting on each of them would
    // reconnect every cluster on every store reconnect.
    registry { (clients, pool) =>
      for {
        _ <- clients.connectionFor(TestProfiles.profile(version = 5L))
        _ <- clients.connectionFor(TestProfiles.profile(version = 5L))
        _ <- clients.connectionFor(TestProfiles.profile(version = 3L))
        events <- pool.events.get
      } yield assertEquals(events, Nil)
    }
  }

  test("everyClusterIsTrackedSeparately") {
    registry { (clients, pool) =>
      for {
        _ <- clients.connectionFor(TestProfiles.profile(id = "prod"))
        _ <- clients.connectionFor(TestProfiles.profile(id = "staging"))
        _ <- clients.connectionFor(TestProfiles.profile(id = "prod", version = 2L))
        open <- clients.openClients
        events <- pool.events.get
      } yield {
        assertEquals(open, 2)
        // Only the cluster whose profile moved is rebuilt. A dead or edited cluster must never cost a
        // healthy one its connection.
        assertEquals(events, List("evict:prod"))
      }
    }
  }

  test("invalidateAsksThePoolToRebuildAndKeepsTheRegistration") {
    registry { (clients, pool) =>
      for {
        _ <- clients.connectionFor(TestProfiles.profile())
        _ <- clients.invalidate(kui.kernel.ClusterId.unsafe("local"))
        events <- pool.events.get
        open <- clients.openClients
      } yield {
        assertEquals(events, List("invalidate:local"))
        // The cluster is still configured; only its socket was thrown away. Forgetting the version here
        // would make the next call look like a profile change and evict a client that was just rebuilt.
        assertEquals(open, 1)
      }
    }
  }

  test("releasingTheResourceEvictsEveryRegisteredCluster") {
    for {
      pool <- RecordingAdminPool()
      logger <- FakeStructuredLogger[IO]
      _ <- ClusterAdminClients
        .resource[IO](pool, logger)
        .use { clients =>
          clients.connectionFor(TestProfiles.profile(id = "prod")) *>
            clients.connectionFor(TestProfiles.profile(id = "staging")).void
        }
      events <- pool.events.get
    } yield assertEquals(events.sorted, List("evict:prod", "evict:staging"))
  }

  test("aCancelledConnectionForLeavesTheRegistryAndThePoolInStep") {
    // The `Ref` update and the eviction are one uncancelable step. If a cancellation could land between
    // them, the registry would believe the client matches the profile while the pool still held the one
    // built from the old credentials — a stale connection nothing would ever evict again.
    registry { (clients, pool) =>
      for {
        _ <- clients.connectionFor(TestProfiles.profile(version = 1L))
        fiber <- clients.connectionFor(TestProfiles.profile(version = 2L)).start
        _ <- fiber.cancel
        events <- pool.events.get
        // Whatever the cancellation did, the two must agree: either nothing moved, or the version moved and
        // the old client is gone.
        open <- clients.openClients
        _ <- clients.connectionFor(TestProfiles.profile(version = 2L))
        after <- pool.events.get
      } yield {
        assertEquals(open, 1)
        assert(events.size <= 1, s"at most one eviction can have happened, got $events")
        assertEquals(after, List("evict:local"), s"exactly one eviction for the move to version 2, got $after")
      }
    }
  }
}
