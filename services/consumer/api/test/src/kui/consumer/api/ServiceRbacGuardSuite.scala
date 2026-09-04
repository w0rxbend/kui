package kui.consumer.api

import cats.effect.IO
import sttp.client4.*

import kui.kernel.RoleName
import kui.security.rbac.*
import kui.testkit.KuiIOSuite

/** The consumer service refusing on its own account.
  *
  * ==Why this suite exists at all==
  *
  * The gateway already checks permissions, on the same declaration this service reads, so it is fair to ask
  * what a second check buys. The answer is the case every request below constructs: a call that never went
  * through the gateway. A KUI service listens on its own port and trusts a signed principal; anything on the
  * same network that can reach that port and hold a signing key can call it. If the only check lived at the
  * edge, the consumer service would be open to whatever else runs beside it — which is precisely what
  * ADR-021 says must not be true of any service.
  *
  * So every request here goes straight to this service's own routes, with a valid principal and no gateway
  * anywhere. That is the shape of the attack the second check exists for, and it is the only shape in which
  * the second check can be observed at all.
  */
final class ServiceRbacGuardSuite extends KuiIOSuite {

  import ConsumerTestServer.{Cluster, Group, path, resource, token, uri}

  private val reader: RoleName = RoleName.unsafe("reader")

  /** A policy granting `reader` the named actions over groups matching `pattern`, on this cluster only. */
  private def policy(pattern: String, actions: Action*): RbacPolicy =
    RbacPolicy(
      roles = List(
        Role(
          name = reader,
          clusters = Set(Cluster),
          subjects = Nil,
          permissions = List(
            RbacPolicy.permission(
              Resource.ConsumerGroup,
              Some(ResourcePattern.compile(pattern).getOrElse(fail(s"'$pattern' does not compile"))),
              actions.toSet
            )
          )
        )
      ),
      defaultRole = None
    )

  private def get(
      server: ConsumerTestServer,
      at: String,
      roles: Set[RoleName]
  ): IO[Response[Either[String, String]]] =
    token(at, roles = roles).flatMap(principal =>
      basicRequest
        .get(uri"${uri(at)}")
        .header(kui.contracts.KuiEndpoint.PrincipalHeader, principal.value)
        .send(server.backend)
    )

  test("a group the caller's pattern does not match is refused by the service itself") {
    resource(rbac = policy("order-.*", Action.ConsumerGroupView)).use { (server, _) =>
      get(server, path("/consumer-groups/payroll-nightly"), Set(reader)).map { response =>
        val body = response.body.fold(identity, identity)
        assertEquals(response.code.code, 403, body)
        assert(body.contains("KUI-FORBIDDEN"), body)
        // The refusal says nothing about which pattern was checked; a caller learns that they may not,
        // not what the rule is.
        assert(!body.contains("order-"), s"the refusal leaked the pattern: $body")
      }
    }
  }

  test("a group the caller's pattern does match is served") {
    resource(rbac = policy("order-.*", Action.ConsumerGroupView)).use { (server, _) =>
      get(server, path(s"/consumer-groups/${Group.value}"), Set(reader)).map { response =>
        assertEquals(response.code.code, 200, response.body.fold(identity, identity))
      }
    }
  }

  test("a valid principal holding no role at all is refused, however it reached this port") {
    resource(rbac = policy("order-.*", Action.ConsumerGroupView)).use { (server, _) =>
      get(server, path(s"/consumer-groups/${Group.value}"), Set.empty).map { response =>
        assertEquals(response.code.code, 403, response.body.fold(identity, identity))
      }
    }
  }

  test("a list endpoint is reachable by anyone who may reach the cluster, and filters instead of refusing") {
    // ADR-021's rule: a 403 on a list would tell a caller that groups they may not see exist.
    resource(rbac = policy("order-.*", Action.ConsumerGroupView)).use { (server, _) =>
      get(server, path("/consumer-groups"), Set(reader)).map { response =>
        assertEquals(response.code.code, 200, response.body.fold(identity, identity))
      }
    }
  }

  test("a deployment with no roles configured is unaffected") {
    resource().use { (server, _) =>
      get(server, path(s"/consumer-groups/${Group.value}"), Set.empty).map { response =>
        assertEquals(response.code.code, 200, response.body.fold(identity, identity))
      }
    }
  }
}
