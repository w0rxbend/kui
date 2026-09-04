package kui.security.rbac

import kui.kernel.{ClusterId, RoleName}

/** Where a role's subjects came from, so that "the group `platform`" from GitHub and "the group `platform`"
  * from LDAP are not the same grant.
  */
enum Provider(val wire: String) {

  /** KUI's own login form, whose accounts live in `kui.auth.users[]`.
    *
    * Kafbat has no such provider, because its form mode uses Spring's single built-in account and cannot
    * assign roles at all. KUI's form mode gives each account a `groups` list precisely so that the smallest
    * deployment — three named people and no directory server — can still use the same role file as a large
    * one, and a role's subjects have to be able to say which source a group name came from.
    */
  case Form extends Provider("FORM")

  case OauthGoogle extends Provider("OAUTH_GOOGLE")
  case OauthGithub extends Provider("OAUTH_GITHUB")
  case OauthCognito extends Provider("OAUTH_COGNITO")
  case Oauth extends Provider("OAUTH")
  case Ldap extends Provider("LDAP")
  case LdapAd extends Provider("LDAP_AD")
}

object Provider {
  def fromWire(raw: String): Option[Provider] = values.find(_.wire == raw.trim.toUpperCase)
  given CanEqual[Provider, Provider] = CanEqual.derived
}

/** Which attribute of an identity a subject is about.
  *
  * Not every provider offers every kind: `organization` and `team` are GitHub's, `domain` is Google's `hd`
  * claim, `role` is Cognito's and AD's. A subject naming a kind its provider does not produce simply never
  * matches, which is the honest outcome — the alternative, refusing the configuration, would make a single
  * shared role file unusable across two providers.
  */
enum SubjectKind(val wire: String) {
  case User extends SubjectKind("user")
  case Group extends SubjectKind("group")
  case Domain extends SubjectKind("domain")
  case Organization extends SubjectKind("organization")
  case Team extends SubjectKind("team")
  case Role extends SubjectKind("role")
}

object SubjectKind {
  def fromWire(raw: String): Option[SubjectKind] = values.find(_.wire == raw.trim.toLowerCase)
  given CanEqual[SubjectKind, SubjectKind] = CanEqual.derived
}

/** One rule for "who is in this role".
  *
  * @param isRegex
  *   when false the comparison is case-insensitive equality, which is what almost every deployment wants and
  *   what a login name comparison has to be. When true, `value` is a full-match regular expression.
  */
final case class Subject(provider: Provider, kind: SubjectKind, value: String, isRegex: Boolean) {

  /** The compiled form of `value`, or `None` when this subject is not a regular expression — and also when it
    * is one that does not compile, in which case it matches nothing rather than throwing at login time.
    */
  private lazy val pattern: Option[ResourcePattern] =
    if isRegex then ResourcePattern.compile(value).toOption else None

  def matches(candidate: String): Boolean =
    if isRegex then pattern.exists(_.matches(candidate))
    else value.equalsIgnoreCase(candidate)
}

object Subject {
  given CanEqual[Subject, Subject] = CanEqual.derived
}

/** What one identity provider said about the person signing in: their login name, their groups, their
  * organisations.
  *
  * A map from attribute kind to values because that is exactly the shape a subject is matched against, and
  * because it lets one extractor per provider (RB-002) be written without any of them knowing about roles.
  */
final case class IdentityAttributes(values: Map[SubjectKind, Set[String]]) {
  def of(kind: SubjectKind): Set[String] = values.getOrElse(kind, Set.empty)
}

object IdentityAttributes {
  val Empty: IdentityAttributes = IdentityAttributes(Map.empty)
  given CanEqual[IdentityAttributes, IdentityAttributes] = CanEqual.derived
}

/** A grant: these actions, on this resource, on the resources whose name matches this pattern.
  *
  * `actions` is **already closed** under [[Action.directlyImplies]] — [[RbacPolicy.permission]] is the only
  * constructor that should be used to build one from configuration, and it closes the set. Storing the
  * expanded set rather than expanding on every check is what lets the browser evaluate the same rule from the
  * same list without re-deriving the closure in another language.
  *
  * `value` of `None` means "the unnamed one". A permission over `AUDIT` names nothing because there is one
  * audit trail; a permission over `TOPIC` with no pattern therefore grants nothing, because every topic
  * access names a topic. That asymmetry is Kafbat's and is deliberate: it makes a forgotten `value` deny
  * rather than grant.
  */
