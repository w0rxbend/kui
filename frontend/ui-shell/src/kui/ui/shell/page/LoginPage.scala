package kui.ui.shell.page

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import sttp.model.StatusCode
import sttp.tapir.PublicEndpoint

import kui.contracts.ErrorEnvelope
import kui.gateway.contract.AuthEndpoints
import kui.identity.contract.dto.{
  AuthSettingsDto,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  OidcStartResponse
}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.kernel.component.{Button, ButtonVariant, Components}
import kui.ui.shell.ShellCss

/** The sign-in screen: the door into a KUI that has authentication turned on.
  *
  * ==Why this file exists at all==
  *
  * Every server-side half of authentication was built before this: `POST /api/v1/auth/login` works,
  * `/auth/settings` says which kind of sign-in a deployment uses, the session cookie is issued and rotated,
  * roles are resolved and permissions are returned on `/auth/me`. None of it was reachable from a browser. A
  * deployment configured with `kui.auth.type: form` served its interface to anybody who asked, as the
  * anonymous principal, with no way to become anybody else — so the whole authentication feature was, from a
  * user's point of view, a set of endpoints and no product. This is the missing half.
  *
  * ==When it is shown, and when it must never be==
  *
  * Exactly one condition, decided in `Shell.app`: the deployment says it uses a sign-in (`authType !=
  * "disabled"`) **and** the browser's current principal is anonymous. Both halves matter.
  *
  *   - Without the first, a deployment that has deliberately configured no authentication — which is the
  *     default, and what the quickstart and every demonstration run — would meet a login screen with no
  *     account to type into. That is the product's front door, and putting a locked gate in front of it is
  *     the worst regression this screen could cause.
  *   - Without the second, a signed-in user would be asked to sign in again on every reload.
  *
  * ==Three flows, one screen==
  *
  *   - **form** — a username and a password, posted to `/auth/login`.
  *   - **oidc** — one button, which asks the gateway where to send the browser and then goes there. There is
  *     no password field, because with a provider KUI never sees one.
  *   - **a required password change** — what `/auth/login` answers when an account was configured with
  *     `mustChangePassword`. It is a *third* state and not a flag on success, because the server grants no
  *     session in that case: the screen collects a new password against the single-use challenge the server
  *     returned, and then asks the user to sign in with it.
  *
  * ==What it never does==
  *
  * It never says which half of a rejected sign-in was wrong. "That username and password were not accepted"
  * is one sentence for an unknown account and for a wrong password, because two sentences are an oracle that
  * tells anybody with a browser which usernames exist. The identity service already spends a decoy hash on an
  * unknown account so that the *timing* cannot answer that question either; saying it in the message would
  * throw that away.
  */
object LoginPage {

  /** `/auth/login` and its two siblings declare `(ErrorEnvelope, StatusCode)` as their error output, because
    * `ErrorEnvelope.statusOf` is the one code-to-status table in the system and these are the gateway's only
    * own endpoints with a business failure. `ApiClient` speaks the plain `ErrorEnvelope` shape that every
    * other endpoint in KUI uses.
    *
    * Rather than widen `ApiClient` for three endpoints, the status is dropped here. Nothing is lost: the
    * status was *derived from* the envelope's code on the way out, so the code — which is the stable thing
    * (ADR-034) and the thing `ApiError` renders — still carries the whole meaning. The reverse direction is
    * required by `mapErrorOut` and is never exercised by a client, which only ever decodes.
    */
  private def plainErrors[I, O](
      endpoint: PublicEndpoint[I, (ErrorEnvelope, StatusCode), O, Any]
  ): PublicEndpoint[I, ErrorEnvelope, O, Any] =
    endpoint.mapErrorOut((envelope, _) => envelope)(envelope => (envelope, StatusCode.BadRequest))

  private val login = plainErrors(AuthEndpoints.login)
  private val changePassword = plainErrors(AuthEndpoints.changePassword)
  private val oidcStart = plainErrors(AuthEndpoints.oidcStart)

  /** The sentence for a refused sign-in, whatever the server said.
    *
    * The gateway's own message is already deliberately uninformative, but it travels through
    * `KUI-UNAUTHENTICATED`, which other screens render as "your session has ended" — correct there and
    * actively confusing on a screen where no session has begun.
    */
  private val Refused = "That username and password were not accepted."

  /** What the screen is currently doing. A sealed set rather than three booleans, because "submitting and
    * also showing an error and also asking for a new password" is a state that must not be representable.
    */
  private enum Stage {
    case Credentials
    case ChangingPassword(challenge: String)
  }

