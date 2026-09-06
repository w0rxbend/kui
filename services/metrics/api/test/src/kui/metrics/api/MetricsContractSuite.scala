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

  test("the throughput endpoint needs METRICS:VIEW, which is not an alter") {
    val declared = EndpointAuthorization
      .of(MetricsEndpoints.throughput)
      .getOrElse(fail("the throughput endpoint carries no authorization declaration"))

    assertEquals(declared.operation, "metrics.throughput")
    assertEquals(declared.requirements.map(_.resource), List(Resource.Metrics))
    assertEquals(declared.requirements.flatMap(_.actions.toList), List(Action.MetricsView))
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
}
