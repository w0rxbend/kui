package kui.connect.api

import munit.FunSuite
import sttp.model.Method
import sttp.tapir.AnyEndpoint

import kui.connect.contract.ConnectEndpoints
import kui.connect.domain.ConnectorOperation
import kui.contracts.KuiEndpoint
import kui.contracts.rbac.{EndpointAuthorization, NameSource}
import kui.security.rbac.{Action, Resource}

/** The properties every endpoint of this service has to have, enumerated rather than spot-checked.
  *
  * Every case below walks the published list. A fifth endpoint arriving with no permission declaration, no
  * name or an unclassified verb fails the build, which is the difference between a rule and a convention.
  */
final class ConnectContractSuite extends FunSuite {

  private val documented: List[AnyEndpoint] = ConnectApi.documented

  test("every endpoint declares the permission it needs") {
    val undeclared = ConnectEndpoints.all.filter(_.attribute(EndpointAuthorization.Key).isEmpty)

    assertEquals(undeclared.flatMap(_.info.name), Nil)
  }

  test("the three writes need CONNECT:OPERATE on the Connect cluster named in the path") {
    // Named by the path parameter and not unnamed: `Resource.Connect.isNamed` is true, so every grant
    // carries a pattern, and `Permission.covers` matches a pattern only against a name. An unnamed
    // requirement here would be a permission no role file could ever satisfy.
    val writes = ConnectEndpoints.writes.flatMap(_.attribute(EndpointAuthorization.Key).toList)

    assertEquals(writes.size, 3)
    assert(Resource.Connect.isNamed)
    writes.foreach { declared =>
      assertEquals(declared.requirements.map(_.resource), List(Resource.Connect))
      assertEquals(declared.requirements.flatMap(_.actions).toSet, Set(Action.ConnectOperate))
      assertEquals(
        declared.requirements.map(_.name),
        List(NameSource.PathParam(ConnectEndpoints.ConnectNameParam))
      )
    }
  }

  test("CONNECT:OPERATE is what an operator's file spells RESTART") {
    // The alias shipped in wave 1 and this is the first assertion of it anywhere: `grep -rn
    // ConnectRestartAlias` over the repository finds its declaration in `Vocabulary.scala`, its one use in
    // `Action.fromWire` beside it, and — until this case — nothing else. It is why the writes declare
    // `ConnectOperate` rather than a connector-level action: a role file that says RESTART on a Connect
    // cluster is granting exactly this, and a rename of either side would otherwise be silent.
    assertEquals(Action.fromWire(Resource.Connect, Action.ConnectRestartAlias), Some(Action.ConnectOperate))
  }

  test("the read is cluster-scoped, which is what every list endpoint in this product declares") {
    // ADR-021 keeps Kafbat's rule that a list filters rather than refuses: an operator who may see one of
    // two Connect clusters wants to see one, not a 403 — and a 403 would leak that the other exists.
    // `topic.list`, `schema.subjects` and `consumer.list` are the same declaration.
    val declared = ConnectEndpoints.connectors.attribute(EndpointAuthorization.Key)

    assertEquals(declared.map(_.requirements), Some(Nil))
    assertEquals(declared.map(_.operation), Some(ConnectEndpoints.ListOperation))
  }

  test("every write is classified as a mutation and the read is not") {
    assertEquals(
      ConnectEndpoints.all.filter(KuiEndpoint.isMutation).flatMap(_.info.name),
      List(
        ConnectEndpoints.PauseOperation,
        ConnectEndpoints.ResumeOperation,
        ConnectEndpoints.RestartOperation
      )
    )
  }

  test("each marker names the same operation the audit line writes, and none is destructive") {
    // Not destructive: nothing is lost. A paused connector resumes, and a restarted one re-reads its own
    // committed offsets — so there is no ADR-045 plan and no typed confirmation, and there *is* an audit
    // record.
    val markers = ConnectEndpoints.writes.flatMap(_.attribute(KuiEndpoint.MutationKey))

    assertEquals(markers.map(_.operation), ConnectorOperation.values.map(_.operation).toList)
    assert(markers.forall(!_.destructive))
  }

  test("every write is a POST, so the CSRF header is required from the day it exists") {
    // A header added later has to be added to every client that already shipped, and the clients that were
    // not updated start failing with a 403 that looks like a permissions problem.
    assert(ConnectEndpoints.writes.forall(_.method.contains(Method.POST)))
    assertEquals(ConnectEndpoints.connectors.method, Some(Method.GET))
  }

  test("every endpoint has a name, a summary and the connect tag") {
    ConnectEndpoints.all.foreach { endpoint =>
      assert(endpoint.info.name.isDefined, clue = endpoint.showPathTemplate())
      assert(endpoint.info.summary.isDefined, clue = endpoint.showPathTemplate())
      assert(endpoint.info.tags.contains("connect"), clue = endpoint.info.tags)
    }
  }

  test("every path starts at /internal/v1, because the public prefix is the gateway's") {
    val paths = ConnectEndpoints.all.map(_.showPathTemplate())

    assert(paths.forall(_.startsWith("/internal/v1/clusters/{clusterId}/connect")), clue = paths)
  }

  test("the read is tried first and the three writes address one connector on one Connect cluster") {
    assertEquals(
      ConnectEndpoints.all.flatMap(_.info.name),
      List(
        ConnectEndpoints.ListOperation,
        ConnectEndpoints.PauseOperation,
        ConnectEndpoints.ResumeOperation,
        ConnectEndpoints.RestartOperation
      )
    )
    assertEquals(
      ConnectEndpoints.writes.map(_.showPathTemplate()),
      List("pause", "resume", "restart").map(verb =>
        s"/internal/v1/clusters/{clusterId}/connect/{connectName}/connectors/{connectorName}/$verb"
      )
    )
  }

  test("there is no endpoint outside the published contract, so nothing is served undocumented") {
    // The gateway may see a service through its `contract` module and nothing else (rules A4 and A11).
    // An endpoint declared in `api` would be invisible to the gateway by construction, and the browser
    // would have no route to it however much code was written — which is how `services/alerts` shipped a
    // stream nobody could reach.
    val health = Set("/health/live", "/health/ready", "/capabilities")
    val published = ConnectEndpoints.all.map(_.showPathTemplate()).toSet

    assertEquals(documented.map(_.showPathTemplate()).toSet, published ++ health)
  }

  test("this service publishes no stream, because Connect has no change feed to relay") {
    // House rule 16 asks a stream to ship its relay; the honest answer here is that there is no stream.
    // Connect's REST API is poll-only, so an SSE endpoint would have to be KUI polling on the browser's
    // behalf and calling it a stream. ADR-054 §5 records that, and this case is what stops one arriving
    // without the relay that would then be needed.
    assert(documented.forall(endpoint => !endpoint.showPathTemplate().contains("stream")))
  }

  test("the published document carries the health probes every KUI service serves") {
    val paths = documented.map(_.showPathTemplate()).toSet

    assert(paths.contains("/health/live"), clue = paths)
    assert(paths.contains("/health/ready"), clue = paths)
    assert(paths.contains("/capabilities"), clue = paths)
  }

  test("the service id is the one the gateway is configured with") {
    assertEquals(ConnectApi.Id.value, "connect")
    assertEquals(ConnectApi.ServiceName, "kui-connect")
  }
}
