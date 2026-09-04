package kui.ui.kernel.state

import com.raquo.laminar.api.L.*

import kui.kernel.{ClusterId, RoleName, UserName}
import kui.security.rbac.*
import kui.security.{Principal, PrincipalKind}

/** What the signed-in user is allowed to do, and the one place a screen asks.
  *
  * ## Why the browser decides this at all
  *
  * It does not, really — the server decides, and re-decides on every request. What the browser does is
  * *predict* the server's answer so it can hide a control rather than offer one that will be refused. A
  * button that is offered and then refused teaches an operator that KUI's refusals are bugs, and the next
  * real refusal is not believed.
  *
  * A prediction is only worth making if it is right every time, which is why this store does not implement
  * any rule of its own. It holds the permission list `GET /api/v1/auth/me` returned — already expanded by the
  * server, so `DELETE` already carries the `VIEW` it implies — and it answers every question by calling
  * `kui.security.rbac.Rbac.decide`, the same function the services call. `libs/security-core` is
  * cross-compiled precisely so that this is possible: there is one evaluator, and both sides run it.
  *
  * ## What a screen does with it
  *
  * One call, into `ActionPermissionWrapper`'s `permitted` parameter:
  *
  * {{{
  * ActionPermissionWrapper(
  *   action = deleteButton,
  *   capability = topicAdminState,
  *   permitted = PermissionStore.current.allows(cluster, Resource.Topic, topic.value, Action.TopicDelete)
  * )
  * }}}
  *
  * The wrapper merges this with the capability state and renders one tooltip listing every reason the control
  * is unavailable — see its own documentation for why the two reasons share one wrapper.
  *
  * ## Before `/auth/me` has answered
  *
  * The list is empty and every question answers `false`, so a write control starts disabled and becomes
  * enabled a moment later. That is the right way round: a control that starts enabled and disables itself can
  * be clicked in the gap, and the click goes to a server that will refuse it.
  *
  * ## Why this is a class as well as an object
  *
  * The application has exactly one permission set, and [[PermissionStore.current]] is it. A test needs
  * several, with no leakage between them, and a hard-wired singleton cannot give it that — the same
  * arrangement [[Auth]] and `Theme` already use.
  */
final class Permissions {

  /** The grants the last `/auth/me` returned, exactly as the server computed them. */
  val granted: Var[List[ClusterPermission]] = Var(List.empty)

  /** Replaces the whole set. Called by [[Auth.markSignedIn]]; nothing else should call it. */
  def adopt(permissions: List[ClusterPermission]): Unit = granted.set(permissions)

  /** Whether this principal may take this action on this named resource, right now.
    *
    * A `Signal` rather than a `Boolean` because the answer changes: the session is re-established, a role is
    * revoked, the user switches cluster. Every consumer binds to it and the control follows.
    *
    * @param cluster
    *   which cluster the resource is on. A grant is scoped to clusters, so the same topic name can be
    *   deletable on one cluster and not on another — which is the whole point of scoping a role.
    * @param name
    *   the resource's name: a topic name, a group id. Use [[allowsUnnamed]] for the resources that have none.
    */
  def allows(
      cluster: ClusterId,
      resource: Resource,
      name: String,
      action: Action
  ): Signal[Boolean] =
    decide(cluster, ResourceAccess.named(resource, name, action))

  /** The same question about a resource that has no name: the audit trail, ksqlDB, the ACL list. */
  def allowsUnnamed(cluster: ClusterId, resource: Resource, action: Action): Signal[Boolean] =
    decide(cluster, ResourceAccess.unnamed(resource, action))

  /** Whether the user holds this action on **some** resource of this kind, whatever its name.
    *
    * The question a create asks. A new topic's name does not exist yet, so there is nothing to match a
    * grant's pattern against, and neither [[allows]] (which needs a name) nor [[allowsUnnamed]] (which is for
    * resources that never have one) answers it.
    *
    * This is deliberately the *same* weakening the gateway applies to an endpoint whose resource is named
    * only in the request body (`EndpointDecision.bodyNamedGate`): somebody with no topic grant at all may not
    * reach the create endpoint, and somebody whose grant is `payments\..*` may — and is then refused by the
    * owning service, with the name in hand, if they ask for `orders`. Matching the server's rule exactly is
    * the point: a stricter browser hides a control the server would have allowed, and a looser one offers a
    * control the server refuses.
    */
  def allowsAny(cluster: ClusterId, resource: Resource, action: Action): Signal[Boolean] =
    granted.signal.map { permissions =>
      Rbac
        .effectivePermissions(policyOf(permissions, cluster), Permissions.Holder, Some(cluster))
        .exists(permission => permission.resource == resource && permission.actions.contains(action))
    }

