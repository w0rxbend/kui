package kui.alerts.api

import cats.effect.IO
import munit.CatsEffectSuite

import kui.alerts.contract.AlertsEndpoints
import kui.alerts.domain.{AlertRule, RuleOutcome, RuleReport}
import kui.kernel.RoleName
import kui.kernel.error.InfrastructureError
import kui.security.rbac.*

/** The two routes, answered end to end: the interceptor chain, the principal check, the permission guard,
  * the use case, the store and the mapping onto the wire.
  */
final class AlertsRoutesSuite extends CatsEffectSuite {

  import AlertsTestServer.*

  /** A policy granting `operator` the named alerts actions on both clusters. */
  private def policy(actions: Action*): RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = operator,
          clusters = Set(cluster, readOnly),
          subjects = Nil,
          permissions = List(RbacPolicy.permission(Resource.Alerts, None, actions.toSet))
        )
      ),
      defaultRole = None
    )

  test("a feed with no events answers ok with an empty list") {
    resource().use(get(_, feedPath(cluster))).map { response =>
      val section = body(response).hcursor.downField("events")

      assertEquals(response.code.code, 200, response.body)
      assertEquals(section.get[String]("status"), Right("ok"))
      assertEquals(section.downField("data").get[List[String]]("items"), Right(Nil))
      assertEquals(section.downField("data").get[Int]("openCount"), Right(0))
    }
  }

  test("an acknowledgement by a principal without ALERTS:ACKNOWLEDGE is refused before the store is written") {
    // The order is the claim. `SecuredRoutes` runs the guard between "who is this" and "do the work", so a
    // caller holding only `VIEW` never reaches `AlertStore.acknowledge` at all — which is why the fixture
    // counts the calls the store was asked to make rather than reading the events back.
    val open = event(AlertRule.OfflinePartitions, "")

    resource(rbac = policy(Action.AlertsView), events = List(open)).use { rig =>
      for {
        response <- post(rig, acknowledgePath(cluster, open.id.value), Set(operator))
        attempts <- rig.store.attempts.get
      } yield {
        assertEquals(response.code.code, 403, response.body)
        assert(response.body.contains("KUI-FORBIDDEN"), response.body)
        assertEquals(attempts, Nil, clue = "the store was asked to write despite the refusal")
      }
    }
  }

  test("an acknowledgement by a principal holding ALERTS:ACKNOWLEDGE closes the event") {
    val open = event(AlertRule.OfflinePartitions, "")

    resource(rbac = policy(Action.AlertsAcknowledge, Action.AlertsView), events = List(open)).use { rig =>
      for {
        response <- post(rig, acknowledgePath(cluster, open.id.value), Set(operator))
        attempts <- rig.store.attempts.get
      } yield {
        assertEquals(response.code.code, 200, response.body)
        val event = body(response).hcursor.downField("event")
        assertEquals(event.downField("resolution").get[String]("kind"), Right("acknowledged"))
        assertEquals(body(response).hcursor.get[Int]("openCount"), Right(0))
        assertEquals(attempts.size, 1)
      }
    }
  }

  test("an acknowledgement on a read-only cluster is refused at the guard and writes nothing") {
    // Two layers refuse this and the outer one wins. `RbacGuard.fromPolicy` is given the deployment's
    // read-only flags and refuses an *altering* action on a read-only cluster before the route's logic
    // runs at all, so the caller sees `KUI-FORBIDDEN`; `MutationGuard`'s `KUI-READ-ONLY` is the refusal
    // behind it, asserted in `AcknowledgementSuite` where no permission guard stands in front. Both are
    // the same decision `Action.AlertsAcknowledge.isAlter` encodes (ADR-053 §1), and what matters here is
    // that neither of them reaches the store.
    val open = event(AlertRule.OfflinePartitions, "")

    resource(rbac = policy(Action.AlertsAcknowledge, Action.AlertsView), events = List(open)).use { rig =>
      for {
        response <- post(rig, acknowledgePath(readOnly, open.id.value), Set(operator))
        attempts <- rig.store.attempts.get
      } yield {
        assertEquals(response.code.code, 403, response.body)
        assertEquals(attempts, Nil)
      }
    }
  }

  test("an acknowledgement of an event that is already closed is a 409") {
    val open = event(AlertRule.OfflinePartitions, "")

    resource(rbac = policy(Action.AlertsAcknowledge, Action.AlertsView), events = List(open)).use { rig =>
      for {
        _ <- post(rig, acknowledgePath(cluster, open.id.value), Set(operator))
        again <- post(rig, acknowledgePath(cluster, open.id.value), Set(operator))
      } yield {
        assertEquals(again.code.code, 409, again.body)
        assert(again.body.contains("KUI-INVALID-STATE"), again.body)
      }
    }
  }

  test("an event id the service could never have issued is a 400 naming the parameter, not a 409") {
    resource(rbac = policy(Action.AlertsAcknowledge, Action.AlertsView)).use { rig =>
      for {
        response <- post(rig, acknowledgePath(cluster, "bad~id"), Set(operator))
        attempts <- rig.store.attempts.get
      } yield {
        assertEquals(response.code.code, 400, response.body)
        assert(response.body.contains(AlertsEndpoints.EventIdParam), response.body)
        assertEquals(attempts, Nil)
      }
    }
  }

  test("a cluster KUI has never heard of is a 404, because the caller followed a broken link") {
    resource().use(get(_, feedPath(unknown))).map { response =>
      assertEquals(response.code.code, 404, response.body)
      assert(response.body.contains("KUI-CLUSTER-NOT-FOUND"), response.body)
    }
  }

  test("a rule whose facts could not be read is an unavailable section, never a zero") {
    // The product's central promise, applied to its own alerting: "no offline partitions" and "KUI could
    // not count the partitions" are different sentences, and a card that drew a zero for the second would
    // be telling an operator that a cluster it cannot see is well.
    val blind = List(
      RuleReport(
        AlertRule.OfflinePartitions,
        RuleOutcome.NotEvaluated(InfrastructureError.Unreachable("kafka-admin", "connection refused"))
      )
    )

    resource(reports = blind).use(get(_, feedPath(cluster))).map { response =>
      val rules = body(response).hcursor.downField("events").downField("data").downField("rules")
      val offline = rules.downN(0)

      assertEquals(response.code.code, 200, response.body)
      assertEquals(offline.get[String]("rule"), Right("offline-partitions"))
      assertEquals(offline.downField("evaluation").get[String]("status"), Right("unavailable"))
      assertEquals(
        offline.downField("evaluation").get[String]("reason"),
        Right("UPSTREAM_UNAVAILABLE")
      )
    }
  }

  test("a rule the last pass never reported is starting, not ok with nothing found") {
    // Every rule has a row on every pass, so a rule that is missing from the report is a cluster that has
    // not been evaluated. `ok` with zero would be a clean bill of health nothing established.
    resource(reports = Nil, evaluatedAt = None).use(get(_, feedPath(cluster))).map { response =>
      val rules = body(response).hcursor.downField("events").downField("data").downField("rules")

      assertEquals(rules.downN(0).downField("evaluation").get[String]("status"), Right("unavailable"))
      assertEquals(rules.downN(0).downField("evaluation").get[String]("reason"), Right("STARTING"))
      assertEquals(
        body(response).hcursor.downField("events").downField("data").get[Option[String]]("evaluatedAt"),
        Right(None)
      )
    }
  }

  test("every rule has a row, in the order the vocabulary declares them") {
    resource().use(get(_, feedPath(cluster))).map { response =>
      val rules = body(response).hcursor
        .downField("events")
        .downField("data")
        .get[List[io.circe.Json]]("rules")
        .getOrElse(Nil)

      assertEquals(
        rules.flatMap(_.hcursor.get[String]("rule").toOption),
        AlertRule.All.map(_.wire)
      )
    }
  }

  test("a limit above the published maximum is refused rather than clamped") {
    // A request for a thousand that quietly answered two hundred would look like a cluster with fewer
    // events than it has, and an alerts feed that under-reports is the one screen that must not.
    resource().use(get(_, s"${feedPath(cluster)}?limit=${AlertsEndpoints.MaxLimit + 1}")).map { response =>
      assertEquals(response.code.code, 400, response.body)
    }
  }

  test("the published maximum is 200 and the default is 50") {
    // Literals, so that widening either is a change to this line rather than a silent change of behaviour.
    assertEquals(AlertsEndpoints.MaxLimit, 200)
    assertEquals(AlertsEndpoints.DefaultLimit, 50)
  }

  test("a limit smaller than the feed pages the rows and leaves the counts alone") {
    val events = List(
      event(AlertRule.OfflinePartitions, "", at),
      event(AlertRule.UnderReplicatedPartitions, "", at.plusSeconds(1)),
      event(AlertRule.DiskUsage, "broker-1:/var", at.plusSeconds(2))
    )

    resource(events = events).use(get(_, s"${feedPath(cluster)}?limit=1")).map { response =>
      val data = body(response).hcursor.downField("events").downField("data")

      assertEquals(data.get[List[io.circe.Json]]("items").map(_.size), Right(1))
      assertEquals(data.get[Int]("total"), Right(3))
      assertEquals(data.get[Int]("openCount"), Right(3))
    }
  }

  test("a request with no principal header is refused before any route runs") {
    resource()
      .use(rig =>
        sttp.client4.basicRequest
          .get(address(feedPath(cluster)))
          .response(sttp.client4.asStringAlways)
          .send(rig.backend)
      )
      .map(response => assertEquals(response.code.code, 401, response.body))
  }

  test("a principal holding no role at all is refused, however it reached this port") {
    val open = event(AlertRule.OfflinePartitions, "")

    resource(rbac = policy(Action.AlertsAcknowledge, Action.AlertsView), events = List(open)).use { rig =>
      post(rig, acknowledgePath(cluster, open.id.value), Set.empty[RoleName]).map { response =>
        assertEquals(response.code.code, 403, response.body)
      }
    }
  }
}
