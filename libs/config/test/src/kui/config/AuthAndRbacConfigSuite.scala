package kui.config

import java.nio.file.Path

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import kui.kernel.{ClusterId, RoleName}
import kui.security.rbac.{Action, Provider, Resource, SubjectKind}
import kui.testkit.KuiSuite

/** That `kui.auth` and `kui.rbac` are read, and that every way of writing them wrongly is a startup error
  * naming the key.
  *
  * The reason this suite is long is the reason the sections exist: a role is a security control, and a
  * security control that silently grants nothing is worse than one that refuses to start. Every `assert`
  * below about a *problem* is one such silence made loud.
  */
final class AuthAndRbacConfigSuite extends KuiSuite {

  private def load(
      yaml: String,
      env: Map[String, String] = Map.empty
  ): Either[ConfigErrors, KuiConfig] =
    KuiConfigSource
      .loadFrom[IO](Nil, List[Path](ConfigFixtures.yaml(yaml)), env, UrlPolicy.Dev)
      .unsafeRunSync()

  private def loaded(yaml: String, env: Map[String, String] = Map.empty): KuiConfig =
    load(yaml, env).fold(errors => fail(errors.render), identity)

  private def problems(yaml: String, env: Map[String, String] = Map.empty): List[ConfigProblem] =
    load(yaml, env) match {
      case Left(errors) => errors.problems.toList
      case Right(_) => fail("expected the load to fail, but it succeeded")
    }

  // -----------------------------------------------------------------------------------------------
  // The default, which is the one behaviour that must never change
  // -----------------------------------------------------------------------------------------------

  test("a file that says nothing about authentication gets none, and a policy that allows everything") {
    val config = loaded("kui:\n  server:\n    port: 8080\n")

    assertEquals(config.auth, AuthConfig.Default)
    assertEquals(config.auth.authType, AuthType.Disabled)
    assertEquals(config.rbac.enabled, false)
  }

  test("type: disabled is still spellable, and still means the same thing") {
    assertEquals(loaded("kui:\n  auth:\n    type: disabled\n").auth.authType, AuthType.Disabled)
  }

  // -----------------------------------------------------------------------------------------------
  // kui.auth
  // -----------------------------------------------------------------------------------------------

  test("form users are read, with their groups, and the hash is followed through env:") {
    val config = loaded(
      """kui:
        |  auth:
        |    type: form
        |    users:
        |      - name: ada
        |        passwordHash: "env:KUI_ADA_HASH"
        |        groups: [admins, oncall]
        |      - name: bob
        |        passwordHash: "pbkdf2-sha256$210000$c2FsdA$aGFzaA"
        |        mustChangePassword: true
        |""".stripMargin,
      env = Map("KUI_ADA_HASH" -> "pbkdf2-sha256$210000$c2FsdA$b3RoZXI")
    )

    assertEquals(config.auth.authType, AuthType.Form)
    assertEquals(config.auth.users.map(_.name), List("ada", "bob"))
    assertEquals(config.auth.users.head.groups, Set("admins", "oncall"))
    assertEquals(config.auth.users.head.passwordHash.value, "pbkdf2-sha256$210000$c2FsdA$b3RoZXI")
    assertEquals(config.auth.users.head.mustChangePassword, false)
    assertEquals(config.auth.users(1).mustChangePassword, true)
  }

  test("a password hash never appears in a rendered configuration problem") {
    // The hash resolves, and an unrelated key is wrong. The rendered error must still not carry it.
    val rendered = load(
      """kui:
        |  auth:
        |    type: form
        |    users:
        |      - name: ada
        |        passwordHash: "pbkdf2-sha256$210000$c2FsdA$c3VwZXJzZWNyZXQ"
        |  server:
        |    port: not-a-port
        |""".stripMargin
    ) match {
      case Left(errors) => errors.render
      case Right(_) => fail("expected the load to fail")
    }

    assert(!rendered.contains("c3VwZXJzZWNyZXQ"), rendered)
  }

