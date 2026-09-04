package kui.config

import cats.syntax.all.*

import kui.config.ConfigReader.{Indices, Lookup, Problems}
import kui.kernel.{ClusterId, RoleName}
import kui.security.rbac.*

/** Reading `kui.rbac` into the `RbacPolicy` the evaluator already knows how to apply (RB-001).
  *
  * ==Why the configuration produces the domain type directly==
  *
  * `libs/security-core` owns the role model as pure data and pure functions, and it is deliberately unaware
  * of where a policy came from. The alternative to building an `RbacPolicy` here would be a parallel set of
  * `RoleConfig`, `SubjectConfig` and `PermissionConfig` types plus a translation somewhere else — and the
  * translation is exactly where the interesting mistakes live, because it is the step that decides what
  * `actions: [DELETE]` means. Doing it at load time instead buys the one property that matters: an
  * unrecognised action name, a resource that does not exist and a regular expression that will not compile
  * are all reported **at start-up**, with the key that is wrong, beside every other configuration mistake in
  * the file. A role that only fails when somebody presses a button is a role nobody can test.
  *
  * ==What the file looks like==
  *
  * {{{
  * kui:
  *   rbac:
  *     roles:
  *       - name: developers
  *         clusters: [local, staging]
  *         subjects:
  *           - provider: FORM
  *             kind: group
  *             value: developers
  *         permissions:
  *           - resource: TOPIC
  *             value: ".*"
  *             actions: [VIEW, MESSAGES_READ]
  *           - resource: CONSUMER
  *             value: ".*"
  *             actions: [ALL]
  *     defaultRole:
  *       permissions:
  *         - resource: TOPIC
  *           value: ".*"
  *           actions: [VIEW]
  * }}}
  *
  * A file with neither `roles` nor `defaultRole` yields [[kui.security.rbac.RbacPolicy.Disabled]], and a
  * disabled policy allows everything. That is Kafbat's rule and it is the one that keeps the quickstart
  * working: a deployment that has configured no roles has not asked for authorization, and denying everything
  * there would be a product that does nothing until it is configured.
  */
object RbacConfigSection {

  val Prefix: String = "kui.rbac"

  private val RolesPrefix: String = s"$Prefix.roles"
  private val DefaultRolePermissions: String = s"$Prefix.defaultRole.permissions"

  /** Every key this section understands, as the path segments `UnknownKeys` compares against.
    *
    * It is written out rather than derived so that a mistyped `permisions:` is a startup error naming the
    * key, instead of a role that silently grants nothing.
    */
  val keys: List[List[String]] =
    List(
      List("kui", "rbac", "roles", "*", "name"),
      List("kui", "rbac", "roles", "*", "clusters"),
      List("kui", "rbac", "roles", "*", "clusters", "*"),
      List("kui", "rbac", "roles", "*", "subjects", "*", "provider"),
      List("kui", "rbac", "roles", "*", "subjects", "*", "kind"),
      List("kui", "rbac", "roles", "*", "subjects", "*", "value"),
      List("kui", "rbac", "roles", "*", "subjects", "*", "isRegex"),
      List("kui", "rbac", "roles", "*", "permissions", "*", "resource"),
      List("kui", "rbac", "roles", "*", "permissions", "*", "value"),
      List("kui", "rbac", "roles", "*", "permissions", "*", "actions"),
      List("kui", "rbac", "roles", "*", "permissions", "*", "actions", "*"),
      List("kui", "rbac", "defaultRole", "permissions", "*", "resource"),
      List("kui", "rbac", "defaultRole", "permissions", "*", "value"),
      List("kui", "rbac", "defaultRole", "permissions", "*", "actions"),
      List("kui", "rbac", "defaultRole", "permissions", "*", "actions", "*")
    )

  /** The deployment's policy, or every reason it could not be read. */
  def decode(lookup: Lookup, indices: Indices): Problems[RbacPolicy] = {
    val roleIndices = indices(RolesPrefix)
    val defaultIndices = indices(DefaultRolePermissions)

    val roles = ConfigReader.all(roleIndices.map(index => role(lookup, indices, index)))
    val default =
      if defaultIndices.isEmpty then None.validNel
      else
        (
          ConfigReader.denseIndices(DefaultRolePermissions, defaultIndices),
          ConfigReader.all(
            defaultIndices.map(index => permission(lookup, s"$DefaultRolePermissions.$index"))
          )
        ).mapN((_, permissions) => Some(DefaultRole(permissions)))

    (ConfigReader.denseIndices(RolesPrefix, roleIndices), roles, default)
      .mapN((_, decoded, defaultRole) => (decoded, defaultRole))
      .andThen((decoded, defaultRole) => rejectDuplicateNames(decoded).map(RbacPolicy(_, defaultRole)))
  }

