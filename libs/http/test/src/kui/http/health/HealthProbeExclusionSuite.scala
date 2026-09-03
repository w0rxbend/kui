package kui.http.health

import cats.effect.IO
import munit.FunSuite
import sttp.tapir.server.ServerEndpoint

import kui.http.BasePath
import kui.observability.KuiInterceptors

/** That the probes `HealthEndpoints` declares are the probes the duration histogram leaves out.
  *
  * The exclusion list lives in `libs/observability`, which cannot see `libs/http`, so it has to name the
  * probes rather than import them. That is exactly the arrangement two copies of a value drift apart in — and
  * it did drift: the list held paths, while the probes are served under a base path (`/kui/health/live`) or,
  * in the gateway, under the API prefix (`/api/v1/health/live`), so nothing was ever excluded and every probe
  * landed in the histogram.
  *
  * This suite is the join between the two halves. It asserts against the real endpoint values, so a rename on
  * either side fails here rather than in production a month later.
  */
final class HealthProbeExclusionSuite extends FunSuite {

  private val probes: List[ServerEndpoint[Any, IO]] =
    HealthEndpoints.probes[IO](Nil)

  test("every health probe is excluded from the duration histogram, however it is mounted") {
    val liveness = probes.filter(_.endpoint.info.name.contains("health.live"))
    val readiness = probes.filter(_.endpoint.info.name.contains("health.ready"))
    assert(liveness.nonEmpty && readiness.nonEmpty, probes.map(_.endpoint.info.name).toString)

    val mountings = List(
      "" -> probes,
      "/kui" -> BasePath.prefixAll("/kui", probes),
      "/api/v1" -> BasePath.prefixAll("/api/v1", probes)
    )

    mountings.foreach { (basePath, mounted) =>
      (liveness ++ readiness).map(_.endpoint.info.name).foreach { name =>
        mounted.filter(_.endpoint.info.name == name).foreach { probe =>
          assert(
            !KuiInterceptors.isMeasured(probe.endpoint),
            s"$name under '$basePath' is measured as ${KuiInterceptors.routeLabel(probe.endpoint)}"
          )
        }
      }
    }
  }

  test("the capabilities endpoint is still measured, so the exclusion is not a blanket one") {
    val capabilities = HealthEndpoints.capabilities
    assert(KuiInterceptors.isMeasured(capabilities))
    assert(KuiInterceptors.isMeasured(BasePath.prefix("/kui", capabilities.serverLogicSuccess[IO](_ => ???)).endpoint))
  }

  test("the operation ids observability excludes are the ids the endpoints declare") {
    val declared = probes.flatMap(_.endpoint.info.name).toSet
    assertEquals(KuiInterceptors.UnmeasuredOperations, declared)

    // And the paths kept as the second net are the paths the endpoints are declared at.
    assertEquals(KuiInterceptors.UnmeasuredRoutes, Set(HealthEndpoints.LivePath, HealthEndpoints.ReadyPath))
  }
}
