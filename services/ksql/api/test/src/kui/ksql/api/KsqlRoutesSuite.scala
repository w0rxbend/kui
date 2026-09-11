package kui.ksql.api

import cats.effect.IO
import munit.CatsEffectSuite

import kui.kernel.RoleName
import kui.ksql.contract.KsqlEndpoints
import kui.security.rbac.{Action, RbacPolicy, Resource, Role}

/** The routes, through the real interceptor chain and the real permission guard.
  *
  * Every refusal below is asserted twice: once on the status and the error code the caller sees, and once on
  * **what the ksqlDB server was asked**. The second assertion is the one that matters — "the statement was
  * refused" and "the statement was refused before ksqlDB was called" are different promises, and only the
  * second is worth anything to somebody who has just found a topic missing.
  */
final class KsqlRoutesSuite extends CatsEffectSuite {

  import KsqlTestServer.*

  private val pushQuery = "SELECT * FROM ORDERS EMIT CHANGES;"
  private val dropWithTopic = "DROP STREAM ORDERS DELETE TOPIC;"
  private val harmless = "CREATE STREAM A AS SELECT * FROM ORDERS;"

  /** A deployment with RBAC on: one role that may look, one that may run.
    *
    * `Resource.Ksql` is unnamed, so both permissions are over the whole resource; the difference between the
    * two roles is the *action*, which is exactly the difference the endpoints declare.
    */
  private val policy: RbacPolicy =
    RbacPolicy(
      List(
        Role(
          viewer,
          // The role has to *name* the clusters it applies to: `Rbac.clusterGate` refuses a principal
          // whose roles name no such cluster, before any resource is considered. A role with an empty
          // cluster set grants nothing, which is Kafbat's rule and makes a forgotten list deny.
          Set(cluster, readOnly),
          Nil,
          List(RbacPolicy.permission(Resource.Ksql, None, Set(Action.KsqlView)))
        ),
        Role(
          operator,
          Set(cluster, readOnly),
          Nil,
          // Closed through `RbacPolicy.permission`, which is the only correct way to build one — and it is
          // `Action.closure` that turns a grant of EXECUTE into {EXECUTE, VIEW}. This role is what makes
          // the implication observable at the endpoint rather than only in `RbacLawsSuite`.
          List(RbacPolicy.permission(Resource.Ksql, None, Set(Action.KsqlExecute)))
        )
      ),
      None
    )

  // -----------------------------------------------------------------------------------------------
  // The read
  // -----------------------------------------------------------------------------------------------

  test("a deployment with no ksqlDB address answers not_configured with a 200") {
    // The required case, at the wire. A 404 would tell a browser the address is wrong; an empty list would
    // tell it this ksqlDB has no streams. Both are false, and ADR-032's rule is that the row is hidden.
    resource().use(rig =>
      get(rig, objectsPath(bare)).map { response =>
        assertEquals(response.code.code, 200)
        assertEquals(
          body(response).hcursor.downField("objects").get[String]("status"),
          Right("not_configured")
        )
      }
    )
  }

  test("a cluster KUI has never heard of is a 404, because the caller followed a dead link") {
    resource().use(rig =>
      get(rig, objectsPath(kui.kernel.ClusterId.unsafe("nope"))).map { response =>
        assertEquals(response.code.code, 404)
        assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-CLUSTER-NOT-FOUND"))
      }
    )
  }

  test("a working ksqlDB reaches the wire with each object's kind") {
    resource().use(rig =>
      get(rig, objectsPath(cluster)).map { response =>
        assertEquals(response.code.code, 200)

        val items = body(response).hcursor
          .downField("objects")
          .downField("data")
          .downField("items")
          .values
          .toList
          .flatten

        assertEquals(
          items.flatMap(_.hcursor.get[String]("kind").toOption),
          List("stream", "table")
        )
      }
    )
  }

  // -----------------------------------------------------------------------------------------------
  // The permission, which is the rule this packet owns
  // -----------------------------------------------------------------------------------------------

  test("a statement a principal without KSQL:EXECUTE asks for is refused before the server is called") {
    // The required case. `KSQL:VIEW` is a real permission and this caller holds it, so the refusal is not
    // "you cannot see ksqlDB" — it is "you cannot run things on it", which is the whole reason `KsqlView`
    // and `KsqlExecute` are two actions.
    resource(policy).use(rig =>
      for {
        response <- post(rig, statementsPath(cluster), harmless, roles = Set(viewer))
        executed <- rig.server.executed.get
      } yield {
        assertEquals(response.code.code, 403)
        assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-FORBIDDEN"))
        // The order, which only exists once the guard, the route and the use case are assembled: the
        // statement never reached ksqlDB.
        assertEquals(executed, Nil)
      }
    )
  }