  test("type: form with no users is refused, rather than starting a login nobody can pass") {
    val found = problems("kui:\n  auth:\n    type: form\n")

    assertEquals(found.map(_.key), List("kui.auth.users"))
    assert(found.head.problem.contains("is required when kui.auth.type is 'form'"), found.head.problem)
  }

  test("type: ldap says it is not implemented rather than that it is not a word") {
    val found = problems("kui:\n  auth:\n    type: ldap\n")

    assertEquals(found.map(_.key), List("kui.auth.type"))
    assert(found.head.problem.contains("not implemented yet"), found.head.problem)
  }

  test("an unknown auth type lists the ones that exist") {
    val found = problems("kui:\n  auth:\n    type: saml\n")

    assertEquals(found.map(_.key), List("kui.auth.type"))
    assert(found.head.problem.contains("disabled, form, oidc"), found.head.problem)
  }

  test("the oidc block is read, openid is added when it was forgotten, and the secret is followed") {
    val config = loaded(
      """kui:
        |  auth:
        |    type: oidc
        |    oidc:
        |      issuer: https://accounts.example.com
        |      clientId: kui
        |      clientSecret: "env:KUI_OIDC_SECRET"
        |      redirectUri: http://localhost:8080/api/v1/auth/oidc/callback
        |      scopes: [profile, email]
        |      usernameClaim: email
        |      groupsClaim: groups
        |      label: Example
        |""".stripMargin,
      env = Map("KUI_OIDC_SECRET" -> "s3cret")
    )

    val oidc = config.auth.oidc.getOrElse(fail("expected the oidc block to be read"))
    assertEquals(config.auth.authType, AuthType.Oidc)
    assertEquals(oidc.scopes, List("openid", "profile", "email"))
    assertEquals(oidc.usernameClaim, "email")
    assertEquals(oidc.groupsClaim, Some("groups"))
    assertEquals(oidc.clientSecret.value, "s3cret")
  }

  test("half an oidc block is a half-finished edit, not a block to ignore") {
    val found = problems(
      """kui:
        |  auth:
        |    oidc:
        |      issuer: https://accounts.example.com
        |""".stripMargin
    )

    assertEquals(
      found.map(_.key).sorted,
      List("kui.auth.oidc.clientId", "kui.auth.oidc.clientSecret", "kui.auth.oidc.redirectUri")
    )
  }

  test("type: oidc with no provider configured is refused") {
    assertEquals(problems("kui:\n  auth:\n    type: oidc\n").map(_.key), List("kui.auth.oidc"))
  }

  // -----------------------------------------------------------------------------------------------
  // kui.rbac
  // -----------------------------------------------------------------------------------------------

  private val ValidRoles: String =
    """kui:
      |  rbac:
      |    roles:
      |      - name: developers
      |        clusters: [local]
      |        subjects:
      |          - provider: FORM
      |            kind: group
      |            value: devs
      |        permissions:
      |          - resource: TOPIC
      |            value: "orders.*"
      |            actions: [MESSAGES_DELETE]
      |          - resource: AUDIT
      |            actions: [ALL]
      |    defaultRole:
      |      permissions:
      |        - resource: TOPIC
      |          value: ".*"
      |          actions: [VIEW]
      |""".stripMargin

  test("a role is read into the evaluator's own policy, with its actions already expanded") {
    val policy = loaded(ValidRoles).rbac

    assertEquals(policy.enabled, true)
    assertEquals(policy.roles.map(_.name), List(RoleName.unsafe("developers")))
    assertEquals(policy.roles.head.clusters, Set(ClusterId.unsafe("local")))
    assertEquals(
      policy.roles.head.subjects.head,
      kui.security.rbac.Subject(Provider.Form, SubjectKind.Group, "devs", isRegex = false)
    )

    val topic = policy.roles.head.permissions
      .find(_.resource == Resource.Topic)
      .getOrElse(fail("expected a topic permission"))

    // `MESSAGES_DELETE` implies being able to see the topic at all. The closure is applied at load
    // time so that the browser is handed an expanded list and never has to re-derive it.
    assert(topic.actions.contains(Action.TopicMessagesDelete), topic.actions.toString)
    assert(topic.actions.contains(Action.TopicView), topic.actions.toString)

    assertEquals(policy.defaultRole.map(_.permissions.size), Some(1))
  }

