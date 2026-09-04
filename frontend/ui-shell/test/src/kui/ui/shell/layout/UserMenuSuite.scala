package kui.ui.shell.layout

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.scalajs.dom

import kui.kernel.{RoleName, UserName}
import kui.security.{Principal, PrincipalKind}

/** The account menu, and the sign-out control the audit found missing.
  *
  * DOM assertions under jsdom: structure, attributes and text. The one behavioural thing worth proving here
  * is that choosing "Sign out" reaches the observer the shell wired to the logout endpoint, because that is
  * the wire that was absent — the endpoint has worked since M0 and nothing called it.
  */
final class UserMenuSuite extends FunSuite {

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

  private def signedIn(roles: String*): Principal =
    Principal(UserName.unsafe("ada"), roles.map(RoleName.unsafe).toSet, PrincipalKind.Session)

  private def click(element: dom.Element): Unit =
    element.asInstanceOf[dom.HTMLElement].click()

  test("there is no account control at all when nobody is signed in") {
    mounted(UserMenu(Val(None), Observer.empty)) { root =>
      assertEquals(root.querySelector("[data-testid='user-menu-trigger']"), null)
    }
  }

  test("the anonymous principal is not somebody, so it gets no sign-out button") {
    // `/auth/me` always answers with a principal: with authentication switched off it is the anonymous
    // one. Offering "Sign out" there would claim to end a session that never began.
    val anonymous = Val(Some(Principal.Anonymous))

    mounted(UserMenu(anonymous, Observer.empty)) { root =>
      assertEquals(root.querySelector("[data-testid='user-menu-trigger']"), null)
    }
  }

  test("the signed-in name is on screen and the menu starts closed") {
    mounted(UserMenu(Val(Some(signedIn())), Observer.empty)) { root =>
      val trigger = root.querySelector("[data-testid='user-menu-trigger']")

      assert(trigger != null, "a signed-in session should show the account control")
      assertEquals(trigger.textContent, "ada")
      assertEquals(trigger.getAttribute("aria-expanded"), "false")
      assertEquals(root.querySelector("[data-testid='user-menu-panel']"), null)
    }
  }

  test("opening the menu lists the roles the session holds") {
    // Which roles you hold is the answer to "why can I not do that", so it is worth showing: an operator
    // who can read it asks for the role they need instead of asking for "access".
    mounted(UserMenu(Val(Some(signedIn("readers", "admins"))), Observer.empty)) { root =>
      click(root.querySelector("[data-testid='user-menu-trigger']"))

      assertEquals(root.querySelector("[data-testid='user-menu-roles']").textContent, "admins, readers")
      assertEquals(root.querySelector("[data-testid='user-menu-trigger']").getAttribute("aria-expanded"), "true")
    }
  }

  test("a session with no roles says so rather than showing an empty line") {
    mounted(UserMenu(Val(Some(signedIn())), Observer.empty)) { root =>
      click(root.querySelector("[data-testid='user-menu-trigger']"))

      assertEquals(root.querySelector("[data-testid='user-menu-roles']").textContent, "No roles")
    }
  }

  test("choosing sign out reaches the observer and closes the menu") {
    var signedOut = 0

    mounted(UserMenu(Val(Some(signedIn())), Observer[Unit](_ => signedOut += 1))) { root =>
      click(root.querySelector("[data-testid='user-menu-trigger']"))
      click(root.querySelector("[data-testid='user-menu-sign-out']"))

      assertEquals(signedOut, 1)
      assertEquals(root.querySelector("[data-testid='user-menu-panel']"), null)
    }
  }

  test("a click anywhere else closes the menu") {
    mounted(UserMenu(Val(Some(signedIn())), Observer.empty)) { root =>
      click(root.querySelector("[data-testid='user-menu-trigger']"))
      assert(root.querySelector("[data-testid='user-menu-panel']") != null)

      click(dom.document.body)

      assertEquals(root.querySelector("[data-testid='user-menu-panel']"), null)
    }
  }

  test("a session that expires takes the sign-out control with it") {
    // The control is bound to the principal, so a session the server has forgotten cannot be signed out
    // of a second time — and, more importantly, the header stops claiming somebody is signed in.
    val principal = Var(Option(signedIn()))

    mounted(UserMenu(principal.signal, Observer.empty)) { root =>
      assert(root.querySelector("[data-testid='user-menu-trigger']") != null)

      principal.set(None)

      assertEquals(root.querySelector("[data-testid='user-menu-trigger']"), null)
    }
  }
}
