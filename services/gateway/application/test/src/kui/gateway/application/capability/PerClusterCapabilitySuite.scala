package kui.gateway.application.capability

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.testkit.TestControl
import munit.CatsEffectSuite

import kui.contracts.capability.{CapabilityKey, CapabilityState, ClusterCapability}
import kui.gateway.application.client.{ServiceClients, ServiceHealth, StubServiceClient}
import kui.kernel.{ClusterId, ServiceId}
import kui.testkit.fakes.FakeStructuredLogger

/** That a cluster's state belongs to that cluster, and to nothing else.
  *
  * The first case in this suite is a regression test for a real bug in the M0 code: the poller folded every
  * cluster a service reported into one verdict and took the worst, so a single unreachable Kafka cluster
  * dimmed the whole cluster feature for every other cluster's users. That is precisely the failure the
  * milestone's decision D4 forbids and the reason ADR-039 §6 says "transport failures *of the upstream
  * service*". The fix is to key the inputs on `(service, cluster)`; these cases are what keeps it fixed.
  */
final class PerClusterCapabilitySuite extends CatsEffectSuite {

  private val interval = 10.seconds
  private val cluster = ServiceId.unsafe("cluster")

  private def clusterId(id: String): ClusterId = ClusterId.unsafe(id)

  private def key(id: String): CapabilityKey = CapabilityKey(cluster, Some(clusterId(id)))
  private val serviceKey: CapabilityKey = CapabilityKey(cluster, None)

  private def capability(
      status: String,
      configured: Boolean = true,
      name: Option[String] = None,
      reason: Option[String] = None
  ): ClusterCapability =
    ClusterCapability(
      configured = configured,
      features = List("CLUSTER_TOPOLOGY"),
      status = status,
      name = name,
      reason = reason
    )

  private def fixture(
      stub: StubServiceClient[IO]
  ): Resource[IO, (CapabilityRegistry[IO], CapabilitySignals[IO])] =
    for {
      logger <- Resource.eval(FakeStructuredLogger[IO])
      registry <- CapabilityRegistry.resource[IO](
        RegistryConfig.Default.copy(debounce = 1.millisecond),
        kui.observability.Telemetry.noop[IO],
        logger
      )
      signals <- Resource.eval(
        CapabilitySignals.make[IO](RegistryConfig.Default, registry, List(cluster))
      )
      _ <- ReadinessPoller.resource[IO](
        ServiceClients.of(List(stub: kui.gateway.application.client.ServiceClient[IO])),
        signals,
        interval,
        logger
      )
    } yield (registry, signals)

  /** Reads until the answer satisfies `holds`, or gives up after five (virtual) seconds.
    *
    * Returns the last value read either way, so a test that gives up still fails on its own
    * assertion with the state it actually saw, rather than on a timeout that says nothing.
    */
  private def eventually[A](read: IO[A])(holds: A => Boolean): IO[A] = {
    def attempt(remaining: Int): IO[A] =
      read.flatMap(value =>
        if holds(value) || remaining <= 0 then IO.pure(value)
        else IO.sleep(1.millisecond) *> attempt(remaining - 1)
      )

    attempt(5000)
  }

  private def afterOnePoll[A](
      clusters: Map[ClusterId, ClusterCapability],
      health: ServiceHealth = ServiceHealth.Healthy
  )(body: (CapabilityRegistry[IO], CapabilitySignals[IO], StubServiceClient[IO]) => IO[A]): IO[A] =
    TestControl.executeEmbed(
      StubServiceClient[IO](cluster, health, clusters).flatMap(stub =>
        fixture(stub).use((registry, signals) =>
          IO.sleep(interval + 1.second) *> body(registry, signals, stub)
        )
      )
    )

