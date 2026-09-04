package kui.ui.kernel.state

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite

import kui.kernel.{ClusterId, UserName}
import kui.security.rbac.*
import kui.security.{Principal, PrincipalKind}

/** The browser's half of E4: the store that holds what `/auth/me` said, and answers a screen's question by
  * running the *server's* evaluator.
  *
  * Every case here has a mirror in `libs/security-core`'s `RbacLawsSuite`, deliberately. What is being
  * checked is not the rule — that is tested once, where the rule lives — but that this store feeds the rule
  * the right inputs: the right cluster, the right resource name, the expanded action set the server sent.
  */
class PermissionStoreSuite extends FunSuite {

  private val Production: ClusterId = ClusterId.unsafe("production")
  private val Staging: ClusterId = ClusterId.unsafe("staging")

  private def pattern(raw: String): ResourcePattern =
    ResourcePattern.compile(raw).getOrElse(ResourcePattern.Everything)

  private def grant(scope: ClusterScope, resource: Resource, value: String, actions: Set[Action]) =
    ClusterPermission(scope, RbacPolicy.permission(resource, Some(pattern(value)), actions))

  /** Reads a signal's current value without mounting anything. */
  private def now(signal: Signal[Boolean]): Boolean = {
    val owner = new ManualOwner
    var seen = false
    val subscription = signal.foreach(value => seen = value)(using owner)
    subscription.kill()
    owner.killSubscriptions()
    seen
  }

  test("nothingIsPermittedBeforeAuthMeHasAnswered") {
    // The right way round. A control that starts enabled and disables itself a moment later can be
    // clicked in the gap, and the click goes to a server that will refuse it.
    val store = new Permissions

    assert(!now(store.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))
  }

  test("aWildcardGrantOverEveryClusterPermitsEverything") {
    // What a deployment with no roles configured sends. It must not read as "you may do nothing", or the
    // quickstart loses every write control it has.
    val store = new Permissions
    store.adopt(
      Resource.values.toList.map(resource =>
        ClusterPermission(
          ClusterScope.Every,
          RbacPolicy.allPermission(resource, Some(ResourcePattern.Everything))
        )
      )
    )

    assert(now(store.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))
    assert(now(store.allows(ClusterId.unsafe("added-later"), Resource.Topic, "any", Action.TopicCreate)))
  }

  test("aGrantIsScopedToItsClusters") {
    val store = new Permissions
    store.adopt(
      List(grant(ClusterScope.Named(Set(Staging)), Resource.Topic, ".*", Set(Action.TopicDelete)))
    )

    assert(now(store.allows(Staging, Resource.Topic, "orders", Action.TopicDelete)))
    assert(!now(store.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))
  }

  test("aGrantIsScopedToItsResourcePattern") {
    val store = new Permissions
    store.adopt(
      List(grant(ClusterScope.Every, Resource.Topic, "orders.*", Set(Action.TopicDelete)))
    )

    assert(now(store.allows(Production, Resource.Topic, "orders-dlq", Action.TopicDelete)))
    assert(!now(store.allows(Production, Resource.Topic, "payments", Action.TopicDelete)))
  }

  test("theServersExpansionIsWhatMakesViewFollowDelete") {
    // The server closes the action set before it sends it, so a grant of DELETE arrives carrying VIEW.
    // The browser never re-derives that — one implementation of the rule, on the side that decides.
    val store = new Permissions
    store.adopt(List(grant(ClusterScope.Every, Resource.Topic, ".*", Set(Action.TopicDelete))))

    assert(now(store.allows(Production, Resource.Topic, "orders", Action.TopicView)))
  }

  test("holdingOneActionDoesNotGrantAnUnrelatedOne") {
    val store = new Permissions
    store.adopt(List(grant(ClusterScope.Every, Resource.Topic, ".*", Set(Action.TopicView))))

    assert(now(store.allows(Production, Resource.Topic, "orders", Action.TopicView)))
    assert(!now(store.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))
  }

  test("anUnnamedResourceIsAskedAboutWithoutAName") {
    val store = new Permissions
    store.adopt(
      List(ClusterPermission(ClusterScope.Every, RbacPolicy.permission(Resource.Audit, None, Set(Action.AuditView))))
    )

    assert(now(store.allowsUnnamed(Production, Resource.Audit, Action.AuditView)))
  }

  test("everyPermissionIsWithdrawnWhenTheSessionExpires") {
    // The store is the session's, so an expiry has to take the controls away with it. A delete button
    // left enabled after a logout belongs to a session the server has already forgotten.
    val auth = new Auth
    auth.markSignedIn(
      AuthInfo(
        principal = Some(Principal(UserName.unsafe("ada"), Set.empty, PrincipalKind.Session)),
        csrfToken = Some("t"),
        authType = "basic",
        permissions = List(grant(ClusterScope.Every, Resource.Topic, ".*", Set(Action.TopicDelete)))
      )
    )

    assert(now(auth.permissions.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))

    auth.markExpired()

    assert(!now(auth.permissions.allows(Production, Resource.Topic, "orders", Action.TopicDelete)))
  }

  test("aListIsFilteredRatherThanEmptied") {
    val store = new Permissions
    store.adopt(List(grant(ClusterScope.Every, Resource.Topic, "orders.*", Set(Action.TopicView))))

    val owner = new ManualOwner
    var shown: List[String] = Nil
    val subscription = store
      .visible(Production, Resource.Topic, Action.TopicView)(Val(List("orders", "orders-dlq", "payments")))(
        identity
      )
      .foreach(value => shown = value)(using owner)
    subscription.kill()
    owner.killSubscriptions()

    assertEquals(shown, List("orders", "orders-dlq"))
  }
}
