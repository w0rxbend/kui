package kui.identity.application

import cats.effect.IO
import cats.effect.kernel.Ref

import kui.identity.application.IdentityFixtures.*
import kui.identity.domain.{AuthMode, Identity}
import kui.kernel.error.{ApplicationError, ErrorCode, KuiError}
import kui.kernel.{RoleName, Secret, UserName}
import kui.security.audit.AuthenticationEvent
import kui.security.rbac.*
import kui.testkit.KuiIOSuite
import kui.testkit.fakes.FakeStructuredLogger

/** The provider sign-in, and the four rules W11-A1 found nothing asserting.
  *
  * Until this file existed `OidcLoginUseCase` had **no suite at all**: deleting the `state` check, both mode
  * gates and the role-subject attribution each left `./mill services.identity.__.test` and
  * `./mill apps.allinone.test` entirely green. The file's own scaladoc calls the `state` check "the whole
  * security story" of the flow, which is what made it worth writing these rather than a coverage number.
  */
final class OidcLoginUseCaseSuite extends KuiIOSuite {

  private val ada: Identity = Identity(UserName.unsafe("ada@example.com"), Set("platform"))

  /** A provider that counts how many times it was asked to exchange a code.
    *
    * The count is the point of the first case below: refusing a forged callback *after* the exchange would
    * still be a refusal, and would still have handed an attacker's authorization code to the provider on this
    * deployment's client credentials. The rule is that the exchange never happens.
    */
  final private class CountingProvider(
      val starts: Ref[IO, List[String]],
      val completions: Ref[IO, List[(String, PendingLogin)]]
  ) extends OidcProviderPort[IO] {

    def start(state: String): IO[Either[KuiError, (String, PendingLogin)]] =
      starts
        .update(_ :+ state)
        .as(Right(("https://accounts.example.com/authorize?state=" + state, waiting)))

    def complete(code: String, pending: PendingLogin): IO[Either[KuiError, Identity]] =
      completions.update(_ :+ (code, pending)).as(Right(ada))
  }

  private val waiting: PendingLogin = PendingLogin("the-nonce", Secret("the-verifier"))

  private def provider: IO[CountingProvider] =
    for {
      starts <- Ref.of[IO, List[String]](Nil)
      completions <- Ref.of[IO, List[(String, PendingLogin)]](Nil)
    } yield new CountingProvider(starts, completions)

  private def oidcConfig(policy: RbacPolicy = RbacPolicy.Disabled): IdentityConfig =
    IdentityConfig(AuthMode.Oidc, Some(ProviderSummary("Example")), policy)

  private def rig(
      config: IdentityConfig = oidcConfig()
  ): IO[(OidcLoginUseCase[IO], CountingProvider, SingleUseTokens[IO, PendingLogin], RecordingAudit)] =
    for {
      port <- provider
      pending <- SingleUseTokens.make[IO, PendingLogin]()
      audit <- RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
    } yield (new OidcLoginUseCase[IO](config, port, pending, audit, logger), port, pending, audit)

  // -----------------------------------------------------------------------------------------------

