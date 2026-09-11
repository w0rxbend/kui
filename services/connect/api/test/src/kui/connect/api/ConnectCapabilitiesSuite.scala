package kui.connect.api

import cats.effect.IO
import munit.CatsEffectSuite

import kui.connect.application.{ClusterConnectSource, ConnectProfileView}
import kui.connect.contract.ConnectEndpoints
import kui.connect.domain.*
import kui.contracts.capability.{CapabilityState, ClusterCapability, DegradedReason, ReasonCode}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{ClusterId, ConnectName, ConnectorName}
import kui.testkit.fakes.FakeStructuredLogger

/** What the gateway is told this service can do, per cluster.
  *
  * The one rule this file is built around is the packet's owned rule: **a rebalancing Connect cluster is
  * `available`.** The other two halves are `ConnectHttp.errorFrom`, asserted in `ConnectHttpSuite`, and
  * `ConnectMapping.section`, asserted in `ConnectMappingSuite`; all three are assertions against the shipped
  * functions rather than against a re-implementation of them.
  */
final class ConnectCapabilitiesSuite extends CatsEffectSuite {

  private val cluster: ClusterId = ClusterId.unsafe("prod-eu")
  private val readOnly: ClusterId = ClusterId.unsafe("prod-us")
  private val bare: ClusterId = ClusterId.unsafe("staging")

  private val payments: ConnectName = ConnectName.unsafe("payments")
  private val analytics: ConnectName = ConnectName.unsafe("analytics")

  private val rebalancing: KuiError = ApplicationError.Refused(
    ErrorCode.ConnectRebalancing,
    "the Kafka Connect cluster 'payments' is rebalancing and cannot answer yet"
  )

  private val unreachable: KuiError =
    kui.kernel.error.InfrastructureError.Unreachable("kafka-connect.payments", "connection refused")

  /** A worker that answers what it was told to. It counts nothing: what this suite asserts is the *verdict*
    * the probe reaches, and the call itself is `ConnectHttpSuite`'s subject.
    */
  private final class Worker(answer: Either[KuiError, ConnectorFacts]) extends ConnectWorkerPort[IO] {

    def connectors: IO[Either[KuiError, ConnectorFacts]] = IO.pure(answer)

    def operate(connector: ConnectorName, operation: ConnectorOperation): IO[Either[KuiError, Unit]] =
      IO.pure(Right(()))
  }

  private final class Source(
      views: List[ConnectProfileView],
      workers: Map[(ClusterId, ConnectName), ConnectWorkerPort[IO]]
  ) extends ClusterConnectSource[IO] {

    def profileOf(id: ClusterId): IO[Either[KuiError, ConnectProfileView]] =
      IO.pure(views.find(_.cluster == id).toRight(ApplicationError.Conflict("no such cluster")))

    def all: IO[List[ConnectProfileView]] = IO.pure(views)

    def worker(id: ClusterId, connect: ConnectName): IO[Option[ConnectWorkerPort[IO]]] =
      IO.pure(workers.get((id, connect)))
  }

  private def report(
      views: List[ConnectProfileView],
      answers: Map[(ClusterId, ConnectName), Either[KuiError, ConnectorFacts]]
  ): IO[Map[ClusterId, ClusterCapability]] =
    FakeStructuredLogger[IO].flatMap { logger =>
      val workers = answers.map((key, answer) => key -> (new Worker(answer): ConnectWorkerPort[IO]))
      ConnectCapabilities.make[IO](new Source(views, workers), logger).report
    }

  private def profile(id: ClusterId, connects: List[ConnectName], readOnly: Boolean = false) =
    ConnectProfileView(id, s"cluster ${id.value}", readOnly, connects)

  private val healthy: Either[KuiError, ConnectorFacts] = Right(ConnectorFacts.complete(Nil))

  /** The `degraded` discriminator, read off the enum rather than typed out again. */
  private val degradedStatus: String =
    CapabilityState.Degraded(DegradedReason(ReasonCode.Starting, "", None, None)).status

  test("a connector whose worker is rebalancing is reported as rebalancing and does not dim the capability") {
    // **The rule this packet owns.** A rebalance lasts seconds and clears itself; a capability dimmed by
    // one is a red badge in the sidebar that an operator cannot clear and learns to ignore, which is
    // exactly what ADR-039 §6 warns about. The mutation that reverses it — reporting the rebalancing arm
    // as a degraded row, or as `ReasonCode.UpstreamUnavailable` on the wire — reddens this case and
    // `ConnectMappingSuite`'s twin.
    report(
      List(profile(cluster, List(payments))),
      Map((cluster, payments) -> Left(rebalancing))
    ).map { answer =>
      val row = answer(cluster)

      assertEquals(row.status, CapabilityState.Available.status)
      assertNotEquals(row.status, degradedStatus)
      assertEquals(row.reason, None)
      assert(row.configured)
      // And the feature is still offered: an operator who cannot press Restart during a rebalance would
      // be told the feature is gone rather than that the cluster is busy.
      assert(clue(row.features).contains(ConnectEndpoints.RestartOperation))
    }
  }

  test("a worker that is unreachable does dim it, and says which one") {
    report(
      List(profile(cluster, List(payments))),
      Map((cluster, payments) -> Left(unreachable))
    ).map { answer =>
      val row = answer(cluster)

      assertNotEquals(row.status, CapabilityState.Available.status)
      assert(row.configured)
      assert(clue(row.reason.getOrElse("")).contains("payments"))
    }
  }