  /** @param settings
    *   what the deployment said about itself. Fetched by the shell before this is built, so the screen never
    *   renders a form for a sign-in the server does not offer.
    * @param api
    *   `None` in a suite that only wants the layout. Every control then renders and does nothing, which is
    *   the honest shape for a screen with no server: it is better than a suite standing up a gateway, and it
    *   is why `submit` checks for it rather than asserting.
    * @param onSignedIn
    *   what to do once the server has issued a session. In the application it reloads the page, because every
    *   store in the shell — permissions, capabilities, the cluster list — was populated as the anonymous
    *   principal and a reload is the one way to be sure none of it survives.
    */
  def apply(
      settings: AuthSettingsDto,
      api: Option[ApiClient],
      onSignedIn: Observer[Unit]
  ): HtmlElement = {
    val stage = Var[Stage](Stage.Credentials)
    val username = Var("")
    val password = Var("")
    val newPassword = Var("")
    val confirmation = Var("")
    val busy = Var(false)
    val failure = Var(Option.empty[String])
    val notice = Var(Option.empty[String])

    val titleId = Components.nextId("kui-login-title")

    def fail(message: String): Unit = {
      failure.set(Some(message))
      notice.set(None)
      busy.set(false)
    }

    /** Signing in, and the three answers the server can give. */
    val submitCredentials: Observer[Unit] = Observer[Unit] { _ =>
      val name = username.now().trim
      val secret = password.now()
      if name.isEmpty || secret.isEmpty then fail("Enter a username and a password.")
      else
        api.foreach { client =>
          busy.set(true)
          failure.set(None)
          val _ = client
            .call(login, LoginRequest(name, secret))
            .foreach {
              case Right(LoginResponse.SignedIn(_)) =>
                // The password is dropped from the page's memory the moment it is no longer needed.
                // It buys little against a determined attacker with the machine, and it costs nothing.
                password.set("")
                busy.set(false)
                onSignedIn.onNext(())

              case Right(LoginResponse.PasswordChangeRequired(challenge)) =>
                password.set("")
                busy.set(false)
                notice.set(
                  Some("This account has to have a new password set before it can be used.")
                )
                failure.set(None)
                stage.set(Stage.ChangingPassword(challenge))

              case Left(error) => fail(sentenceFor(error))
            }(using unsafeWindowOwner)
        }
    }

    /** Completing a required change. The server grants no session for it, so this ends by asking the user to
      * sign in — one way to obtain a session rather than two.
      */
    def submitNewPassword(challenge: String): Observer[Unit] = Observer[Unit] { _ =>
      val fresh = newPassword.now()
      if fresh.isEmpty then fail("Enter a new password.")
      else if fresh != confirmation.now() then fail("The two passwords are not the same.")
      else
        api.foreach { client =>
          busy.set(true)
          failure.set(None)
          val _ = client
            .call(changePassword, ChangePasswordRequest(challenge, fresh))
            .foreach {
              case Right(_) =>
                newPassword.set("")
                confirmation.set("")
                busy.set(false)
                failure.set(None)
                notice.set(Some("Your password has been changed. Sign in with it."))
                stage.set(Stage.Credentials)

              case Left(error) => fail(sentenceFor(error))
            }(using unsafeWindowOwner)
        }
    }

    /** Handing the browser to the provider. The gateway answers with the URL to go to, state and PKCE
      * challenge already minted, so nothing about the flow is decided in the browser.
      */
    val startProvider: Observer[Unit] = Observer[Unit] { _ =>
      api.foreach { client =>
        busy.set(true)
        failure.set(None)
        val _ = client
          .call(oidcStart, ())
          .foreach {
            case Right(OidcStartResponse(url, _)) => org.scalajs.dom.window.location.href = url
            case Left(error) => fail(sentenceFor(error))
          }(using unsafeWindowOwner)
      }
    }

    div(
      cls := ShellCss.Login,
      dataAttr("testid") := "login-page",
      // The same role the unreachable screen uses, and for the same reason: it covers everything, nothing
      // behind it is usable, and `alertdialog` is what makes a screen reader announce it on arrival.
      role := "alertdialog",
      htmlAttr("aria-modal", com.raquo.laminar.codecs.StringAsIsCodec) := "true",
      aria.labelledBy := titleId,
      L.form(
        cls := ShellCss.LoginCard,
        // A real `<form>`, so that Enter in either field submits. Without this the only way in is the
        // mouse, which is the wrong answer on the one screen every user types on.
        onSubmit.preventDefault --> Observer[org.scalajs.dom.Event] { _ =>
          // Ignored while a request is in flight, so holding Enter down cannot send three sign-ins.
          if !busy.now() then
            stage.now() match {
              case Stage.Credentials => submitCredentials.onNext(())
              case Stage.ChangingPassword(challenge) => submitNewPassword(challenge).onNext(())
            }
        },
        // A hidden submit button, and it is not decoration.
        //
        // A browser only submits a form on Enter — "implicit submission" — when the form has a submit
        // button, unless it has exactly one text field. This form has two, and the visible "Sign in"
        // control is the kernel's `Button`, which is a `type="button"` with a click handler. So without
        // this, pressing Enter after typing a password did nothing at all: the only way in was the mouse,
        // on the one screen in the product that every user types on. Observed, and this is the fix.
        //
        // `hidden` rather than off-screen: the spec's default button need only exist and not be disabled,
        // and a hidden button is skipped by the keyboard and by screen readers, so it adds no control
        // anybody can find.
        button(tpe := "submit", hidden := true, aria.hidden := true, tabIndex := -1),
        h1(idAttr := titleId, cls := ShellCss.LoginTitle, "Sign in to KUI"),
        child.maybe <-- notice.signal.map(
          _.map(message => p(cls := ShellCss.LoginNotice, role := "status", message))
        ),
        child.maybe <-- failure.signal.map(
          _.map(message =>
            p(
              cls := ShellCss.LoginError,
              role := "alert",
              dataAttr("testid") := "login-error",
              message
            )
          )
        ),
        child <-- stage.signal.map {
          case Stage.Credentials if settings.authType == "oidc" =>
            provider(settings, busy.signal, startProvider)
          case Stage.Credentials =>
            credentials(username, password, busy.signal, submitCredentials)
          case Stage.ChangingPassword(challenge) =>
            newPasswordForm(newPassword, confirmation, busy.signal, submitNewPassword(challenge))
        }
      )
    )
  }

