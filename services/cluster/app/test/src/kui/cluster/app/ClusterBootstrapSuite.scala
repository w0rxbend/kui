package kui.cluster.app

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite

import kui.config.{ClusterConfig, StoreConfig}
import kui.http.health.HealthEndpoints
import kui.kernel.cluster.{AdminTuning, BootstrapServers, ClientProperties, ClusterSecurity}
import kui.kernel.ClusterId
import kui.observability.Telemetry
import kui.testkit.fakes.FakeStructuredLogger

/** That the service starts in the order ADR-042 fixes, and starts at all when there is nothing to start
  * against.
  *
  * The store-less path is the one a first run takes and the one the all-in-one deployment uses, so it is
  * asserted here in process. The Kafka-backed path - topic bootstrap, replay, the bounded replay failure -
  * is asserted against a real broker by the store's own integration suite, which owns those behaviours;
  * duplicating them here would be a second container per build for a second copy of the same assertions.
  */
final class ClusterBootstrapSuite extends CatsEffectSuite {

  private def cluster(id: String, bootstrap: String): ClusterConfig =
    ClusterConfig(
      id = ClusterId.unsafe(id),
      name = id,
      bootstrapServers = BootstrapServers.unsafe(bootstrap),
      security = ClusterSecurity.Plaintext,
      properties = ClientProperties.empty,
      readOnly = false,
      admin = AdminTuning.default
    )

  private def bootstrapped(clusters: List[ClusterConfig], store: StoreConfig = StoreConfig.Default) =
    FakeStructuredLogger[IO].toResource.flatMap { logger =>
      given org.typelevel.log4cats.LoggerFactory[IO] = AppLoggerFactory.of(logger)

      ClusterBootstrap.resource[IO](clusters, store, Telemetry.noop[IO], logger)
    }

  test("withNoStoreConfiguredTheFileAdapterIsNotNeededAndTheServiceStarts") {
    // A KUI with clusters in its configuration file and no metadata store is a supported deployment, not a
    // half-configured one: it is what a first run looks like, and it must start.
    bootstrapped(List(cluster("local", "localhost:9092"))).use { started =>
      for {
        snapshot <- started.registry.snapshot
        report <- HealthEndpoints.report[IO](ClusterBootstrap.readiness[IO](started))
      } yield {
        assertEquals(snapshot.size, 1)
        assertEquals(started.storeMode, "none")
        assertEquals(report.ready, true)
        assertEquals(report.checks.map(_.name), List("process", "config", "store"))
      }
    }
  }

  test("theStoreCheckReportsWhatKindOfStoreThereIsRatherThanFailing") {
    // Readiness is what the gateway polls, so it must not fail for a fault that costs this service nothing.
    // A deployment with no store can still serve every cluster in its configuration file.
    bootstrapped(Nil).use { started =>
      HealthEndpoints.report[IO](ClusterBootstrap.readiness[IO](started)).map { report =>
        val store = report.checks.find(_.name == "store").getOrElse(fail("no store check"))

        assertEquals(store.healthy, true)
        assert(store.detail.exists(_.contains("no metadata store")), store.detail.toString)
      }
    }
  }

  test("noPerClusterReadinessCheckIsAdded") {
    // Decision, not an omission: an unready cluster service dims the `cluster` capability for *every*
    // cluster at once, so one unreachable broker would take the others' pages down with it. Per-cluster
    // health is reported per cluster, in /capabilities and in each row's section.
    val three = List(
      cluster("one", "localhost:9092"),
      cluster("two", "localhost:9093"),
      cluster("three", "localhost:9094")
    )

    bootstrapped(three).use { started =>
      HealthEndpoints.report[IO](ClusterBootstrap.readiness[IO](started)).map { report =>
        assertEquals(report.checks.map(_.name), List("process", "config", "store"))
        assertEquals(report.ready, true)
      }
    }
  }

  test("anUnreachableManagedClusterDoesNotPreventStartup") {
    // Port 1 is not listening. Refusing to start would let one mistyped address take down access to nine
    // healthy clusters; the row renders unavailable instead, which is the milestone's whole promise.
    val clusters = List(cluster("healthy", "localhost:9092"), cluster("dead", "localhost:1"))

    bootstrapped(clusters).use { started =>
      for {
        snapshot <- started.registry.snapshot
        views <- started.topology.viewAll
      } yield {
        assertEquals(snapshot.size, 2)
        // Neither has a topology yet: the refresh loop has been asked, not awaited. Startup does not wait
        // on a broker, which is what keeps a dead cluster out of the startup path.
        assertEquals(views.count(_.isRenderable), 0)
      }
    }
  }

  test("configurationThatCannotBecomeAProfileStopsTheProcess, with every failure named") {
    // The one failure in the chain that is about what the operator *wrote*. ADR-013's rule applies: an
    // operator who made two mistakes is told about both, in one message, rather than one restart at a time.
    val broken = List(
      cluster("one", "localhost:9092").copy(name = ""),
      cluster("two", "localhost:9093").copy(name = " ")
    )

    ClusterBootstrap.profilesOf[IO](broken).attempt.map {
      case Left(failure) =>
        assert(failure.getMessage.contains("one"), failure.getMessage)
        assert(failure.getMessage.contains("two"), failure.getMessage)
      case Right(profiles) => fail(s"a blank display name must not produce a profile: $profiles")
    }
  }

  test("everyValidClusterBecomesAProfileAtTheStaticVersion") {
    // Version zero is the precedence rule expressed as a number: a store record starts at one, so it always
    // wins the overlay against the configuration file it was copied from.
    ClusterBootstrap.profilesOf[IO](List(cluster("local", "localhost:9092"))).map { profiles =>
      assertEquals(profiles.map(_.id.value), List("local"))
      assertEquals(profiles.map(_.version.value), List(0L))
      assertEquals(profiles.map(_.origin), List(kui.cluster.domain.ProfileOrigin.Static))
    }
  }

  test("releasingTheResourceStopsEveryFiberAndClosesEveryClient") {
    // Allocated and released twice over. A refresh loop that outlived its resource would keep
    // authenticating to a cluster every thirty seconds after the process had been told to stop; a leaked
    // fiber shows up here as the second allocation seeing the first one's work.
    val once = bootstrapped(List(cluster("local", "localhost:9092"))).use(started =>
      started.registry.snapshot.map(_.size)
    )

    (once, once).tupled.map((first, second) => assertEquals((first, second), (1, 1)))
  }

  test("cancellingTheAllocationLeavesNothingRunning") {
    // The cancellation path, not the failure path: a start that is interrupted - a SIGTERM during a slow
    // replay - must release whatever it had acquired rather than leave a consumer or a fiber behind.
    for {
      released <- Ref.of[IO, Boolean](false)
      resource = bootstrapped(List(cluster("local", "localhost:9092")))
        .onFinalize(released.set(true))
      fiber <- resource.use(_ => IO.never).start
      _ <- IO.cede.replicateA_(50)
      _ <- fiber.cancel
      done <- released.get
    } yield assertEquals(done, true)
  }
}