  test("a degraded row keeps its features, so the browser draws the screen that explains why") {
    // Filed by W7-A3. `degraded` builds its row with `featuresFor(profile.readOnly)` and its scaladoc
    // argues the point at length — "a row that also withdrew its features would make the browser hide
    // the tab at the moment the operator most needs to see why it is empty" — and nothing asserted it.
    // Replacing that argument with `features = Nil` left all 65 cases in `services.connect.__.test`
    // green, and it is the difference between a Connect screen that says which worker is down and no
    // Connect entry in the drawer at all, on precisely the cluster somebody is investigating.
    //
    // Both directions, because a row that always advertised everything would pass the first half: the
    // not-configured row above is the one that legitimately carries none, and it stays that way.
    report(
      List(profile(cluster, List(payments))),
      Map((cluster, payments) -> Left(unreachable))
    ).map { answer =>
      val row = answer(cluster)

      assertEquals(row.status, degradedStatus)
      assertEquals(row.features, ConnectCapabilities.featuresFor(readOnly = false))
      assert(clue(row.features).contains(ConnectEndpoints.ListOperation))
      assert(clue(row.features).contains(ConnectEndpoints.RestartOperation))
    }
  }

  test("a degraded read-only cluster still withholds the three writes it could never accept") {
    // The two rules compose rather than one overriding the other: being unreachable does not hand a
    // read-only cluster a Restart button, and being read-only does not empty a degraded row.
    report(
      List(profile(readOnly, List(payments), readOnly = true)),
      Map((readOnly, payments) -> Left(unreachable))
    ).map { answer =>
      val row = answer(readOnly)

      assertEquals(row.status, degradedStatus)
      assertEquals(row.features, List(ConnectEndpoints.ListOperation))
    }
  }

  test("one failing Connect cluster degrades the row even when the other answered") {
    report(
      List(profile(cluster, List(payments, analytics))),
      Map((cluster, payments) -> healthy, (cluster, analytics) -> Left(unreachable))
    ).map(answer => assertNotEquals(answer(cluster).status, CapabilityState.Available.status))
  }

  test("a cluster with no Kafka Connect is not configured, which is not the same as degraded") {
    report(List(profile(bare, Nil)), Map.empty).map { answer =>
      val row = answer(bare)

      assertEquals(row.status, CapabilityState.NotConfigured.status)
      assert(!row.configured)
      assertEquals(row.features, Nil)
      assertEquals(row.reason, Some(ConnectCapabilities.NotConfiguredMessage))
      // The content, not just the constant: comparing the row with the constant it was built from cannot
      // fail whatever the sentence says, and the person most likely to read this one is the operator
      // wondering where the Kafka Connect row went. It names the key they have to add.
      assert(clue(ConnectCapabilities.NotConfiguredMessage).contains("kui.clusters.<n>.connect[].url"))
    }
  }

  test("a Connect cluster configured with no client built is degraded rather than hidden") {
    report(List(profile(cluster, List(payments))), Map.empty).map { answer =>
      val row = answer(cluster)

      assertNotEquals(row.status, CapabilityState.NotConfigured.status)
      assert(row.configured)
      assert(clue(row.reason.getOrElse("")).contains("could not build a client"))
    }
  }

  test("a read-only cluster advertises the read and none of the three writes") {
    // The one place the read-only decision reaches the browser before a request is made. A browser told
    // the feature was there would draw an enabled Restart whose only possible outcome is a refusal.
    report(
      List(profile(readOnly, List(payments), readOnly = true)),
      Map((readOnly, payments) -> healthy)
    ).map { answer =>
      val features = answer(readOnly).features

      assertEquals(features, List(ConnectEndpoints.ListOperation))
      assert(!features.contains(ConnectEndpoints.PauseOperation))
      assert(!features.contains(ConnectEndpoints.RestartOperation))
    }
  }

  test("the feature roster is the published contract's own names, so a fifth endpoint joins by itself") {
    assertEquals(ConnectCapabilities.Features, ConnectEndpoints.all.flatMap(_.info.name))
    assertEquals(ConnectCapabilities.WriteFeatures.size, 3)
    assertEquals(ConnectCapabilities.featuresFor(readOnly = false).size, 4)
  }

  test("rebalancing is decided by the error code and by nothing else") {
    assert(ConnectCapabilities.rebalancing(rebalancing))
    assert(!ConnectCapabilities.rebalancing(unreachable))
  }

  test("the composition root's route list is the published contract plus the three health probes") {
    // `ConnectTestServer.resource` used to assemble `HealthEndpoints.make ++ ConnectRoutes` itself, so an
    // endpoint dropped from `ConnectApi.routes` — the list the process actually serves — left every route
    // case green against a list the product does not have. The rig now drives `ConnectApi.routes` and
    // hands it back; this is the case that reads it. `services/alerts` landed the same case a wave earlier,
    // for the stream it had to hand-write; this service has no stream, and the list it must not lose a
    // member of is the four contract endpoints and the three health probes.
    //
    // Compared against `ConnectEndpoints.all` rather than against a written-out list of paths, so that a
    // fifth contract endpoint joins without editing this file — and against `documented`, which is what
    // the OpenAPI document is rendered from, so a route that is served and not published cannot appear.
    ConnectTestServer.resource().use { rig =>
      IO {
        val served = rig.routes.map(_.showPathTemplate())

        assertEquals(served.distinct.size, served.size, clue = served)
        assertEquals(served.size, ConnectApi.documented.size)
        ConnectEndpoints.all.flatMap(_.showPathTemplate().split("\\?").headOption).foreach { path =>
          assert(served.exists(_.startsWith(path)), clue = s"$path is not served: $served")
        }
      }
    }
  }
}
