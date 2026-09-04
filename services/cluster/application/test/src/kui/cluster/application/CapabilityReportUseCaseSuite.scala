package kui.cluster.application

import java.time.Instant

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.testkit.TestControl

import org.scalacheck.{Gen, Prop}

import kui.cluster.domain.*
import kui.kernel.ClusterId
import kui.kernel.error.{InfrastructureError, KuiError}
import kui.testkit.{ClusterGenerators, RedactionAssertions}

/** What this service says about itself, and the one thing it must never say.
  *
  * The invariant, asserted as a property in `noManagedClusterConditionCanProduceAnythingBelowAvailable`: no
  * state of any managed Kafka cluster can move this service's reported capability below `Available`. Only two
  * things can — the metadata store being degraded, and the process not having finished starting. Everything
  * else about a managed cluster is data on a page, and reporting it as a degraded capability would dim the
  * sidebar for every user because one operator typed a bad broker address.
  */
final class CapabilityReportUseCaseSuite extends munit.CatsEffectSuite with munit.ScalaCheckSuite {

  private val prod = ClusterProfileFixtures.plaintext("prod", "Production")
  private val staging = ClusterProfileFixtures.plaintext("staging", "Staging")
  private val secretive = ClusterProfileFixtures.saslScram("secure", "Secure")

  private val at: Instant = Instant.parse("2026-09-04T09:00:00Z")

  /** The failure a cluster that cannot be reached leaves on its snapshot. */
  private val noRoute: KuiError = InfrastructureError.Unreachable("the cluster", "no route")

  private val unreachable: KuiError = InfrastructureError.Unreachable("the cluster", "no route")

  private val degradedStore: StoreHealth = StoreHealth.Degraded("the broker did not answer", at)