final case class Permission(resource: Resource, value: Option[ResourcePattern], actions: Set[Action]) {

  /** Whether this permission is about the thing being accessed at all — same resource, and a name that
    * matches.
    */
  def covers(resource: Resource, name: Option[String]): Boolean =
    this.resource == resource && ((value, name) match {
      case (None, None) => true
      case (Some(pattern), Some(candidate)) => pattern.matches(candidate)
      case _ => false
    })
}

object Permission {
  given CanEqual[Permission, Permission] = CanEqual.derived
}

/** A role as an operator writes it: a name, the clusters it applies to, who is in it, and what it grants. */
final case class Role(
    name: RoleName,
    clusters: Set[ClusterId],
    subjects: List[Subject],
    permissions: List[Permission]
) {

  /** Whether the identity attributes gathered at login put this person in this role. */
  def admits(identity: Map[Provider, IdentityAttributes]): Boolean =
    subjects.exists(subject =>
      identity.get(subject.provider).exists(_.of(subject.kind).exists(subject.matches))
    )
}

object Role {
  given CanEqual[Role, Role] = CanEqual.derived
}

/** The permissions everyone gets when they match no role at all.
  *
  * It has no clusters: Kafbat applies it to every cluster, and a default role scoped to some clusters would
  * be a role, not a default.
  */
final case class DefaultRole(permissions: List[Permission])

object DefaultRole {
  given CanEqual[DefaultRole, DefaultRole] = CanEqual.derived
}

/** Facts about a cluster that change what may be done to it regardless of who is asking.
  *
  * Read-only lives here rather than in the policy because it is a property of the deployment's connection to
  * that cluster, not of anybody's role — an administrator with every permission still may not write to a
  * cluster the operator marked read-only, and that is the point of the flag.
  */
final case class ClusterFlags(readOnly: Boolean)

object ClusterFlags {
  val Writable: ClusterFlags = ClusterFlags(readOnly = false)
  given CanEqual[ClusterFlags, ClusterFlags] = CanEqual.derived
}

/** Everything an authorization decision reads, built once at start-up and never mutated.
  *
  * RBAC is **on** exactly when there is at least one role or a default role, which is Kafbat's rule. A
  * deployment with no roles configured is not a deployment where everything is denied — it is a deployment
  * that has not asked for authorization, and denying everything there would break the quickstart on the day
  * this type was introduced.
  */
final case class RbacPolicy(roles: List[Role], defaultRole: Option[DefaultRole]) {

  val enabled: Boolean = roles.nonEmpty || defaultRole.isDefined

  /** The roles this identity is in, by name. */
  def rolesFor(identity: Map[Provider, IdentityAttributes]): Set[RoleName] =
    roles.filter(_.admits(identity)).map(_.name).toSet

  /** The role definitions behind a principal's role names.
    *
    * By name rather than by re-running subject matching: subject matching happens once, at login, and the
    * result travels in the signed principal (ADR-020). A service re-running it would need the identity
    * provider's attributes, which it does not have and must not need.
    */
  def held(roleNames: Set[RoleName]): List[Role] = roles.filter(role => roleNames.contains(role.name))
}

object RbacPolicy {

  /** The policy of a deployment that has not configured RBAC. Every decision it takes is `Allowed`, except
    * the ones a read-only cluster refuses.
    */
  val Disabled: RbacPolicy = RbacPolicy(List.empty, None)

  /** Builds a permission with its action set closed, which is the only correct way to build one.
    *
    * `Set(Action.TopicDelete)` written by hand into a `Permission` would grant a delete to somebody who
    * cannot list the topic, and every screen would then show them an empty list with a delete button on it.
    */
  def permission(resource: Resource, value: Option[ResourcePattern], actions: Set[Action]): Permission =
    Permission(resource, value, Action.closure(actions.filter(_.resource == resource)))

  /** The `ALL` of a configuration file: every action the resource has, closed (which changes nothing, since a
    * full set is already closed) and kept as one expression so `ALL` cannot drift from `allActions`.
    */
  def allPermission(resource: Resource, value: Option[ResourcePattern]): Permission =
    permission(resource, value, resource.allActions)

  given CanEqual[RbacPolicy, RbacPolicy] = CanEqual.derived
}
