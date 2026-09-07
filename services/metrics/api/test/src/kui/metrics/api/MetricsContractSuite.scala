package kui.metrics.api

import munit.FunSuite

import kui.contracts.KuiEndpoint
import kui.contracts.rbac.EndpointAuthorization
import kui.metrics.contract.MetricsEndpoints
import kui.security.rbac.{Action, Resource}

/** What this service publishes about itself, checked here because nothing else can check it for one service.
  *
  * The gateway's own suites enumerate every service's endpoints at once, but they only run when the gateway
  * builds; these three properties are cheap, and finding out at `services.metrics.api.test` that an endpoint
  * has no permission is the difference between a five-second failure and a twenty-minute one.
  */
final class MetricsContractSuite extends FunSuite {

  test("every endpoint declares the permission it needs") {
    val undeclared = MetricsEndpoints.all.filter(EndpointAuthorization.of(_).isEmpty)

    assertEquals(undeclared.map(_.showShort), Nil)
  }

  test("every endpoint needs METRICS:VIEW and nothing else, and none of them is an alter") {
    // One requirement for five reads, declared through one function so a sixth cannot arrive with a
    // subtly different one — and asserted over `all` rather than over throughput alone, because an
    // endpoint that asked for a resource nobody grants would be a card that is permanently forbidden on
    // a deployment where every other card works.
    val declared = MetricsEndpoints.all.map(endpoint =>
      EndpointAuthorization.of(endpoint).getOrElse(fail(s"${endpoint.showShort} carries no authorization"))
    )

    assertEquals(declared.map(_.operation), MetricsEndpoints.all.flatMap(_.info.name))
    assertEquals(declared.flatMap(_.requirements.map(_.resource)).distinct, List(Resource.Metrics))
    assertEquals(declared.flatMap(_.requirements.flatMap(_.actions.toList)).distinct, List(Action.MetricsView))
    // A read-only cluster must still be able to look at a chart. `Rbac.decide` refuses any altering
    // action there, so an action classified wrongly would take the whole dashboard away from a
    // deployment that deliberately cannot change anything.
    assert(!Action.MetricsView.isAlter)
  }

  test("nothing this service publishes changes a cluster") {
    // No mutation marker anywhere: the metrics service reads, and the day it acquires a write — an
    // acknowledgement, a threshold — the marker and the CSRF header arrive with it rather than after it.
    assertEquals(MetricsEndpoints.all.filter(KuiEndpoint.isMutation).map(_.showShort), Nil)
  }

  test("every endpoint carries a unique name and a non-blank summary") {
    // The gateway's style check enforces both across the merged document; asserting them here means a
    // missing summary is found by the suite of the service that forgot it.
    val names = MetricsEndpoints.all.flatMap(_.info.name)

    assertEquals(names.size, MetricsEndpoints.all.size)
    assertEquals(names.distinct.size, names.size)
    assert(MetricsEndpoints.all.forall(_.info.summary.exists(!_.isBlank)))
    assert(names.forall(_.startsWith("metrics.")), names.toString)
  }

  test("the five endpoints the milestone names are the five that are published") {
    // The list the gateway derives its proxy routes from, and the list `MetricsCapabilities` derives its
    // feature roster from. A path written here and forgotten in `all` is an endpoint no deployment
    // serves; a path in `all` that the browser does not know about is a card nobody can draw.
    assertEquals(
      MetricsEndpoints.all.flatMap(_.info.name),
      List(
        "metrics.throughput",
        "metrics.latency",
        "metrics.requestHandlers",
        "metrics.producers",
        "metrics.recordSize"
      )
    )
  }

  test("every endpoint hangs off one cluster's metrics prefix") {
    // The gateway rewrites the first segment of each of these into `/api/v1`, so a path that did not
    // start at `internal/v1/clusters/{clusterId}/metrics` would be proxied somewhere nobody meant.
    val paths = MetricsEndpoints.all.map(_.showPathTemplate().takeWhile(_ != '?'))

    assert(
      paths.forall(_.startsWith("/internal/v1/clusters/{clusterId}/metrics/")),
      paths.toString
    )
    assertEquals(paths.distinct.size, paths.size, paths.toString)
  }
}