  test("the same principal lists the objects, because KSQL:VIEW is what the read asks for") {
    resource(policy).use(rig =>
      get(rig, objectsPath(cluster), roles = Set(viewer)).map(response =>
        assertEquals(response.code.code, 200)
      )
    )
  }

  test("a principal granted only KSQL:EXECUTE both runs and reads, because EXECUTE implies VIEW") {
    // The other half of the rule this packet owns, and the half a mutation at the endpoint's declaration
    // breaks: `Action.closure` expands a grant of EXECUTE to include VIEW, so an operator granted only
    // EXECUTE reaches the endpoint that asks for VIEW. An endpoint that asked for EXECUTE on the read
    // would collapse the two permissions into one and take the object list away from every read-only
    // reader in a deployment with RBAC on.
    resource(policy).use(rig =>
      for {
        read <- get(rig, objectsPath(cluster), roles = Set(operator))
        ran <- post(rig, statementsPath(cluster), harmless, roles = Set(operator))
        executed <- rig.server.executed.get
      } yield {
        assertEquals(read.code.code, 200)
        assertEquals(ran.code.code, 200)
        assertEquals(executed, List(harmless))
      }
    )
  }

  test("a principal with no ksql permission at all is refused the read as well as the statement") {
    resource(policy).use(rig =>
      for {
        read <- get(rig, objectsPath(cluster), roles = Set(RoleName.unsafe("nobody")))
        ran <- post(rig, statementsPath(cluster), harmless, roles = Set(RoleName.unsafe("nobody")))
        executed <- rig.server.executed.get
        reads <- rig.server.reads.get
      } yield {
        assertEquals(read.code.code, 403)
        assertEquals(ran.code.code, 403)
        assertEquals(executed, Nil)
        assertEquals(reads, 0)
      }
    )
  }

  test("a push query a principal without KSQL:EXECUTE opens is refused before the query is started") {
    resource(policy).use(rig =>
      for {
        response <- get(rig, s"${streamPath(cluster)}?statement=$pushQuery", roles = Set(viewer))
        opened <- rig.server.opened.get
      } yield {
        assertEquals(response.code.code, 403)
        assertEquals(opened, Nil)
      }
    )
  }

  test("an unsigned caller is refused before the service parses a byte of what they sent") {
    resource().use(rig =>
      sttp.client4.basicRequest
        .post(address(statementsPath(cluster)))
        .header(kui.contracts.HttpHeaders.Csrf, "test-csrf")
        .body("""{"statement":"DROP STREAM ORDERS DELETE TOPIC;"}""")
        .response(sttp.client4.asStringAlways)
        .send(rig.backend)
        .flatMap(response => rig.server.executed.get.map(executed => (response, executed)))
        .map { (response, executed) =>
          assertEquals(response.code.code, 401)
          assertEquals(executed, Nil)
        }
    )
  }

  // -----------------------------------------------------------------------------------------------
  // The plan and the apply
  // -----------------------------------------------------------------------------------------------

  test("DROP ... DELETE TOPIC is refused without a plan token and accepted with one") {
    // The required case, end to end through the routes: the refusal is asserted against the server, so a
    // topic that was dropped and then reported as refused would fail here.
    resource().use(rig =>
      for {
        refused <- post(rig, statementsPath(cluster), dropWithTopic)
        afterRefusal <- rig.server.executed.get

        planned <- post(rig, planPath(cluster), dropWithTopic)
        token = body(planned).hcursor.get[Option[String]]("token").toOption.flatten

        accepted <- post(rig, statementsPath(cluster), dropWithTopic, token)
        afterApply <- rig.server.executed.get
      } yield {
        assertEquals(refused.code.code, 400)
        assertEquals(body(refused).hcursor.get[String]("code"), Right("KUI-VALIDATION"))
        assertEquals(afterRefusal, Nil)

        assertEquals(planned.code.code, 200)
        assert(token.isDefined, clue = planned.body)

        assertEquals(accepted.code.code, 200)
        assertEquals(afterApply, List(dropWithTopic))
      }
    )
  }

  test("a plan for a harmless statement answers no token, and the statement runs without one") {
    resource().use(rig =>
      for {
        planned <- post(rig, planPath(cluster), harmless)
        ran <- post(rig, statementsPath(cluster), harmless)
      } yield {
        assertEquals(body(planned).hcursor.get[Option[String]]("token"), Right(None))
        assertEquals(body(planned).hcursor.get[Boolean]("destructive"), Right(false))
        assertEquals(ran.code.code, 200)
      }
    )
  }

