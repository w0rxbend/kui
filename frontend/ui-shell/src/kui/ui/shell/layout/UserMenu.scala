package kui.ui.shell.layout

import com.raquo.laminar.api.L.*
import org.scalajs.dom

import kui.security.{Principal, PrincipalKind}
import kui.ui.kernel.css.KernelCss
import kui.ui.shell.ShellCss

/** Who you are signed in as, and the way out.
  *
  * ==Why this is worth its own component==
  *
  * KUI has had a sign-out endpoint since M0 and, until now, nothing that called it. An operator who signed in
  * on a shared machine had no way to stop being signed in short of clearing cookies, and the audit found that
  * as a missing control rather than as a missing feature — `POST /api/v1/auth/logout` works, and worked, and
  * no pixel on any screen reached it.
  *
  * ==What it shows when nobody is signed in==
  *
  * The whole control disappears. A deployment with authentication switched off — the quickstart, and every
  * deployment until an identity provider is configured — has no identity to display and nothing to sign out
  * of, and a "Sign out" button that ends a session that never existed would be a lie about what the product
  * is doing. The distinction is made on [[PrincipalKind]] rather than on the presence of a principal, because
  * `/auth/me` always answers with one: the anonymous principal is how "authentication is off" is spelled.
  *
  * ==The menu's behaviour==
  *
  * It opens on click, closes on `Escape`, on a click anywhere outside it, and on choosing an item. Those
  * three are the ones people try; a menu that stays open after a click elsewhere is the single most-reported
  * complaint about hand-rolled dropdowns, and it is three lines to get right.
  *
  * @param signOut
  *   what to do when the item is chosen. A parameter rather than a call made here, because ending a session
  *   is the shell's business — it owns the API client and decides what happens afterwards — and because a
  *   suite has to be able to observe that the item was chosen without a server
  */
object UserMenu {

  def apply(principal: Signal[Option[Principal]], signOut: Observer[Unit]): HtmlElement = {
    val open = Var(false)

    div(
      cls := ShellCss.UserMenu,
      dataAttr("testid") := "user-menu",
      // Nothing at all when there is nobody to be. `child.maybe` rather than a hidden element: a control
      // that is present-but-invisible is still in the tab order, and tabbing onto an invisible sign-out
      // button is worse than not having one.
      child.maybe <-- principal.map(_.filter(isSignedIn).map(menu(_, open, signOut)))
    )
  }

  /** Whether this principal is somebody, as opposed to the stand-in for "authentication is off". */
  private def isSignedIn(principal: Principal): Boolean =
    principal.kind match {
      case PrincipalKind.Anonymous => false
      case PrincipalKind.Session | PrincipalKind.Bearer | PrincipalKind.System => true
    }

  private def menu(principal: Principal, open: Var[Boolean], signOut: Observer[Unit]): HtmlElement =
    div(
      cls := ShellCss.UserMenuAnchor,
      // Closing on a click outside is a document-level listener, mounted and unmounted with this element
      // so it cannot outlive the menu.
      documentEvents(_.onClick).filter(_ => open.now()) --> Observer[dom.Event](_ => open.set(false)),
      documentEvents(_.onKeyDown).filter(event => event.key == "Escape" && open.now()) -->
        Observer[dom.KeyboardEvent](_ => open.set(false)),
      button(
        tpe := "button",
        cls := KernelCss.Button,
        cls := KernelCss.ButtonGhost,
        cls := KernelCss.ButtonMd,
        dataAttr("testid") := "user-menu-trigger",
        aria.hasPopup := true,
        aria.expanded <-- open.signal,
        aria.label := s"Account: ${principal.name.value}",
        // The click that opens the menu must not immediately reach the document listener above and close
        // it again. Stopping it here is the whole of the fix.
        onClick.stopPropagation.mapTo(()) --> Observer[Unit](_ => open.update(!_)),
        span(cls := ShellCss.UserMenuName, principal.name.value)
      ),
      child.maybe <-- open.signal.map(Option.when(_)(panel(principal, open, signOut)))
    )

  private def panel(principal: Principal, open: Var[Boolean], signOut: Observer[Unit]): HtmlElement =
    div(
      cls := ShellCss.UserMenuPanel,
      role := "menu",
      dataAttr("testid") := "user-menu-panel",
      onClick.stopPropagation --> Observer[dom.Event](_ => ()),
      div(
        cls := ShellCss.UserMenuIdentity,
        div(cls := ShellCss.UserMenuIdentityName, principal.name.value),
        // The roles are shown because they are the answer to "why can I not do that" — an operator who
        // can see which roles they hold can ask for the right one instead of asking for "access".
        div(
          cls := ShellCss.UserMenuRoles,
          dataAttr("testid") := "user-menu-roles",
          if principal.roles.isEmpty then "No roles"
          else principal.roles.map(_.value).toList.sorted.mkString(", ")
        )
      ),
      button(
        tpe := "button",
        cls := ShellCss.UserMenuItem,
        role := "menuitem",
        dataAttr("testid") := "user-menu-sign-out",
        "Sign out",
        onClick.stopPropagation.mapTo(()) --> Observer[Unit] { _ =>
          open.set(false)
          signOut.onNext(())
        }
      )
    )
}