  test("a callback whose state this process never issued is refused, and no code is exchanged for it") {
    // W11-A1 mutation I13: replacing the `None` branch with an exchange against an empty `PendingLogin`
    // left `services.identity.__.test` (1,186 targets) and `apps.allinone.test` green. That branch is the
    // classic OAuth login-CSRF defence: without it an attacker's authorization code, delivered to a
    // signed-in operator's browser, quietly re-signs that operator in as the attacker.
    rig().flatMap { (useCase, port, _, audit) =>
      for {
        result <- useCase.complete("an-attackers-code", Secret("a-state-nobody-issued"))
        exchanged <- port.completions.get
        recorded <- audit.entries.get
      } yield {
        assertEquals(result.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated))
        assertEquals(
          result.left.toOption.map(_.message),
          Some(OidcLoginUseCase.Refusal.message),
          "the refusal says which check failed"
        )
        // The whole point: refused *before* any HTTP call, so the code is never presented to the provider.
        assertEquals(exchanged, Nil, "an unaccounted-for callback still reached the token exchange")
        // And it leaves a trace, because a forged callback is exactly the event an operator wants to see.
        assertEquals(recorded.map(_.event), List(AuthenticationEvent.OidcCallback))
      }
    }
  }

  test("a state is single use, so the same callback delivered twice signs nobody in the second time") {
    rig().flatMap { (useCase, port, _, _) =>
      for {
        started <- useCase.start()
        state = started.toOption.map(_.state).getOrElse(fail("start refused"))
        first <- useCase.complete("the-code", state)
        second <- useCase.complete("the-code", state)
        exchanged <- port.completions.get
      } yield {
        assertEquals(first.toOption.map(_.name.value), Some("ada@example.com"))
        assertEquals(second.left.toOption.map(_.code), Some(ErrorCode.Unauthenticated))
        assertEquals(exchanged.size, 1, "the replayed callback was exchanged a second time")
      }
    }
  }

  test("the state handed to the provider is the one the token store minted, and it is remembered") {
    rig().flatMap { (useCase, port, pending, _) =>
      for {
        started <- useCase.start()
        issued <- port.starts.get
        redirect = started.toOption.getOrElse(fail("start refused"))
        // What was remembered against the state is what the adapter chose, not a placeholder.
        remembered <- pending.redeem(redirect.state, IdentityFixtures.Now)
      } yield {
        assertEquals(issued, List(redirect.state.value))
        assert(redirect.authorizationUrl.contains(redirect.state.value), redirect.authorizationUrl)
        assertEquals(remembered, Some(waiting))
      }
    }
  }

  test("neither half of the provider flow works when kui.auth.type is not oidc") {
    // W11-A1 mutations I14 and I15, both green before this case. A provider sign-in that still worked in a
    // deployment configured for `form` — or for `disabled` — is a second way in that nobody configured, and
    // it is the same rule `LoginUseCase` states for passwords and has asserted.
    forEachRow(List(AuthMode.Form, AuthMode.Disabled)) { mode =>
      rig(IdentityConfig(mode, None, RbacPolicy.Disabled)).flatMap { (useCase, port, _, _) =>
        for {
          start <- useCase.start()
          complete <- useCase.complete("the-code", Secret("any-state"))
          asked <- port.starts.get
          exchanged <- port.completions.get
        } yield {
          assertEquals(start.left.toOption.map(_.code), Some(ErrorCode.Unsupported), mode.wire)
          assertEquals(complete.left.toOption.map(_.code), Some(ErrorCode.Unsupported), mode.wire)
          assert(start.left.toOption.exists(_.message.contains(mode.wire)), start.toString)
          assertEquals(asked, Nil, s"the provider was asked to start a sign-in in $mode mode")
          assertEquals(exchanged, Nil, s"a code was exchanged in $mode mode")
        }
      }
    }
  }

  test("a provider's group matches a role written against `group` and one written against `role`") {
    // W11-A1 mutation I16: dropping `SubjectKind.Role -> identity.groups` was green. It is the line that
    // lets one role file describe a deployment whose provider emits `groups` and one whose reference
    // product spells the same list `role` — and losing it silently grants nobody anything, which reads in
    // the interface as "your permissions are gone" rather than as a configuration error.
    def roleOver(kind: SubjectKind): Role =
      Role(
        name = RoleName.unsafe(s"operators-by-${kind.toString.toLowerCase}"),
        clusters = Set(kui.kernel.ClusterId.unsafe("local")),
        subjects = List(Subject(Provider.Oauth, kind, "platform", isRegex = false)),
        permissions = List(RbacPolicy.allPermission(Resource.Topic, Some(ResourcePattern.Everything)))
      )

    val policy = RbacPolicy(List(roleOver(SubjectKind.Group), roleOver(SubjectKind.Role)), None)

    rig(oidcConfig(policy)).flatMap { (useCase, _, _, _) =>
      for {
        started <- useCase.start()
        state = started.toOption.map(_.state).getOrElse(fail("start refused"))
        result <- useCase.complete("the-code", state)
      } yield {
        val principal = result.toOption.getOrElse(fail(result.toString))
        assertEquals(
          principal.roles.map(_.value),
          Set("operators-by-group", "operators-by-role"),
          "a provider group no longer reaches a role file written against `role`"
        )
      }
    }
  }

  test("a provider that refuses the exchange is reported as its own failure, not as a bad state") {
    val failing = new OidcProviderPort[IO] {
      def start(state: String): IO[Either[KuiError, (String, PendingLogin)]] =
        IO.pure(Right(("https://accounts.example.com/authorize", waiting)))

      def complete(code: String, pending: PendingLogin): IO[Either[KuiError, Identity]] =
        IO.pure(Left(ApplicationError.Invalid("the provider said no", Nil)))
    }

    for {
      tokens <- SingleUseTokens.make[IO, PendingLogin]()
      audit <- RecordingAudit.make
      logger <- FakeStructuredLogger[IO]
      useCase = new OidcLoginUseCase[IO](oidcConfig(), failing, tokens, audit, logger)
      started <- useCase.start()
      state = started.toOption.map(_.state).getOrElse(fail("start refused"))
      result <- useCase.complete("the-code", state)
      recorded <- audit.entries.get
    } yield {
      assertEquals(result.left.toOption.map(_.message), Some("the provider said no"))
      assertEquals(recorded.size, 1)
    }
  }

  /** Runs an assertion over every row of a table, in order, so a failure names the row that failed. */
  private def forEachRow[A](values: List[A])(check: A => IO[Unit]): IO[Unit] =
    values.foldLeft(IO.unit)((acc, value) => acc.flatMap(_ => check(value)))
}
