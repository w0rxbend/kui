package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom
import sttp.tapir.{Endpoint, PublicEndpoint}

import kui.contracts.ErrorEnvelope
import kui.identity.contract.dto.{
  AuthSettingsDto,
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse
}
import kui.kernel.{RoleName, UserName}
import kui.security.{Principal, PrincipalKind}
import kui.ui.kernel.api.{ApiClient, ApiError}
import kui.ui.shell.Shell

/** The sign-in screen, and — more important than anything the screen does — the rule that decides whether it
  * appears at all.
  *
  * The regression these tests exist to make impossible is not a broken login. It is a *working* login shown
  * to a deployment that asked for none: KUI's default is `kui.auth.type: disabled`, every demonstration
  * environment runs that default, and a newcomer who meets a password prompt with no account to type into has
  * met a broken product. Three of the tests below are about that single sentence.
  */
class LoginPageSuite extends FunSuite {

  private def mounted[A](element: HtmlElement)(check: dom.Element => A): A = {
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container): Unit
    val root = render(container, element)
    try check(element.ref)
    finally {
      root.unmount(): Unit
      dom.document.body.removeChild(container): Unit
    }
  }

  private def settings(kind: String, label: Option[String] = None): AuthSettingsDto =
    AuthSettingsDto(kind, label, rbacEnabled = true)

  private def anonymous: Principal =
    Principal(UserName.unsafe("anonymous"), Set.empty, PrincipalKind.Anonymous)

  private def ada: Principal =
    Principal(UserName.unsafe("ada"), Set(RoleName.unsafe("admin")), PrincipalKind.Session)

  private def page(kind: String, label: Option[String] = None): HtmlElement =
    LoginPage(settings(kind, label), None, Observer[Unit](_ => ()))

  /** An `ApiClient` that records what was asked and answers only when the test says so.
    *
    * Answering on demand rather than immediately is what makes these tests synchronous. The real client
    * returns a stream that emits on a later turn of the event loop, so a test that asserted straight after a
    * click would be asserting before the answer had arrived — and would pass or fail on timing. Here the
    * click subscribes, [[answer]] emits inside the test's own call stack, and the assertion that follows sees
    * a settled screen.
    *
    * The input is recorded rather than the endpoint, because the input is what distinguishes the two calls
    * this screen makes: a `LoginRequest` is a sign-in and a `ChangePasswordRequest` is a change. That is also
    * what lets a test assert the far more interesting negative — that no request was made at all.
    */
  private final class StubApi extends ApiClient {

    private val bus: EventBus[Any] = new EventBus[Any]

    /** Every input handed to [[call]], oldest first. */
    var requests: List[Any] = List.empty

    /** Delivers `value` as the answer to whatever is currently waiting. */
    def answer(value: Any): Unit = bus.emit(value)

    def call[I, O](
        endpoint: PublicEndpoint[I, ErrorEnvelope, O, Any],
        input: I
    ): EventStream[Either[ApiError, O]] = {
      requests = requests :+ input
      // The cast is the price of a stub for a method whose output type is fixed by its endpoint. The
      // test supplies the value for the endpoint it is exercising, so the only way to reach a wrong
      // type here is to write a test that asks for one.
      bus.events.map(value => Right(value.asInstanceOf[O]))
    }

    def callSecure[A, I, O](
        endpoint: Endpoint[A, I, ErrorEnvelope, O, Any],
        security: A,
        input: I
    ): EventStream[Either[ApiError, O]] =
      call(endpoint.asInstanceOf[PublicEndpoint[I, ErrorEnvelope, O, Any]], input)
  }

  /** Types into a `controlled` Laminar input the way a person does.
    *
    * Setting `value` alone is not enough and the reason is worth stating: the field is bound with
    * `controlled`, so the element's value is written *from* a `Var` and the `Var` is only updated by the
    * `input` event. Assigning `value` without dispatching one leaves the model empty, the screen then
    * rewrites the field from that empty model, and the test would be asserting against a form nobody filled
    * in.
    */
  private def typeInto(root: dom.Element, testId: String, text: String): Unit = {
    val field = root.querySelector(s"[data-testid='$testId']").asInstanceOf[dom.html.Input]
    field.value = text
    field.dispatchEvent(new dom.Event("input", new dom.EventInit { bubbles = true })): Unit
  }

  private def click(root: dom.Element, testId: String): Unit =
    root.querySelector(s"[data-testid='$testId']").asInstanceOf[dom.html.Element].click()

  // ---------------------------------------------------------------------------------------------
  // When the screen appears
  // ---------------------------------------------------------------------------------------------

  test("authentication disabled never asks anybody to sign in") {
    // The product's front door. This is the assertion that must never be deleted.
    assert(!Shell.mustSignIn(Some(settings("disabled")), Some(anonymous)))
  }

  test("an unanswered settings call never asks anybody to sign in") {
    // `/auth/settings` in flight, or failed. Falling towards "no sign-in" is the only safe direction:
    // the alternative is a locked door in front of a deployment that configured no lock.
    assert(!Shell.mustSignIn(None, Some(anonymous)))
  }

  test("form authentication with an anonymous principal asks for a sign-in") {
    assert(Shell.mustSignIn(Some(settings("form")), Some(anonymous)))
    assert(Shell.mustSignIn(Some(settings("oidc")), Some(anonymous)))
  }

  test("somebody already signed in is not asked again") {
    // Otherwise every reload is a sign-in loop for a user who already has a session cookie.
    assert(!Shell.mustSignIn(Some(settings("form")), Some(ada)))
  }

  test("a principal that has not arrived yet is not a reason to demand a sign-in") {
    assert(!Shell.mustSignIn(Some(settings("form")), None))
  }

  // ---------------------------------------------------------------------------------------------
  // What the screen contains
  // ---------------------------------------------------------------------------------------------

  test("the form flow offers a username and a real password field") {
    mounted(page("form")) { root =>
      val password = root.querySelector("[data-testid='login-password']")
      assert(root.querySelector("[data-testid='login-username']") != null, "no username field")
      assert(password != null, "no password field")
      // A password field that is not `type=password` shows the secret on screen and offers it to a
      // password manager as a username.
      assertEquals(password.getAttribute("type"), "password")
      assertEquals(password.getAttribute("autocomplete"), "current-password")
    }
  }

  test("Enter submits the form") {
    // Two things are needed and only the first is obvious. A `<form>` — and a submit button, because a
    // browser performs implicit submission only when the form has one, unless it has exactly one text
    // field. This form has two, and the visible control is the kernel's `Button`, which is a
    // `type="button"`. Without the hidden submit button, Enter did nothing and the only way in was the
    // mouse. That was observed in a browser before it was written down here.
    mounted(page("form")) { root =>
      assert(root.querySelector("form") != null, "no form element")
      val submit = root.querySelector("button[type='submit']")
      assert(submit != null, "no submit button, so Enter will not submit this form")
    }
  }

  test("the provider flow offers no password field at all") {
    // With a provider KUI never sees a password, so a field for one would be a control that cannot work.
    mounted(page("oidc", Some("Acme SSO"))) { root =>
      assertEquals(root.querySelector("[data-testid='login-password']"), null)
      val button = root.querySelector("[data-testid='login-provider']")
      assert(button != null, "no provider button")
      assert(button.textContent.contains("Acme SSO"), s"button said '${button.textContent}'")
    }
  }

  test("a provider with no configured label still names a button somebody can press") {
    mounted(page("oidc")) { root =>
      val button = root.querySelector("[data-testid='login-provider']")
      assert(button != null && button.textContent.trim.nonEmpty, "the button has no label")
    }
  }

  test("the screen announces itself to a screen reader") {
    mounted(page("form")) { root =>
      assertEquals(root.getAttribute("role"), "alertdialog")
      assert(root.getAttribute("aria-labelledby") != null, "the dialog has no accessible name")
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The forced password change
  // ---------------------------------------------------------------------------------------------

  test("a required password change replaces the sign-in fields instead of letting anybody in") {
    // The third outcome of `/auth/login`, and the one that is easiest to get wrong. The server grants no
    // session here — it answers with a single-use challenge and nothing else — so the screen must stop
    // asking for the old credentials and start asking for a new password. If it treated this as a
    // success, the browser would carry on as though somebody were signed in when nobody is.
    val api = new StubApi
    mounted(LoginPage(settings("form"), Some(api), Observer[Unit](_ => ()))) { root =>
      typeInto(root, "login-username", "ada")
      typeInto(root, "login-password", "the-temporary-one")
      click(root, "login-submit")

      assertEquals(api.requests, List(LoginRequest("ada", "the-temporary-one")))

      api.answer(LoginResponse.PasswordChangeRequired("challenge-token"))

      assert(
        root.querySelector("[data-testid='login-new-password']") != null,
        "no field to type a new password into"
      )
      assert(
        root.querySelector("[data-testid='login-confirm-password']") != null,
        "a new password is asked for once, so a typo in it locks the account out"
      )
      // The old credentials must be gone, not merely ignored: a username field still on screen invites
      // somebody to retype the temporary password and wonder why nothing happens.
      assertEquals(root.querySelector("[data-testid='login-username']"), null)
      assertEquals(root.querySelector("[data-testid='login-password']"), null)
    }
  }

  test("the challenge the server issued is the one sent back with the new password") {
    // The challenge *is* the proof that the current password was verified moments ago, which is why the
    // change endpoint takes no username and no old password. Sending anything else — or sending it for
    // the wrong account — would be a second login endpoint with none of the first one's protections.
    val api = new StubApi
    mounted(LoginPage(settings("form"), Some(api), Observer[Unit](_ => ()))) { root =>
      typeInto(root, "login-username", "ada")
      typeInto(root, "login-password", "the-temporary-one")
      click(root, "login-submit")
      api.answer(LoginResponse.PasswordChangeRequired("challenge-token"))

      typeInto(root, "login-new-password", "a-much-better-one")
      typeInto(root, "login-confirm-password", "a-much-better-one")
      click(root, "login-change-submit")

      assertEquals(
        api.requests.last,
        ChangePasswordRequest("challenge-token", "a-much-better-one")
      )
    }
  }

  test("two different new passwords are refused before the server is asked") {
    // A mismatch is the one failure this screen can diagnose by itself, and it must: the server would
    // accept the first spelling happily, and the user would be locked out by a typo they never saw. The
    // assertion that matters is the negative one — that nothing was sent.
    val api = new StubApi
    mounted(LoginPage(settings("form"), Some(api), Observer[Unit](_ => ()))) { root =>
      typeInto(root, "login-username", "ada")
      typeInto(root, "login-password", "the-temporary-one")
      click(root, "login-submit")
      api.answer(LoginResponse.PasswordChangeRequired("challenge-token"))

      typeInto(root, "login-new-password", "a-much-better-one")
      typeInto(root, "login-confirm-password", "a-much-better-typo")
      click(root, "login-change-submit")

      assert(
        !api.requests.exists(_.isInstanceOf[ChangePasswordRequest]),
        "a mismatched confirmation was sent to the server anyway"
      )
      val error = root.querySelector("[data-testid='login-error']")
      assert(error != null, "the mismatch was refused silently, which reads as a dead button")
      assert(
        root.querySelector("[data-testid='login-new-password']") != null,
        "the screen left the change flow, so there is no way to correct the typo"
      )
    }
  }

  // ---------------------------------------------------------------------------------------------
  // What it says
  // ---------------------------------------------------------------------------------------------

  test("every refusal is the same sentence, whichever half was wrong") {
    // Two sentences would be an oracle telling anybody with a browser which usernames exist, and would
    // throw away the decoy hash the identity service spends to keep the *timing* from answering it.
    val refused = ApiError.Envelope("KUI-UNAUTHENTICATED", "no such user", Nil, "cid", false)
    val wrongPassword = ApiError.Envelope("KUI-UNAUTHENTICATED", "bad password", Nil, "cid", false)
    assertEquals(LoginPage.sentenceFor(refused), LoginPage.sentenceFor(wrongPassword))
    // And it is KUI's own sentence, not the server's: neither "no such user" nor "bad password"
    // reaches the screen.
    assert(!LoginPage.sentenceFor(refused).contains("no such user"))
    assert(!LoginPage.sentenceFor(wrongPassword).contains("bad password"))
  }

  test("a failure that is not a refusal keeps its own sentence") {
    // "KUI cannot reach the server" is a fact the user needs and is not an oracle about accounts.
    assertEquals(LoginPage.sentenceFor(ApiError.Unreachable("boom")), ApiError.UnreachableMessage)
  }
}