  test("anUnreachableClusterDoesNotChangeTheServiceCapability") {
    // The D4 regression test. Three clusters, one of them unreachable: the service is available and only
    // the dead cluster's key says otherwise.
    val reported = Map(
      clusterId("prod-eu") -> capability("available"),
      clusterId("staging") -> capability("available"),
      clusterId("dead") -> capability("unavailable")
    )

    afterOnePoll(reported) { (registry, _, _) =>
      for {
        service <- registry.state(serviceKey)
        prod <- registry.state(key("prod-eu"))
        dead <- registry.state(key("dead"))
      } yield {
        assertEquals(service, CapabilityState.Available)
        assertEquals(prod, CapabilityState.Available)
        assert(
          dead match {
            case CapabilityState.Available => false
            case _ => true
          },
          s"the dead cluster's own key should not be available: $dead"
        )
      }
    }
  }

  test("aDeadServiceMakesEveryKeyUnavailable") {
    // The other direction. A service that cannot answer cannot vouch for any of its clusters, so a sidebar
    // showing three healthy clusters belonging to a service that is not there would be a lie.
    val reported = Map(clusterId("prod-eu") -> capability("available"))

    TestControl.executeEmbed(
      StubServiceClient[IO](cluster, ServiceHealth.Healthy, reported).flatMap { stub =>
        fixture(stub).use { (registry, _) =>
          for {
            _ <- IO.sleep(interval + 1.second)
            _ <- stub.health.set(ServiceHealth.Down)
            _ <- IO.sleep(interval + 1.second)
            service <- registry.state(serviceKey)
            prod <- registry.state(key("prod-eu"))
          } yield {
            assert(
              service match {
                case CapabilityState.Unavailable(_, _, _) => true
                case _ => false
              },
              s"a service that cannot be reached is unavailable, but it is $service"
            )
            assert(
              prod match {
                case CapabilityState.Available => false
                case _ => true
              },
              s"a cluster of an unreachable service cannot be available, but it is $prod"
            )
          }
        }
      }
    )
  }

  test("aClusterRemovedFromTheReportIsRetiredAsNotConfigured") {
    // Not deleted and not left as it was: a cluster an operator removed must stop looking usable, and
    // ADR-032 says "not configured" is not a failure and must not be styled as one.
    afterOnePoll(Map(clusterId("prod-eu") -> capability("available"))) { (registry, signals, _) =>
        for {
          // A cluster the gateway once heard about, still in the signals, absent from this poll.
          _ <- signals.update(key("gone"))(
            _.copy(readiness = Some(ReadinessSignal.Ready), serviceReport = Some(capability("available")))
          )
          before <- registry.state(key("gone"))
          _ <- signals.keysOf(cluster)
          _ <- IO.sleep(interval + 1.second)
          after <- registry.state(key("gone"))
        } yield {
          assertEquals(before, CapabilityState.Available)
          assertEquals(after, CapabilityState.NotConfigured)
        }
      }
  }

  test("aNewClusterAppearsOnTheNextPollWithNoRestart") {
    // No allow-list and no configured cluster list in the gateway: a cluster registered at runtime through
    // the metadata store has to be visible on the next poll.
    TestControl.executeEmbed(
      StubServiceClient[IO](
        cluster,
        ServiceHealth.Healthy,
        Map(clusterId("prod-eu") -> capability("available"))
      ).flatMap { stub =>
        fixture(stub).use { (registry, signals) =>
          for {
            _ <- IO.sleep(interval + 1.second)
            before <- registry.snapshot.map(_.keySet.flatMap(_.cluster.map(_.value)))
            // The gateway never learns about a cluster from its own configuration, so a new one arriving
            // in a report is the only way it can appear - and it must.
            _ <- signals.update(key("new-one"))(
              _.copy(readiness = Some(ReadinessSignal.Ready), serviceReport = Some(capability("available")))
            )
            after <- registry.snapshot.map(_.keySet.flatMap(_.cluster.map(_.value)))
          } yield {
            assertEquals(before, Set("prod-eu"))
            assertEquals(after, Set("prod-eu", "new-one"))
          }
        }
      }
    )
  }

