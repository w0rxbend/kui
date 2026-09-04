package kui.security.rbac

import org.scalacheck.{Arbitrary, Gen}

import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.{Principal, PrincipalKind}

/** Generators for the RBAC laws.
  *
  * They are deliberately small — three clusters, a handful of resource names — because the properties being
  * checked are about the *shape* of an evaluation and a generator with a thousand distinct cluster ids would
  * make every interesting overlap vanishingly unlikely. A property that never generates two roles naming the
  * same cluster is a property that never tests the union.
  */
object RbacGenerators {

  val clusters: List[ClusterId] =
    List("local", "staging", "production").map(ClusterId.unsafe)

  val resourceNames: List[String] =
    List("orders", "orders-dlq", "payments", "audit-log")

  val cluster: Gen[ClusterId] = Gen.oneOf(clusters)

  val resource: Gen[Resource] = Gen.oneOf(Resource.values.toList)

  def action(of: Resource): Gen[Action] = Gen.oneOf(of.allActions.toList)

  val anyAction: Gen[Action] = Gen.oneOf(Action.values.toList)

  /** A pattern that either names one resource exactly or covers a family of them. Never a hand-written
    * regular expression with a syntax error: the laws are about evaluation, and `compile` has its own suite.
    */
  val pattern: Gen[ResourcePattern] =
    Gen
      .oneOf(resourceNames.map(name => name) ++ List("orders.*", ".*"))
      .map(raw => ResourcePattern.compile(raw).getOrElse(ResourcePattern.Everything))

  /** A permission built through `RbacPolicy.permission`, so its action set is always closed — which is the
    * invariant every other property depends on.
    */
  val permission: Gen[Permission] =
    for {
      target <- resource
      actions <- Gen.nonEmptyListOf(action(target))
      value <- Gen.option(pattern)
    } yield RbacPolicy.permission(target, value, actions.toSet)

  val roleName: Gen[RoleName] =
    Gen.oneOf("readers", "editors", "admins", "auditors").map(RoleName.unsafe)

  val subject: Gen[Subject] =
    for {
      provider <- Gen.oneOf(Provider.values.toList)
      kind <- Gen.oneOf(SubjectKind.values.toList)
      value <- Gen.oneOf("alice", "bob", "platform", "example.com")
    } yield Subject(provider, kind, value, isRegex = false)

  val role: Gen[Role] =
    for {
      name <- roleName
      named <- Gen.nonEmptyListOf(cluster)
      subjects <- Gen.nonEmptyListOf(subject)
      permissions <- Gen.nonEmptyListOf(permission)
    } yield Role(name, named.toSet, subjects, permissions)

  /** A policy that is switched on: at least one role, so `enabled` is true and the gates actually run. */
  val enabledPolicy: Gen[RbacPolicy] =
    for {
      roles <- Gen.nonEmptyListOf(role)
      default <- Gen.option(Gen.nonEmptyListOf(permission).map(DefaultRole.apply))
    } yield RbacPolicy(roles, default)

  /** A principal that holds some subset of the policy's role names, including possibly none of them. */
  def principalIn(policy: RbacPolicy): Gen[Principal] =
    Gen
      .someOf(policy.roles.map(_.name).distinct)
      .map(held => Principal(UserName.unsafe("tester"), held.toSet, PrincipalKind.Session))

  val access: Gen[ResourceAccess] =
    for {
      target <- resource
      actions <- Gen.nonEmptyListOf(action(target))
      name <- Gen.option(Gen.oneOf(resourceNames))
    } yield ResourceAccess(target, name, actions.toSet, None)

  val request: Gen[AccessRequest] =
    for {
      target <- Gen.option(cluster)
      accesses <- Gen.nonEmptyListOf(access)
    } yield AccessRequest(target, accesses, OperationName("law"))

  given Arbitrary[Action] = Arbitrary(anyAction)
  given Arbitrary[Permission] = Arbitrary(permission)
  given Arbitrary[AccessRequest] = Arbitrary(request)
}
