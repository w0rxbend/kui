package kui.ksql.api

import cats.Id
import munit.FunSuite
import sttp.model.Method
import sttp.tapir.AnyEndpoint

import kui.contracts.KuiEndpoint
import kui.contracts.rbac.{EndpointAuthorization, NameSource}
import kui.ksql.contract.{KsqlEndpoints, KsqlStreamEndpoint}
import kui.security.rbac.{Action, Resource}

/** The properties every endpoint of this service has to have, enumerated rather than spot-checked.
  *
  * Every case below walks the published list. A fifth endpoint arriving with no permission declaration, no
  * name or an unclassified verb fails the build, which is the difference between a rule and a convention.
  */
final class KsqlContractSuite extends FunSuite {

  private val documented: List[AnyEndpoint] = KsqlApi.documented[Id]

  test("every endpoint declares the permission it needs, including the stream") {
    val undeclared =
      (KsqlEndpoints.all ++ KsqlStreamEndpoint.endpoints[Id])
        .filter(_.attribute(EndpointAuthorization.Key).isEmpty)

    assertEquals(undeclared.flatMap(_.info.name), Nil)
  }

  test("the read asks for KSQL:VIEW, which is the weaker of the two actions") {
    // **The rule this packet owns.** `Action.closure` expands a grant of `KSQL:EXECUTE` to include
    // `KSQL:VIEW`, so an operator granted only EXECUTE reaches this endpoint; asking for EXECUTE here
    // would collapse the two permissions into one and take the object list away from every read-only
    // reader in a deployment with RBAC on — which is the reason `KsqlView` was declared in wave 1.
    val declared = KsqlEndpoints.objects.attribute(EndpointAuthorization.Key)

    assertEquals(declared.map(_.requirements.map(_.resource)), Some(List(Resource.Ksql)))
    assertEquals(declared.map(_.requirements.flatMap(_.actions).toSet), Some(Set(Action.KsqlView)))
    assertNotEquals(declared.map(_.requirements.flatMap(_.actions).toSet), Some(Set(Action.KsqlExecute)))
  }

  test("the plan, the apply and the stream all ask for KSQL:EXECUTE") {
    // The plan asks for the same permission as the apply, deliberately: a preview shown to somebody who
    // cannot make the change is a dialogue whose only button leads to a refusal (`SCREENS-V4.md` §3.7).
    val executing = (KsqlEndpoints.writes :+ KsqlStreamEndpoint.endpoint[Id])
      .flatMap(_.attribute(EndpointAuthorization.Key).toList)

    assertEquals(executing.size, 3)
    executing.foreach { declared =>
      assertEquals(declared.requirements.map(_.resource), List(Resource.Ksql))
      assertEquals(declared.requirements.flatMap(_.actions).toSet, Set(Action.KsqlExecute))
    }
  }

  test("every requirement is unnamed, because Resource.Ksql has no name") {
    // A *named* requirement over an unnamed resource is a permission no role file could ever satisfy:
    // `Permission.covers` matches a pattern only against a name, and a ksqlDB has none. The cluster gate
    // is what decides which clusters a person may look at.
    assert(!Resource.Ksql.isNamed)

    val named = (KsqlEndpoints.all ++ KsqlStreamEndpoint.endpoints[Id])
      .flatMap(_.attribute(EndpointAuthorization.Key).toList)
      .flatMap(_.requirements)
      .map(_.name)

    assertEquals(named.distinct, List(NameSource.Unnamed))
  }

  test("KSQL:EXECUTE is altering and KSQL:VIEW is not, which is what the read-only gate reads") {
    // The read-only case is settled in the vocabulary and `RbacLawsSuite` holds it; this is the half that
    // says this service's endpoints are on the right side of it. A read-only cluster lists objects and
    // refuses statements because of exactly these two flags.
    assert(Action.KsqlExecute.isAlter)
    assert(!Action.KsqlView.isAlter)
    assertEquals(Action.KsqlExecute.directlyImplies, Set(Action.KsqlView))
  }

  test("both statement phases are classified as mutations and the read is not") {
    assertEquals(
      KsqlEndpoints.all.filter(KuiEndpoint.isMutation).flatMap(_.info.name),
      List(KsqlEndpoints.PlanOperation, KsqlEndpoints.ExecuteOperation)
    )
    assert(!KuiEndpoint.isMutation(KsqlEndpoints.objects))
  }

  test("the apply is destructive and the plan is not, because a plan changes nothing") {
    val markers = KsqlEndpoints.writes.flatMap(_.attribute(KuiEndpoint.MutationKey))

    assertEquals(markers.map(_.destructive), List(false, true))
    // One operation name across both phases, so "what happened to this cluster today" is answerable with
    // one string rather than two.
    assertEquals(markers.map(_.operation).distinct, List(KsqlEndpoints.StatementMutation))
  }