  // A deployment with no authorization configured needs no special case here. The gateway answers
  // `/auth/me` with the *whole* vocabulary granted over every cluster in that case, so this store is
  // holding a full grant list rather than an empty one, exactly as `allows` already relies on.

  /** The subset of `items` the user may see, for a list a screen renders.
    *
    * List screens filter rather than refuse, which is what the server does too: somebody who may see three of
    * a hundred topics should see three topics, not an error. Hiding the other ninety-seven is also what stops
    * the list from leaking that they exist.
    */
  def visible[A](cluster: ClusterId, resource: Resource, action: Action)(
      items: Signal[List[A]]
  )(nameOf: A => String): Signal[List[A]] =
    items
      .combineWith(granted.signal)
      .map { (all, permissions) =>
        Rbac.visible(
          policyOf(permissions, cluster),
          Permissions.Holder,
          ClusterFlags.Writable,
          Some(cluster),
          resource,
          action
        )(all)(nameOf)
      }

  private def decide(cluster: ClusterId, access: ResourceAccess): Signal[Boolean] =
    granted.signal.map { permissions =>
      Rbac
        .decide(
          policyOf(permissions, cluster),
          Permissions.Holder,
          // The browser is not told which clusters are read-only through this channel, and it must not
          // guess: a read-only cluster's controls are disabled by the *capability* half of
          // `ActionPermissionWrapper`, which is fed from the capability document that does say so. Two
          // sources for one fact is two ways to get it wrong, and this is the one that does not know.
          ClusterFlags.Writable,
          AccessRequest(Some(cluster), List(access), OperationName("ui"))
        )
        .isAllowed
    }

  /** The permission list, rebuilt into the shape [[Rbac.decide]] takes.
    *
    * The server sends a flat list of grants because that is what it is; `decide` reads a policy of roles,
    * because on the server a role is where cluster scoping lives. So the grants that apply to this cluster
    * become the permissions of one synthetic role, and [[Holder]] is the principal that holds it.
    *
    * This is a re-shaping and not a re-decision: no rule is applied here that the server does not apply, and
    * the actions in each grant are the ones the server already expanded.
    */
  private def policyOf(permissions: List[ClusterPermission], cluster: ClusterId): RbacPolicy =
    RbacPolicy(
      List(
        Role(
          Permissions.HolderRole,
          Set(cluster),
          Nil,
          permissions.filter(_.clusters.includes(cluster)).map(_.permission)
        )
      ),
      None
    )
}

object Permissions {

  /** The name of the synthetic role the browser's grants are put into. It never leaves this file and never
    * reaches the server; it exists because `Rbac.decide` reads a policy, and a policy is made of roles.
    */
  val HolderRole: RoleName = RoleName.unsafe("kui-ui-permissions")

  /** The principal that holds it.
    *
    * Deliberately not the signed-in user. Who the user *is* was already used, on the server, to compute the
    * grants this store was handed; re-deriving anything from the name here would be a second decision, and a
    * second decision is a second thing that can be wrong.
    */
  val Holder: Principal =
    Principal(UserName.unsafe("kui-ui"), Set(HolderRole), PrincipalKind.Session)
}

/** The application's one permission set, for code that is not under test.
  *
  * It is [[AuthState]]'s, deliberately, and not a second instance: permissions arrive with the identity, and
  * two stores would be two answers to one question the moment a session was re-established.
  */
object PermissionStore {

  def current: Permissions = AuthState.current.permissions

  def granted: Var[List[ClusterPermission]] = current.granted

  def allows(cluster: ClusterId, resource: Resource, name: String, action: Action): Signal[Boolean] =
    current.allows(cluster, resource, name, action)

  def allowsUnnamed(cluster: ClusterId, resource: Resource, action: Action): Signal[Boolean] =
    current.allowsUnnamed(cluster, resource, action)

  def allowsAny(cluster: ClusterId, resource: Resource, action: Action): Signal[Boolean] =
    current.allowsAny(cluster, resource, action)
}
