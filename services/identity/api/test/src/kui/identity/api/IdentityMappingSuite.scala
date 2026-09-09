package kui.identity.api

import munit.FunSuite

import kui.identity.application.LoginResult
import kui.identity.contract.dto.LoginResponse
import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.rbac.{Action, ClusterPermission, ClusterScope, Permission, Resource, ResourcePattern}
import kui.security.{Principal, PrincipalKind}

/** That an identity answer is the same document twice, and that a challenge is all a forced change gets.
  *
  * `IdentityMapping` is the only place in this service where a domain value becomes something a browser can
  * read, and its three `sorted` calls each carry a paragraph saying why: an unsorted set makes the response
  * change from request to request for no reason, which defeats every cache and makes a golden-file test
  * impossible to write. Nothing asserted any of the three. Replacing each `.sorted` with `.sorted.reverse`
  * left `services.identity.__.test + apps.allinone.test` at 98/98 green, measured one mutation at a time —
  * and this file is the first test source `services.identity.api.test` has ever had, which is why: the
  * module was declared in `build.mill` and shipped nothing to run.
  *
  * The sets below are built in an order that is not their sorted order, so a mapping that merely passed the
  * set through would answer something else.
  */
final class IdentityMappingSuite extends FunSuite {

  private val who: Principal =
    Principal(
      UserName.unsafe("dana"),
      Set(RoleName.unsafe("topic-writer"), RoleName.unsafe("auditor"), RoleName.unsafe("readonly")),
      PrincipalKind.Session
    )

  test("a principal's roles reach the wire in one order, whatever order the set is in") {
    val roles = IdentityMapping.principal(who).roles

    assertEquals(roles, List("auditor", "readonly", "topic-writer"))
    // Stated separately from the list above, because the list is also what a reader would write down by
    // hand from the fixture; this is the property the comment in the mapping actually argues for.
    assertEquals(roles, roles.sorted)
    assertEquals(IdentityMapping.principal(who), IdentityMapping.principal(who))
  }

  test("the principal's kind travels as its wire spelling, so a session is not an anonymous request") {
    assertEquals(IdentityMapping.principal(who).kind, PrincipalKind.Session.wire)
    assertEquals(IdentityMapping.principal(who).name, "dana")
  }

  test("a grant's clusters and actions are both sorted, so two answers about one role are identical") {
    val granted = ClusterPermission(
      clusters = ClusterScope.Named(Set(ClusterId.unsafe("staging"), ClusterId.unsafe("prod"))),
      permission = Permission(
        resource = Resource.Topic,
        value = Some(ResourcePattern.Everything),
        actions = Set(Action.TopicView, Action.TopicDelete, Action.TopicCreate)
      )
    )

    val dto = IdentityMapping.grant(granted)

    assertEquals(dto.clusters, List("prod", "staging"))
    assertEquals(dto.clusters, dto.clusters.sorted)
    assertEquals(dto.actions, List("CREATE", "DELETE", "VIEW"))
    assertEquals(dto.actions, dto.actions.sorted)
    assertEquals(dto.resource, Resource.Topic.wire)
    assertEquals(dto.value, Some(ResourcePattern.Everything.raw))
  }

  test("a grant over every cluster travels as the wildcard and not as an empty list") {
    val granted = ClusterPermission(
      clusters = ClusterScope.Every,
      permission = Permission(Resource.Topic, None, Set(Action.TopicView))
    )

    // An empty list here would read as "no clusters", which is the opposite of what `Every` means.
    assertEquals(IdentityMapping.grant(granted).clusters, List(ClusterScope.EveryWire))
  }

  test("a forced password change carries the challenge and no principal at all") {
    IdentityMapping.login(LoginResult.MustChangePassword(kui.kernel.Secret("a-challenge"))) match {
      case LoginResponse.PasswordChangeRequired(challenge) => assertEquals(challenge, "a-challenge")
      case other => fail(s"a forced change must not answer with a principal: $other")
    }
  }

  test("a completed sign-in carries the principal") {
    IdentityMapping.login(LoginResult.SignedIn(who)) match {
      case LoginResponse.SignedIn(principal) => assertEquals(principal.name, "dana")
      case other => fail(s"a signed-in result must carry its principal: $other")
    }
  }
}