  private def credentials(
      username: Var[String],
      password: Var[String],
      busy: Signal[Boolean],
      submit: Observer[Unit]
  ): HtmlElement =
    div(
      cls := ShellCss.LoginFields,
      field("Username", "text", "username", username, busy, testId = "login-username"),
      field("Password", "password", "current-password", password, busy, testId = "login-password"),
      Button(
        label = Val("Sign in"),
        onClick = submit,
        variant = ButtonVariant.Primary,
        loading = busy,
        testId = Some("login-submit")
      )
    )

  private def newPasswordForm(
      fresh: Var[String],
      confirmation: Var[String],
      busy: Signal[Boolean],
      submit: Observer[Unit]
  ): HtmlElement =
    div(
      cls := ShellCss.LoginFields,
      field("New password", "password", "new-password", fresh, busy, testId = "login-new-password"),
      field(
        "New password again",
        "password",
        "new-password",
        confirmation,
        busy,
        testId = "login-confirm-password"
      ),
      Button(
        label = Val("Set password"),
        onClick = submit,
        variant = ButtonVariant.Primary,
        loading = busy,
        testId = Some("login-change-submit")
      )
    )

  /** The provider case: a label and a button, and nothing that could hold a credential.
    *
    * `providerLabel` is the only thing `/auth/settings` says about the provider, on purpose — a reference
    * product serves its whole configuration from an endpoint like that one, Kafka credentials included.
    */
  private def provider(
      settings: AuthSettingsDto,
      busy: Signal[Boolean],
      start: Observer[Unit]
  ): HtmlElement =
    div(
      cls := ShellCss.LoginFields,
      p(
        cls := ShellCss.LoginHint,
        "This KUI signs in through your organisation's identity provider."
      ),
      Button(
        label = Val(s"Continue with ${settings.providerLabel.getOrElse("your provider")}"),
        onClick = start,
        variant = ButtonVariant.Primary,
        loading = busy,
        testId = Some("login-provider")
      )
    )

  /** One labelled field.
    *
    * Written here rather than with `TextInput` for one reason: `TextInput` renders `type="text"` and cannot
    * be told otherwise, and a password field that is not `type="password"` shows the password on screen and
    * offers it to a browser's autofill as a username. `autocomplete` is set for the same reason — it is what
    * lets a password manager fill this form, and a login screen a manager cannot fill is one users work
    * around by choosing a memorable password.
    */
  private def field(
      label: String,
      kind: String,
      autocompleteValue: String,
      value: Var[String],
      busy: Signal[Boolean],
      testId: String
  ): HtmlElement = {
    val id = Components.nextId("kui-login-field")
    div(
      cls := ShellCss.LoginField,
      L.label(cls := ShellCss.LoginLabel, forId := id, label),
      input(
        idAttr := id,
        cls := ShellCss.LoginInput,
        tpe := kind,
        L.autoComplete := autocompleteValue,
        L.disabled <-- busy,
        dataAttr("testid") := testId,
        controlled(L.value <-- value.signal, onInput.mapToValue --> value)
      )
    )
  }

  /** The one place a server failure becomes a sentence.
    *
    * A refused credential is flattened to [[Refused]]; everything else keeps the kernel's own rendering,
    * because "KUI cannot reach the server" and "the identity service is not configured" are facts the user
    * genuinely needs and neither of them is an oracle about accounts.
    */
  private[shell] def sentenceFor(error: ApiError): String =
    if error.isAuth then Refused else error.userMessage
}
