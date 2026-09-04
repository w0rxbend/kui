package kui.ui.shell.page

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.identity.contract.dto.AuthSettingsDto
import kui.kernel.{RoleName, UserName}
import kui.security.{Principal, PrincipalKind}
import kui.ui.kernel.api.ApiError
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

  test("the form is a real form, so Enter submits it") {
    // Without a `<form>` the only way in is the mouse, on the one screen every user types on.
    mounted(page("form"))(root => assert(root.querySelector("form") != null, "no form element"))
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
