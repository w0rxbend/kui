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

  test("staleIsAvailableAndNotReachable") {
    // The decision most likely to be argued with: an unreachable *managed* cluster is a section of
    // a healthy response, not a broken capability.
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Stale(at, "no route", at), StoreHealth.Online),
      (CapabilityState.Available, false)
    )
  }

  test("unavailableIsAvailableAndNotReachable") {
    // Same decision, for the cluster that was never reached at all.
    assertEquals(
      CapabilityReportUseCase.stateOf(SnapshotFreshness.Unavailable("no route", at), StoreHealth.Online),
      (CapabilityState.Available, false)
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
        // And still `Available`: the cluster service is fine, and its pages render with a stale
        // section.
        assertEquals(after.clusters(prod.id).state, CapabilityState.Available)
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

  property("noManagedClusterConditionCanProduceAnythingBelowAvailable") {
    val freshness: Gen[SnapshotFreshness] = Gen.oneOf(
      Gen.const(SnapshotFreshness.Loading),
      Gen.const(SnapshotFreshness.Fresh(at)),
      Gen.const(SnapshotFreshness.Stale(at, "no route", at)),
      Gen.const(SnapshotFreshness.Unavailable("no route", at))
    )

    val healthy: Gen[StoreHealth] =
      Gen.oneOf(StoreHealth.Online, StoreHealth.NotConfigured)

    Prop.forAll(freshness, healthy) { (state, store) =>
      val (reported, _) = CapabilityReportUseCase.stateOf(state, store)

      // A future edit to `stateOf` that "helpfully" reports an unreachable cluster as degraded
      // fails here, with a name that explains why it is wrong.
      if state == SnapshotFreshness.Loading then reported != CapabilityState.Available
      else reported == CapabilityState.Available
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