  test("both writes are POSTs, so the CSRF header is required from the day they exist") {
    // A header added later has to be added to every client that already shipped, and the clients that were
    // not updated start failing with a 403 that looks like a permissions problem.
    assert(KsqlEndpoints.writes.forall(_.method.contains(Method.POST)))
    assertEquals(KsqlEndpoints.objects.method, Some(Method.GET))
    assertEquals(KsqlStreamEndpoint.endpoint[Id].method, Some(Method.GET))
  }

  test("every endpoint has a name, a summary and the ksql tag") {
    (KsqlEndpoints.all ++ KsqlStreamEndpoint.endpoints[Id]).foreach { endpoint =>
      assert(endpoint.info.name.isDefined, clue = endpoint.showPathTemplate())
      assert(endpoint.info.summary.isDefined, clue = endpoint.showPathTemplate())
      assert(endpoint.info.tags.contains("ksql"), clue = endpoint.info.tags)
    }
  }

  test("every path starts at /internal/v1, because the public prefix is the gateway's") {
    val paths = (KsqlEndpoints.all ++ KsqlStreamEndpoint.endpoints[Id]).map(_.showPathTemplate())

    assert(paths.forall(_.startsWith("/internal/v1/clusters/{clusterId}/ksql")), clue = paths)
  }

  test("the read is tried first, and the plan before the apply") {
    assertEquals(
      KsqlEndpoints.all.flatMap(_.info.name),
      List(KsqlEndpoints.ListOperation, KsqlEndpoints.PlanOperation, KsqlEndpoints.ExecuteOperation)
    )
    assertEquals(
      KsqlEndpoints.all.map(_.showPathTemplate()),
      List(
        "/internal/v1/clusters/{clusterId}/ksql/objects",
        "/internal/v1/clusters/{clusterId}/ksql/statements/plan",
        "/internal/v1/clusters/{clusterId}/ksql/statements"
      )
    )
  }

  test("the push query is not in the proxied list, because a derived route cannot carry a stream") {
    // `ContractRouting.derive` decodes and re-encodes an upstream's JSON, so a derived route over a push
    // query would answer nothing until the query ended — and a push query ends only when somebody goes
    // away. House rule 16: it has a hand-written relay in the gateway instead, and this case is what stops
    // it being quietly added to the list the derivation walks.
    assert(!KsqlEndpoints.all.exists(_.showPathTemplate().contains("stream")))
    // The template carries the query parameter too, which is what a reader of the merged document sees —
    // and which is why the statement is outside the signed request digest (ADR-020) and the gateway's
    // relay re-checks the permission rather than inheriting it.
    assertEquals(
      KsqlStreamEndpoint.endpoint[Id].showPathTemplate(),
      "/internal/v1/clusters/{clusterId}/ksql/stream?statement={statement}"
    )
  }

  test("there is no endpoint outside the published contract, so nothing is served undocumented") {
    // The gateway may see a service through its `contract` module and nothing else (rules A4 and A11). An
    // endpoint declared in `api` would be invisible to the gateway by construction, and the browser would
    // have no route to it however much code was written — which is how `services/alerts` shipped a stream
    // nobody could reach.
    val health = Set("/health/live", "/health/ready", "/capabilities")
    val published =
      (KsqlEndpoints.all ++ KsqlStreamEndpoint.endpoints[Id]).map(_.showPathTemplate()).toSet

    assertEquals(documented.map(_.showPathTemplate()).toSet, published ++ health)
  }

  test("the merged document describes the stream, even though the browser's contract cannot hold it") {
    assert(documented.exists(_.showPathTemplate().contains("/ksql/stream")), clue = documented.size)
  }

  test("the published document carries the health probes every KUI service serves") {
    val paths = documented.map(_.showPathTemplate()).toSet

    assert(paths.contains("/health/live"), clue = paths)
    assert(paths.contains("/health/ready"), clue = paths)
    assert(paths.contains("/capabilities"), clue = paths)
  }

  test("the service id is the one the gateway is configured with") {
    assertEquals(KsqlApi.Id.value, "ksql")
    assertEquals(KsqlApi.ServiceName, "kui-ksql")
  }

  test("the stream's event name is the one declared in contracts-core, not a sixth local literal") {
    // `SseEventName` had no entry for the alerts wire and that name is spelled in two places in that
    // service and compared to the browser's third copy by eye. This one has one spelling.
    assertEquals(KsqlStreamEndpoint.EventName, kui.contracts.sse.SseEventName.Row)
  }
}
