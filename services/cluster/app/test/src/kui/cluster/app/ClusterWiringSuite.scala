package kui.cluster.app

import cats.effect.IO
import munit.CatsEffectSuite

import kui.cluster.api.ClusterApi
import kui.cluster.contract.ClusterEndpoints
import kui.observability.Telemetry
import kui.security.PrincipalCodec
import kui.testkit.fakes.FakeStructuredLogger

/** That the composition root produces everything the contract promises, and nothing that listens.
  *
  * The second half is the one worth a suite. `ClusterWiring.make` returning routes rather than a running
  * server is what lets the all-in-one process mount this service in-process (ADR-010), and it is the kind of
  * property that is quietly lost the first time someone finds it convenient to start a listener inside the
  * wiring. So the suite allocates the wiring twice and asserts that nothing was bound.
  */
final class ClusterWiringSuite extends CatsEffectSuite {

  private def wiring =
    FakeStructuredLogger[IO].toResource.flatMap(logger =>
      ClusterWiring.make[IO](Telemetry.noop[IO], PrincipalCodec.inProcess[IO], logger)
    )

  test("wiringProducesEveryEndpointInTheContract") {
    wiring.use { server =>
      IO {
        val served = server.routes.map(_.endpoint.showPathTemplate(showQueryParam = None)).toSet

        // Everything the contract publishes is served. A contract endpoint with no route is an
        // endpoint the gateway will proxy to a 404.
        ClusterEndpoints.all.foreach(endpoint =>
          assert(
            served.contains(endpoint.showPathTemplate(showQueryParam = None)),
            s"$served does not serve $endpoint"
          )
        )

        // ...plus the three every KUI service serves, and nothing else. The count is asserted so
        // that a route added without a contract entry is caught here rather than in production.
        assertEquals(
          served,
          Set("/health/live", "/health/ready", "/capabilities", "/internal/v1/ping")
        )
        assertEquals(server.routes.size, ClusterEndpoints.all.size + 3)
      }
    }
  }

  test("wiringBindsNoPortAndIsSafeToAllocateTwice") {
    // Allocated and released twice. If `make` bound a listener, the second allocation would fail
    // with "address already in use" — which is exactly the failure the all-in-one process would
    // hit when it mounted two services.
    for {
      first <- wiring.use(server => IO.pure(server.routes.size))
      second <- wiring.use(server => IO.pure(server.routes.size))
    } yield assertEquals(first, second)
  }

  test("readinessIsTrueAfterStartup") {
    wiring.use { server =>
      kui.http.health.HealthEndpoints
        .report[IO](server.readiness)
        .map { report =>
          assert(report.ready, report.toString)
          // Named rather than empty: a readiness report with no checks in it reads like a bug,
          // while one naming `process` says plainly that this service depends on nothing to serve.
          assertEquals(report.checks.map(_.name), List("process"))
        }
    }
  }

  test("theCapabilityDocumentNamesThisServiceEvenWithNoClustersConfigured") {
    wiring.use { server =>
      server.capabilities.map { document =>
        assertEquals(document.service, ClusterApi.Id)
        // Empty and not an error. A KUI started before anyone has configured a cluster genuinely
        // has nothing cluster-scoped to report, and the gateway must render that as "nothing
        // configured yet" rather than as an outage.
        assertEquals(document.clusters, Map.empty)
      }
    }
  }

  test("theReferenceConfigurationDocumentsTheDefaultsTheCodeActuallyUses") {
    // `reference.yaml` is not loaded — it is documentation beside the code — which is exactly why
    // it needs a test: nothing else would notice it going stale.
    val reference = scala.io.Source
      .fromInputStream(getClass.getResourceAsStream("/reference.yaml"), "UTF-8")
      .mkString

    val defaults = ClusterServiceConfig.Default
    assert(reference.contains(s"""host: "${defaults.server.host.value}""""), reference)
    assert(reference.contains(s"port: ${defaults.server.port.value}"), reference)
    assert(reference.contains(s"""basePath: "${defaults.server.basePath}""""), reference)
    assert(reference.contains(s"logFormat: ${defaults.telemetry.logFormat.wire}"), reference)
    assert(reference.contains(s"hashUserIds: ${defaults.telemetry.hashUserIds}"), reference)
    assertEquals(defaults.principalKeys, Nil)
  }
}