  test("twoClustersFlapIndependently") {
    // One cluster's transition must leave the other's state and its sticky `since` untouched: they are
    // different rows on a screen and different entries in a registry.
    val reported = Map(
      clusterId("a") -> capability("available"),
      clusterId("b") -> capability("available")
    )

    afterOnePoll(reported) { (registry, signals, _) =>
      for {
        before <- registry.state(key("b"))
        _ <- signals.update(key("a"))(_.copy(serviceReport = Some(capability("unavailable"))))
        // Polled rather than slept for. The registry applies a debounced write on a fiber of its own,
        // so "has cluster a moved yet" is a condition and not a duration; a fixed one-second sleep
        // failed about one run in ten. The window stays well inside the ten-second poll interval,
        // because the next poll would overwrite what was just written and the test would then be
        // asserting on the poller rather than on the update.
        a <- eventually(registry.state(key("a")))(_ != CapabilityState.Available)
        b <- registry.state(key("b"))
      } yield {
        assertEquals(before, CapabilityState.Available)
        assertEquals(b, before, "cluster b must not move because cluster a did")
        assert(
          a match {
            case CapabilityState.Available => false
            case _ => true
          },
          s"cluster a should have moved: $a"
        )
      }
    }
  }

  test("theCapabilityStreamCarriesPerClusterChanges") {
    // The sidebar and the switcher are driven by this stream, so a per-cluster change that never reaches
    // it is a screen that never updates.
    TestControl.executeEmbed(
      StubServiceClient[IO](cluster, ServiceHealth.Healthy, Map.empty).flatMap { stub =>
        fixture(stub).use { (registry, signals) =>
          // Filtered on the key this test is about rather than taking whichever event arrives first.
          // The registry also publishes service-level entries — whose `key.cluster` is `None` — and
          // whether one of those lands before the per-cluster change is a scheduling detail, not a
          // property of the product. Taking the first event made this suite fail about one run in
          // twenty on a `None` it was never asking about.
          registry.changes
            .filter(_.entry.key.cluster.exists(_.value == "prod-eu"))
            .take(1)
            .compile
            .toList
            .background
            .use { collected =>
            for {
              _ <- IO.sleep(1.second)
              _ <- signals.update(key("prod-eu"))(
                _.copy(readiness = Some(ReadinessSignal.Ready), serviceReport = Some(capability("available")))
              )
              _ <- IO.sleep(1.second)
              events <- collected.flatMap(_.embedNever)
            } yield assertEquals(events.map(_.entry.key.cluster.map(_.value)), List(Some("prod-eu")))
          }
        }
      }
    )
  }

  test("aClustersNameAndReasonReachTheEntryTheBrowserReads") {
    // The switcher renders its rows from these entries and from nothing else — the shell holds no
    // cluster data on purpose — so a name that stops at the poller is a switcher showing slugs, and a
    // reason that stops here is a dimmed row that cannot say why.
    val reported = Map(
      clusterId("prod-eu") -> capability("available", name = Some("Production EU (primary)")),
      clusterId("dead") ->
        capability("degraded", name = Some("Decommissioned"), reason = Some("the cluster could not be reached"))
    )

    afterOnePoll(reported) { (registry, _, _) =>
      registry.entries.map { entries =>
        val byCluster = entries.flatMap(entry => entry.key.cluster.map(_.value -> entry)).toMap

        assertEquals(byCluster("prod-eu").name, Some("Production EU (primary)"))
        assertEquals(byCluster("dead").name, Some("Decommissioned"))
        byCluster("dead").state match {
          case CapabilityState.Degraded(reason) =>
            assertEquals(reason.message, "the cluster could not be reached")
          case other => fail(s"a cluster reported degraded must be degraded, got $other")
        }
        // The service's own entry names no cluster, because it is not about one.
        assertEquals(entries.find(_.key.cluster.isEmpty).flatMap(_.name), None)
      }
    }
  }

  test("aServiceWithNoClustersHasOnlyItsOwnKey") {
    // A KUI nobody has configured a cluster in genuinely has no cluster-scoped capability to report, and
    // that is correct rather than a placeholder.
    afterOnePoll(Map.empty) { (registry, _, _) =>
      registry.snapshot.map(snapshot => assertEquals(snapshot.keySet, Set(serviceKey)))
    }
  }
}