  // -----------------------------------------------------------------------------------------------

  /** Two roles with the same name are not a merge; they are one of them silently disappearing.
    *
    * `RbacPolicy.held` looks roles up by name, so a duplicate would be resolved arbitrarily depending on list
    * order — the classic "I granted it and it did nothing" report.
    */
  private def rejectDuplicateNames(roles: List[(Int, Role)]): Problems[List[Role]] =
    roles
      .groupBy((_, role) => role.name)
      .toList
      .sortBy((name, _) => name.value)
      .collect {
        case (name, clashing) if clashing.sizeIs > 1 =>
          ConfigProblem(
            s"$RolesPrefix.${clashing.map((index, _) => index).min}.name",
            s"'${name.value}' names more than one role " +
              s"(entries ${clashing.map((index, _) => index).sorted.mkString(", ")}); " +
              "give each role its own name, or merge their permissions into one entry",
            ConfigSourceName.Default
          )
      } match {
      case Nil => roles.map((_, role) => role).validNel
      case first :: rest => cats.data.Validated.Invalid(cats.data.NonEmptyList(first, rest))
    }

  private def role(lookup: Lookup, indices: Indices, index: Int): Problems[(Int, Role)] = {
    val prefix = s"$RolesPrefix.$index"
    val subjectIndices = indices(s"$prefix.subjects")
    val permissionIndices = indices(s"$prefix.permissions")

    val name = ConfigReader
      .required(lookup, s"$prefix.name", "a role name such as 'developers'")
      .map(RoleName.unsafe)

    val clusters = ConfigReader.list(lookup, s"$prefix.clusters") match {
      case Nil =>
        ConfigReader
          .problem(
            lookup,
            s"$prefix.clusters",
            "is required; a role names the clusters it applies on, and a role with no clusters " +
              "grants nothing anywhere. Use kui.rbac.defaultRole for permissions that apply everywhere"
          )
          .invalidNel
      case names => ConfigReader.all(names.map(clusterId(lookup, s"$prefix.clusters", _))).map(_.toSet)
    }

    val subjects =
      if subjectIndices.isEmpty then
        ConfigReader
          .problem(
            lookup,
            s"$prefix.subjects",
            "is required; a role with no subjects is a role nobody is ever in"
          )
          .invalidNel
      else
        (
          ConfigReader.denseIndices(s"$prefix.subjects", subjectIndices),
          ConfigReader.all(subjectIndices.map(i => subject(lookup, s"$prefix.subjects.$i")))
        ).mapN((_, decoded) => decoded)

    val permissions =
      if permissionIndices.isEmpty then
        ConfigReader
          .problem(lookup, s"$prefix.permissions", "is required; a role that grants nothing has no effect")
          .invalidNel
      else
        (
          ConfigReader.denseIndices(s"$prefix.permissions", permissionIndices),
          ConfigReader.all(permissionIndices.map(i => permission(lookup, s"$prefix.permissions.$i")))
        ).mapN((_, decoded) => decoded)

    (name, clusters, subjects, permissions).mapN((roleName, on, who, granted) =>
      index -> Role(roleName, on, who, granted)
    )
  }

  private def clusterId(lookup: Lookup, key: String, raw: String): Problems[ClusterId] =
    ClusterId.from(raw) match {
      case Right(id) => id.validNel
      case Left(reason) =>
        ConfigReader.problem(lookup, key, s"'$raw' is not a cluster id: ${reason.message}").invalidNel
    }

  private def subject(lookup: Lookup, prefix: String): Problems[Subject] = {
    val provider = ConfigReader
      .required(lookup, s"$prefix.provider", s"one of ${Provider.values.map(_.wire).mkString(", ")}")
      .andThen { raw =>
        Provider.fromWire(raw) match {
          case Some(value) => value.validNel
          case None =>
            ConfigReader
              .problem(
                lookup,
                s"$prefix.provider",
                s"'$raw' is not a provider; expected one of ${Provider.values.map(_.wire).mkString(", ")}"
              )
              .invalidNel
        }
      }

    val kind = ConfigReader
      .required(lookup, s"$prefix.kind", s"one of ${SubjectKind.values.map(_.wire).mkString(", ")}")
      .andThen { raw =>
        SubjectKind.fromWire(raw) match {
          case Some(value) => value.validNel
          case None =>
            ConfigReader
              .problem(
                lookup,
                s"$prefix.kind",
                s"'$raw' is not a subject kind; expected one of " +
                  SubjectKind.values.map(_.wire).mkString(", ")
              )
              .invalidNel
        }
      }

    val value = ConfigReader.required(lookup, s"$prefix.value", "a login name, group name or pattern")
    val isRegex = ConfigReader.boolean(lookup, s"$prefix.isRegex", fallback = false)

    (provider, kind, value, isRegex)
      .mapN(Subject.apply)
      .andThen(decoded => checkSubjectPattern(lookup, prefix, decoded))
  }