  test("freshIsAvailableAndReachable") {
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Fresh(at), StoreHealth.Online),
      (CapabilityState.Available, true)
    )
  }

  test("loadingIsDegradedStartingAndNotReachable") {
    // The browser matches on this exact word to render "starting" rather than an outage, so the
    // string is asserted and not merely its shape.
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Loading, StoreHealth.Online),
      (CapabilityState.Degraded("starting"), false)
    )
  }

  test("staleIsDegradedWithTheFailuresOwnMessageAndNotReachable") {
    // A cluster whose last refresh failed is usable — its last snapshot is still on the screen — and it
    // is not healthy, and its own capability entry has to say so. Saying `Available` here is what made
    // `/api/v1/capabilities` report a dead cluster as available while its dashboard row said otherwise.
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Stale(at, noRoute, at), StoreHealth.Online),
      (CapabilityState.Degraded(noRoute.message), false)
    )
  }

  test("unavailableIsDegradedWithTheFailuresOwnMessageAndNotReachable") {
    // Same decision, for the cluster that was never reached at all. `Degraded` rather than
    // `Unavailable`, because a service answering this request is by definition reachable and a
    // self-reported `Unavailable` would be a service claiming it is not there.
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Unavailable(noRoute, at), StoreHealth.Online),
      (CapabilityState.Degraded(noRoute.message), false)
    )
  }

  test("aDegradedStoreDegradesEveryClusterWhateverTheClustersAreDoing") {
    val (state, reachable) =
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Fresh(at), degradedStore)

    assertEquals(reachable, true)
    assert(
      state match {
        case CapabilityState.Degraded(reason) => reason.startsWith("configuration store:")
        case CapabilityState.Available => false
      },
      s"the reason must name the store, got $state"
    )
  }

  test("notConfiguredStoreDoesNotDegradeAnything") {
    // The file adapter is a supported way to run. It is not a degradation.
    ClusterRig
      .resource(List(prod), storeHealth = StoreHealth.NotConfigured)
      .evalTap(ClusterRig.settled)
      .use { rig =>
        rig.capabilities.report.map { report =>
          assertEquals(report.storeHealth, StoreHealth.NotConfigured)
          assertEquals(report.clusters(prod.id).state, CapabilityState.Available)
        }
      }
  }

  test("aDegradedStoreIsReportedOnEveryEntryAndOnce") {
    ClusterRig.resource(List(prod, staging)).evalTap(ClusterRig.settled).use { rig =>
      for {
        _ <- rig.store.setHealth(degradedStore)
        _ <- rig.registry.reload
        report <- rig.capabilities.report
      } yield {
        assert(report.storeHealth.isDegraded)
        assert(report.clusters.values.forall(_.state != CapabilityState.Available))
      }
    }
  }

  test("anUnconfiguredClusterIsAbsentFromTheMap") {
    // Absent, not present with `configured = false`. The gateway renders an id it has no entry for
    // as "not configured", which is what "you did not set this up" should look like.
    ClusterRig.resource(List(prod)).evalTap(ClusterRig.settled).use { rig =>
      rig.capabilities.report.map { report =>
        assert(!report.clusters.contains(ClusterId.unsafe("nope")))
      }
    }
  }

  test("everyConfiguredClusterHasAnEntry") {
    ClusterRig.resource(List(prod, staging, secretive)).evalTap(ClusterRig.settled).use { rig =>
      rig.capabilities.report.map { report =>
        assertEquals(report.clusters.keySet, Set(prod.id, staging.id, secretive.id))
        assert(report.clusters.values.forall(_.configured))
      }
    }
  }

  test("featuresAreTheProbedSetAsTokens") {
    val probed = TopologyFixtures.features(Set(ClusterFeature.LogDirs, ClusterFeature.BrokerConfigs))

    ClusterRig.resource(List(prod), features = probed).evalTap(ClusterRig.settled).use { rig =>
      rig.capabilities.report.map { report =>
        assertEquals(report.clusters(prod.id).features, Set("log-dirs", "broker-configs"))
      }
    }
  }

  test("aNeverProbedClusterHasNoFeaturesRatherThanEveryFeatureAbsent") {
    ClusterRig
      .resource(List(prod), features = ClusterFeatures.unprobed(at))
      .evalTap(ClusterRig.settled)
      .use { rig =>
        rig.capabilities.report.map { report =>
          assertEquals(report.clusters(prod.id).features, Set.empty[String])
        }
      }
  }

  test("scrapedAtIsTheLastSuccessAndNotTheLastAttempt") {
    val scenario = ClusterRig.resource(List(prod)).evalTap(ClusterRig.settled).use { rig =>
      for {
        before <- rig.capabilities.report
        _ <- rig.admin.set(_.copy(description = Left(unreachable)))
        _ <- IO.sleep(31.seconds)
        after <- rig.capabilities.report
      } yield {
        assert(before.clusters(prod.id).scrapedAt.isDefined)
        assertEquals(after.clusters(prod.id).scrapedAt, before.clusters(prod.id).scrapedAt)
        assertEquals(after.clusters(prod.id).reachable, false)
        // Degraded and not available: the cluster service itself is fine — that is the *service's* key,
        // which the gateway builds from readiness — but this cluster is not answering, and this entry
        // is the one the sidebar and the switcher read. It carries the failure's own message, so the
        // dot has something to say when a user hovers it.
        assertEquals(
          after.clusters(prod.id).state,
          CapabilityState.Degraded(unreachable.message)
        )
      }
    }

    TestControl.executeEmbed(scenario)
  }

  test("reportDoesNotCallTheAdminPort") {
    // The gateway polls this every ten seconds per replica. An admin call here would be a broker
    // call per cluster per ten seconds per gateway replica.
    ClusterRig.resource(List(prod, staging)).evalTap(ClusterRig.settled).use { rig =>
      for {
        _ <- rig.admin.reset
        _ <- rig.capabilities.report
        calls <- rig.admin.calls
      } yield assertEquals(calls, Nil)
    }
  }

  property("onlyAFreshScrapeIsReportedAvailable") {
    // The per-cluster entry says what KUI can currently do with *this* cluster, and the only condition
    // under which the answer is "everything" is a scrape that succeeded. Anything else is degraded.
    //
    // This property replaces one that asserted the opposite — that no managed cluster's condition could
    // move the entry below `Available`. That rule exists to stop one dead cluster dimming the cluster
    // feature for everybody (DEVPLAN D4, ADR-039 §6), and it is now enforced where it belongs, on the
    // *service's* key: `ReadinessPoller.summarise` builds that key from readiness and the circuit alone,
    // and `PerClusterCapabilitySuite.anUnreachableClusterDoesNotChangeTheServiceCapability` keeps it so.
    // Enforcing it on the per-cluster key as well was what made a dead cluster read as available in the
    // sidebar and the switcher.
    val freshness: Gen[SnapshotFreshness] = Gen.oneOf(
      Gen.const(SnapshotFreshness.Loading),
      Gen.const(SnapshotFreshness.Fresh(at)),
      Gen.const(SnapshotFreshness.Stale(at, noRoute, at)),
      Gen.const(SnapshotFreshness.Unavailable(noRoute, at))
    )

    val healthy: Gen[StoreHealth] =
      Gen.oneOf(StoreHealth.Online, StoreHealth.NotConfigured)

    Prop.forAll(freshness, healthy) { (state, store) =>
      val (reported, reachable) = CapabilityReportUseCase.stateOf(state, store)

      val isFresh = state match {
        case SnapshotFreshness.Fresh(_) => true
        case _ => false
      }

      (reported == CapabilityState.Available) == isFresh && reachable == isFresh
    }
  }

  test("anUnreachableClustersEntryIsDegradedAndCarriesItsName") {
    // End to end through the use case rather than through `stateOf`: the entry a gateway actually reads.
    ClusterRig
      .resource(List(prod), setup = _.set(_.copy(description = Left(noRoute))))
      .evalTap(ClusterRig.settled)
      .use { rig =>
        rig.capabilities.report.map { report =>
          val entry = report.clusters(prod.id)

          assert(
            entry.state != CapabilityState.Available,
            s"a cluster KUI cannot reach must not report itself available: ${entry.state}"
          )
          assertEquals(entry.reachable, false)
          // The name an operator wrote, so the switcher has something to show but the slug.
          assertEquals(entry.name, Some(prod.label))
        }
      }
  }

  test("reportCarriesNoSecretAndNoBootstrapString") {
    // The capability response is public to every authenticated user and is the least-reviewed
    // response body in the product.
    ClusterRig.resource(List(secretive)).evalTap(ClusterRig.settled).use { rig =>
      rig.capabilities.report.map { report =>
        val rendered = report.toString

        RedactionAssertions.assertNoLeak(rendered, ClusterProfileFixtures.Canary)
        RedactionAssertions.assertNoLeak(rendered, secretive.bootstrap.value)

        ClusterGenerators.secretsOfSecurity(secretive.security).filter(_.nonEmpty).foreach { secret =>
          RedactionAssertions.assertNoLeak(rendered, secret)
        }
      }
    }
  }
}