  test("ALL expands to every action the resource has") {
    val policy = loaded(ValidRoles).rbac
    val audit = policy.roles.head.permissions
      .find(_.resource == Resource.Audit)
      .getOrElse(fail("expected an audit permission"))

    assertEquals(audit.actions, Resource.Audit.allActions)
    assertEquals(audit.value, None)
  }

  test("an action that does not exist on that resource names the ones that do") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: FORM
        |            kind: group
        |            value: devs
        |        permissions:
        |          - resource: TOPIC
        |            value: ".*"
        |            actions: [DEMOLISH]
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.rbac.roles.0.permissions.0.actions"))
    assert(found.head.problem.contains("'DEMOLISH' is not an action on TOPIC"), found.head.problem)
  }

  test("a named resource with no value is refused, because it would silently grant nothing") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: FORM
        |            kind: group
        |            value: devs
        |        permissions:
        |          - resource: TOPIC
        |            actions: [VIEW]
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.rbac.roles.0.permissions.0.value"))
    assert(found.head.problem.contains("write '.*'"), found.head.problem)
  }

  test("a pattern that will not compile is reported at start-up and not at the first request") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: FORM
        |            kind: group
        |            value: devs
        |        permissions:
        |          - resource: TOPIC
        |            value: "orders(["
        |            actions: [VIEW]
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.rbac.roles.0.permissions.0.value"))
  }

  test("a role with no subjects and no permissions is refused on both counts at once") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |""".stripMargin
    )

    assertEquals(
      found.map(_.key).sorted,
      List("kui.rbac.roles.0.permissions", "kui.rbac.roles.0.subjects")
    )
  }

  test("two roles with one name is a role that would silently disappear") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: FORM
        |            kind: user
        |            value: ada
        |        permissions:
        |          - resource: KSQL
        |            actions: [ALL]
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: FORM
        |            kind: user
        |            value: bob
        |        permissions:
        |          - resource: KSQL
        |            actions: [ALL]
        |""".stripMargin
    )

    assertEquals(found.map(_.key), List("kui.rbac.roles.0.name"))
    assert(found.head.problem.contains("names more than one role"), found.head.problem)
  }

  test("an unknown provider or subject kind is named, with the legal values") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        subjects:
        |          - provider: CARRIER_PIGEON
        |            kind: flock
        |            value: devs
        |        permissions:
        |          - resource: KSQL
        |            actions: [ALL]
        |""".stripMargin
    )

    assertEquals(
      found.map(_.key).sorted,
      List("kui.rbac.roles.0.subjects.0.kind", "kui.rbac.roles.0.subjects.0.provider")
    )
  }

  test("a mistyped key under kui.rbac is still an unknown key") {
    val found = problems(
      """kui:
        |  rbac:
        |    roles:
        |      - name: developers
        |        clusters: [local]
        |        permisions:
        |          - resource: KSQL
        |            actions: [ALL]
        |""".stripMargin
    )

    // The unknown-key check reports the *leaves* it found, so the mistyped parent appears as the
    // prefix of every key under it. Either way the operator is told the word they wrote, which is
    // the promise; and the role itself is separately refused for having no permissions at all.
    assert(
      found.exists(_.key.startsWith("kui.rbac.roles.0.permisions")),
      found.map(_.key).toString
    )
    assert(found.exists(_.key == "kui.rbac.roles.0.permissions"), found.map(_.key).toString)
  }
}