  test("a plan names the Kafka topic the drop would delete") {
    resource().use(rig =>
      post(rig, planPath(cluster), dropWithTopic).map { response =>
        val warnings = body(response).hcursor.get[List[String]]("warnings").getOrElse(Nil)

        assert(clue(warnings).exists(_.contains("'orders'")))
      }
    )
  }

  test("a statement on a read-only cluster is refused and never reaches the server") {
    resource().use(rig =>
      for {
        response <- post(rig, statementsPath(readOnly), harmless)
        executed <- rig.server.executed.get
      } yield {
        // Two layers refuse this and the outer one wins, which is the `services/connect` shape and worth
        // stating because the code a caller sees is not the one `MutationGuard` writes.
        // `RbacGuard.fromPolicy` is given the deployment's read-only flags and refuses an *altering*
        // action before the route's logic runs at all, and its refusal is the deliberately uninformative
        // `KUI-FORBIDDEN`; `MutationGuard`'s `KUI-READ-ONLY` is the refusal behind it, reachable when a
        // composition root wires `RbacGuard.allowAll`, and `MutationGuardSuite` is where it is asserted.
        // What matters at both layers is that ksqlDB was never called.
        assertEquals(response.code.code, 403)
        assertEquals(body(response).hcursor.get[String]("code"), Right("KUI-FORBIDDEN"))
        assertEquals(executed, Nil)
      }
    )
  }

  test("a SELECT ... EMIT CHANGES is refused by the statements endpoint and named at the stream") {
    // The required case's first half: a push query answers a stream rather than a document. Answering it
    // here would mean buffering an unbounded result into one body — a request that never returns.
    resource().use(rig =>
      for {
        response <- post(rig, statementsPath(cluster), pushQuery)
        executed <- rig.server.executed.get
      } yield {
        assertEquals(response.code.code, 400)
        assert(clue(response.body).contains("/ksql/stream"))
        assertEquals(executed, Nil)
      }
    )
  }

  test("a statement a caller sent two of is refused, so a batch cannot hide a topic deletion") {
    resource().use(rig =>
      for {
        response <- post(rig, statementsPath(cluster), s"$harmless $dropWithTopic")
        executed <- rig.server.executed.get
      } yield {
        assertEquals(response.code.code, 400)
        assertEquals(executed, Nil)
      }
    )
  }

  // -----------------------------------------------------------------------------------------------
  // The stream
  // -----------------------------------------------------------------------------------------------

  test("a push query answers an event stream whose first frame is the phase carrying the columns") {
    // The required case's second half, at the wire. The frame grammar is ADR-035's and the names are
    // `SseEventName`'s, so a browser that already reads the alerts stream reads this one.
    resource().use(rig =>
      stream(rig, s"${streamPath(cluster)}?statement=$pushQuery").flatMap((status, frames) =>
        rig.server.opened.get.map { opened =>
          assertEquals(status, 200)
          assert(clue(frames).startsWith("event: phase"))
          assert(clue(frames).contains("\"columns\":[\"ID\"]"))
          assert(clue(frames).contains("event: row"))
          assert(clue(frames).contains("\"values\":[\"17\"]"))
          // ADR-035: exactly one terminal event, so a browser is never left guessing whether the
          // connection broke or the query ended.
          assertEquals(frames.linesIterator.count(_.startsWith("event: done")), 1)
          assertEquals(opened, List(pushQuery))
        }
      )
    )
  }

  test("a statement that finishes is refused by the stream endpoint") {
    resource().use(rig =>
      for {
        response <- get(rig, s"${streamPath(cluster)}?statement=SELECT+*+FROM+ORDERS;")
        opened <- rig.server.opened.get
      } yield {
        assertEquals(response.code.code, 400)
        assert(clue(response.body).contains("/ksql/statements"))
        assertEquals(opened, Nil)
      }
    )
  }

  test("the stream's path is the one the gateway's relay is written against") {
    // W8-03's `KsqlStreamRoutes` rewrites this endpoint's prefix and relays its bytes. The two sides agree
    // because they hold the same endpoint value; this case is what says so out loud.
    assertEquals(
      kui.ksql.contract.KsqlStreamEndpoint.endpoint[IO].showPathTemplate(),
      "/internal/v1/clusters/{clusterId}/ksql/stream?statement={statement}"
    )
    assertEquals(KsqlEndpoints.objects.showPathTemplate(), "/internal/v1/clusters/{clusterId}/ksql/objects")
  }
}
