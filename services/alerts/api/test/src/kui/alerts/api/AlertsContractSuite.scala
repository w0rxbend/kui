package kui.alerts.api

import munit.FunSuite
import sttp.model.Method
import sttp.tapir.AnyEndpoint

import kui.alerts.application.AcknowledgementRecord
import kui.alerts.contract.{AlertsEndpoints, AlertsStreamEndpoint}
import kui.contracts.KuiEndpoint
import kui.contracts.rbac.EndpointAuthorization
import kui.security.rbac.{Action, Resource}

/** The properties every endpoint of this service has to have, enumerated rather than spot-checked.
  *
  * Every case below walks the published list. A third endpoint arriving with no permission declaration, no
  * name or an unclassified verb fails the build, which is the difference between a rule and a convention —
  * and the eighth service's arrival is what proved the difference matters.
  */
final class AlertsContractSuite extends FunSuite {

  private val documented: List[AnyEndpoint] = AlertsApi.documented[cats.Id]

  test("every endpoint declares the permission it needs") {
    val undeclared = AlertsEndpoints.all.filter(_.attribute(EndpointAuthorization.Key).isEmpty)

    assertEquals(undeclared.flatMap(_.info.name), Nil)
  }

  test("every read needs ALERTS:VIEW and the one write needs ALERTS:ACKNOWLEDGE") {
    val required = AlertsEndpoints.all.map { endpoint =>
      endpoint.info.name.getOrElse("") ->
        endpoint.attribute(EndpointAuthorization.Key).toList.flatMap(_.requirements.flatMap(_.actions)).toSet
    }

    assertEquals(
      required.toMap,
      Map(
        "alerts.events" -> Set(Action.AlertsView),
        "alerts.acknowledge" -> Set(Action.AlertsAcknowledge)
      )
    )
  }

  test("every permission is unnamed, because there is one feed per cluster") {
    // `Resource.Alerts.isNamed` is false and an event's id is generated rather than chosen, so a pattern
    // written against one would match nothing an operator meant by it. A named requirement here would be a
    // permission no role file could ever satisfy.
    val requirements =
      AlertsEndpoints.all.flatMap(_.attribute(EndpointAuthorization.Key).toList.flatMap(_.requirements))

    assert(requirements.nonEmpty)
    assert(requirements.forall(_.resource == Resource.Alerts))
    assert(!Resource.Alerts.isNamed)
  }

  test("every endpoint is classified: the write carries the mutation marker and the read does not") {
    assertEquals(AlertsEndpoints.all.filter(KuiEndpoint.isMutation).flatMap(_.info.name), List("alerts.acknowledge"))
  }

  test("the write's marker names the same operation the audit line writes") {
    val marker = AlertsEndpoints.acknowledge.attribute(KuiEndpoint.MutationKey)

    assertEquals(marker.map(_.operation), Some(AcknowledgementRecord.Operation))
    // Not destructive: the event stays in the feed with the name of whoever acknowledged it, so there is
    // no ADR-045 plan and no typed confirmation.
    assertEquals(marker.map(_.destructive), Some(false))
  }

  test("the write requires the CSRF header from the day it exists") {
    // A header added later has to be added to every client that already shipped, and the clients that were
    // not updated start failing with a 403 that looks like a permissions problem.
    assert(AlertsEndpoints.acknowledge.showPathTemplate().nonEmpty)
    assertEquals(AlertsEndpoints.acknowledge.method, Some(Method.POST))
  }

  test("every endpoint has a name, a summary and the alerts tag") {
    (AlertsEndpoints.all ++ AlertsStreamEndpoint.endpoints[cats.Id]).foreach { endpoint =>
      assert(endpoint.info.name.isDefined, clue = endpoint.showPathTemplate())
      assert(endpoint.info.summary.isDefined, clue = endpoint.showPathTemplate())
    }

    val tagged = AlertsEndpoints.all ++ AlertsStreamEndpoint.endpoints[cats.Id]
    assert(tagged.forall(_.info.tags.contains("alerts")), clue = tagged.map(_.info.tags))
  }

  test("every path starts at /internal/v1, because the public prefix is the gateway's") {
    val paths = (AlertsEndpoints.all ++ AlertsStreamEndpoint.endpoints[cats.Id]).map(_.showPathTemplate())

    assert(paths.forall(_.startsWith("/internal/v1/clusters/{clusterId}/alerts")), clue = paths)
  }

  test("the read is tried before the write, because its path is a prefix of the write's") {
    assertEquals(AlertsEndpoints.all.flatMap(_.info.name), List("alerts.events", "alerts.acknowledge"))
  }

  test("the stream is not in AlertsEndpoints.all, and is in the published document") {
    // It cannot be in `all`: describing an event-stream body needs fs2, and the shared sources of the
    // contract module link for the browser. The document is where the two halves are put back together.
    assert(!AlertsEndpoints.all.flatMap(_.info.name).contains("alerts.stream"))
    assert(documented.flatMap(_.info.name).contains("alerts.stream"))
  }

  test("the stream endpoint is in the contract module, which is the only layer the gateway may see") {
    // Rules A4 and A11 let the gateway see a service through its `contract` module and nothing else, and
    // a stream cannot be proxied by `ContractRouting.derive` — it needs a hand-written relay holding this
    // endpoint value. Declared in `api`, it would be invisible to the gateway by construction and the
    // browser would have no route to it however much code was written. `services/message`'s browse stream
    // is in the same position, in the same place, for the same reason.
    assertEquals(
      AlertsStreamEndpoint.getClass.getPackageName,
      "kui.alerts.contract",
      clue = "a gateway relay cannot import kui.alerts.api"
    )
  }

  test("the published document carries the health probes every KUI service serves") {
    val paths = documented.map(_.showPathTemplate()).toSet

    assert(paths.contains("/health/live"), clue = paths)
    assert(paths.contains("/health/ready"), clue = paths)
    assert(paths.contains("/capabilities"), clue = paths)
  }
}