  /** A subject written as a regular expression has to compile, and it is checked here rather than left to
    * `Subject.matches` — which deliberately treats an uncompilable pattern as matching nothing, so that a bad
    * pattern cannot throw in the middle of a login. That is the right runtime behaviour and the wrong
    * *configuration* behaviour: silently matching nobody is how a role that grants access to everyone in a
    * group ends up granting it to no one, with nothing in the log.
    */
  private def checkSubjectPattern(lookup: Lookup, prefix: String, decoded: Subject): Problems[Subject] =
    if !decoded.isRegex then decoded.validNel
    else
      ResourcePattern.compile(decoded.value) match {
        case Right(_) => decoded.validNel
        case Left(reason) => ConfigReader.problem(lookup, s"$prefix.value", reason).invalidNel
      }

  private def permission(lookup: Lookup, prefix: String): Problems[Permission] =
    ConfigReader
      .required(lookup, s"$prefix.resource", s"one of ${Resource.values.map(_.wire).mkString(", ")}")
      .andThen { raw =>
        Resource.fromWire(raw) match {
          case Some(resource) => resource.validNel
          case None =>
            ConfigReader
              .problem(
                lookup,
                s"$prefix.resource",
                s"'$raw' is not a resource; expected one of ${Resource.values.map(_.wire).mkString(", ")}"
              )
              .invalidNel
        }
      }
      .andThen { resource =>
        (pattern(lookup, prefix, resource), actions(lookup, prefix, resource))
          .mapN((value, granted) => RbacPolicy.permission(resource, value, granted))
      }

  /** The resource-name pattern, and whether this resource is even named.
    *
    * The asymmetry is Kafbat's and is load bearing: a permission over `TOPIC` with no `value` grants nothing,
    * because every topic access names a topic, while a permission over `AUDIT` takes no `value` at all
    * because there is one audit trail. Rather than let a forgotten `value` silently grant nothing, a named
    * resource without one is a configuration error that says what to write.
    */
  private def pattern(
      lookup: Lookup,
      prefix: String,
      resource: Resource
  ): Problems[Option[ResourcePattern]] =
    (ConfigReader.optional(lookup, s"$prefix.value"), resource.isNamed) match {
      case (None, false) => none[ResourcePattern].validNel
      case (Some(raw), false) =>
        ConfigReader
          .problem(
            lookup,
            s"$prefix.value",
            s"${resource.wire} has no name to match against, so '$raw' would never apply; remove the value"
          )
          .invalidNel
      case (None, true) =>
        ConfigReader
          .problem(
            lookup,
            s"$prefix.value",
            s"is required for ${resource.wire}, which is named; write '.*' to mean every one of them"
          )
          .invalidNel
      case (Some(raw), true) =>
        ResourcePattern.compile(raw) match {
          case Right(compiled) => compiled.some.validNel
          case Left(reason) => ConfigReader.problem(lookup, s"$prefix.value", reason).invalidNel
        }
    }

  private def actions(lookup: Lookup, prefix: String, resource: Resource): Problems[Set[Action]] =
    ConfigReader.list(lookup, s"$prefix.actions") match {
      case Nil =>
        ConfigReader
          .problem(
            lookup,
            s"$prefix.actions",
            s"is required; expected ${Action.AllWire} or some of " +
              resource.allActions.map(_.wire).toList.sorted.mkString(", ")
          )
          .invalidNel
      case names if names.exists(_.trim.equalsIgnoreCase(Action.AllWire)) => resource.allActions.validNel
      case names =>
        ConfigReader
          .all(names.map { raw =>
            Action.fromWire(resource, raw) match {
              case Some(action) => action.validNel
              case None =>
                ConfigReader
                  .problem(
                    lookup,
                    s"$prefix.actions",
                    s"'$raw' is not an action on ${resource.wire}; expected ${Action.AllWire} or some of " +
                      resource.allActions.map(_.wire).toList.sorted.mkString(", ")
                  )
                  .invalidNel
            }
          })
          .map(_.toSet)
    }
}
