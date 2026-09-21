package kui.security.rbac

import munit.FunSuite

import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.{Principal, PrincipalKind}

/** The asymmetry in [[Permission.covers]]: a `value` and a `name` that do not pair off deny.
  *
  * ==Why this suite exists==
  *
  * `Permission.covers` ends in `case _ => false`, and `Vocabulary.scala:74-81` argues for that line at
  * length: *"a permission with no pattern matches only an unnamed access — so a `TOPIC` permission with no
  * `value` grants nothing at all. That is the correct evaluation and a terrible silence at configuration
  * time."* Nothing asserted it. Replacing that one line with `case _ => true` reddened nothing anywhere:
  * `./mill -k services.gateway.__.test + services.message.api.test + services.topic.api.test` stayed green at
  * 482 cases over 54 suites, `RbacLawsSuite` stayed green in `libs.securityCore.jvm.test`, and no route or
  * guard suite in `services/cluster`, `services/consumer` or `services/schema` noticed either.
  *
  * The mutation is a fail-open: with it, a role whose `TOPIC` permission has no `value` grants **every**
  * topic, and a role whose `AUDIT` permission carries a leftover `value` grants the audit trail it was scoped
  * away from.
  *
  * Both directions are here on purpose. A suite that only asserted the two refusals would also pass against a
  * `covers` that returned `false` for everything, which would deny the whole product.
  */
final class PermissionCoverageSuite extends FunSuite {

  private val Cluster: ClusterId = ClusterId.unsafe("production")

  private val everything: ResourcePattern = ResourcePattern.Everything

  private def pattern(raw: String): ResourcePattern =
    ResourcePattern.compile(raw).getOrElse(fail(s"'$raw' must compile"))

  private def user(roles: RoleName*): Principal =
    Principal(UserName.unsafe("tester"), roles.toSet, PrincipalKind.Session)

  private def roleOf(permissions: Permission*): Role =
    Role(
      RoleName.unsafe("testers"),
      Set(Cluster),
      List(Subject(Provider.Oauth, SubjectKind.User, "tester", isRegex = false)),
      permissions.toList
    )

  private def decide(permissions: List[Permission], access: ResourceAccess): Decision =
    Rbac.decide(
      RbacPolicy(List(roleOf(permissions*)), None),
      user(RoleName.unsafe("testers")),
      ClusterFlags.Writable,
      AccessRequest(Cluster, "coverage", access)
    )

  // -- the two refusals the `case _ => false` line is ------------------------------------------------

  test("aPermissionOverANamedResourceWithNoPatternCoversNothing") {
    // The forgotten `value`. `Resource.Topic.isNamed` is true, so every topic access carries a name, and a
    // permission with no pattern has nothing to compare it against. Granting instead would hand somebody
    // every topic in the cluster on the strength of a line they under-wrote.
    val granted = RbacPolicy.permission(Resource.Topic, None, Set(Action.TopicView))

    assert(!granted.covers(Resource.Topic, Some("orders")))
    assert(!granted.covers(Resource.Topic, Some("payments-internal")))
  }

  test("aPermissionCarryingAPatternDoesNotCoverAnUnnamedAccess") {
    // The mirror image, and the one a configuration file produces by copy-and-paste: `AUDIT` names nothing,
    // so a `value` on it is meaningless. Reading it as "matches anything" would turn a scoping mistake into
    // a grant over the one resource that records every other grant being used.
    val granted = RbacPolicy.permission(Resource.Audit, Some(pattern("staging-.*")), Set(Action.AuditView))

    assert(!granted.covers(Resource.Audit, None))
  }

  // -- the two grants, so the suite cannot pass by refusing everything -------------------------------

  test("anUnnamedPermissionCoversAnUnnamedAccessAndAPatternCoversAMatchingName") {
    val audit = RbacPolicy.permission(Resource.Audit, None, Set(Action.AuditView))
    val topics = RbacPolicy.permission(Resource.Topic, Some(pattern("orders.*")), Set(Action.TopicView))

    assert(audit.covers(Resource.Audit, None))
    assert(topics.covers(Resource.Topic, Some("orders-dlq")))
    // Still a full match, not a search: the pattern above is the one that widens it.
    assert(
      !RbacPolicy
        .permission(Resource.Topic, Some(pattern("orders")), Set(Action.TopicView))
        .covers(Resource.Topic, Some("orders-dlq"))
    )
  }

  test("aPermissionOfAnotherResourceNeverCoversThisOne") {
    val granted = RbacPolicy.permission(Resource.Topic, Some(everything), Set(Action.TopicView))

    assert(!granted.covers(Resource.ConsumerGroup, Some("orders-consumer")))
  }

  // -- the same rule through the evaluator, which is where a fail-open would be felt ------------------

  test("aTopicPermissionWithNoValueDeniesEveryTopicThroughDecide") {
    // `covers` is `private`-in-spirit but public in fact; this is the same question asked the way the
    // gateway and every service's own guard ask it, so the gate holds even if the evaluator stops calling
    // `covers` directly.
    val decision =
      decide(
        List(RbacPolicy.permission(Resource.Topic, None, Set(Action.TopicView))),
        ResourceAccess.named(Resource.Topic, "orders", Action.TopicView)
      )

    assertEquals(
      decision,
      Decision.Denied(DenialReason.MissingActions(Resource.Topic, Some("orders"), Set(Action.TopicView)))
    )
  }

  test("theSameRoleWithAPatternIsAllowed") {
    val decision =
      decide(
        List(RbacPolicy.permission(Resource.Topic, Some(pattern("orders")), Set(Action.TopicView))),
        ResourceAccess.named(Resource.Topic, "orders", Action.TopicView)
      )

    assertEquals(decision, Decision.Allowed)
  }

  test("anAuditPermissionScopedToAPatternDeniesTheAuditTrailThroughDecide") {
    val decision =
      decide(
        List(RbacPolicy.permission(Resource.Audit, Some(everything), Set(Action.AuditView))),
        ResourceAccess.unnamed(Resource.Audit, Action.AuditView)
      )

    assert(!decision.isAllowed, s"a pattern on an unnamed resource granted it: $decision")
  }
}
