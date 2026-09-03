package kui.ui.shell.layout

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.Toast
import kui.ui.kernel.css.KernelCss
import kui.ui.kernel.state.NotificationBus
import kui.ui.shell.ShellCss

/** The application frame: a skip link, a header, a sidebar, a content area and the toast host.
  *
  * ## The skip link comes first, and that is not a detail
  *
  * The first focusable element in the document is a link to the content area. A keyboard user landing on a
  * page otherwise has to tab through every navigation entry before reaching what they came for — on every
  * page, every time. It is visually hidden until it has focus, which is why it costs sighted users nothing
  * and why it must not be moved further down "because it is invisible anyway".
  *
  * ## Why `content` is a `Signal[HtmlElement]` and not an `HtmlElement`
  *
  * Because the page changes and the frame does not. Handing in a signal means the router decides what is in
  * the middle, and the header and sidebar are built once and never rebuilt — which is also what keeps focus,
  * scroll position and open menus in the frame from being reset on every navigation.
  */
object Layout {

  /** The id the skip link targets, and the id of the `<main>` element. */
  val ContentId = "kui-content"

  def apply(sidebar: HtmlElement, header: HtmlElement, content: Signal[HtmlElement]): HtmlElement =
    div(
      cls := KernelCss.Root,
      cls := ShellCss.Shell,
      a(href := s"#$ContentId", cls := ShellCss.SkipLink, "Skip to content"),
      header,
      sidebar,
      mainTag(
        idAttr := ContentId,
        cls := ShellCss.Content,
        // `tabindex="-1"` makes the element focusable by the skip link without putting it in the tab
        // order. Without it the browser moves the *scroll* position and leaves focus where it was,
        // so the next Tab goes back into the navigation and the link achieves nothing.
        tabIndex := -1,
        child <-- content
      ),
      Toast(NotificationBus.current, NotificationBus.queued, Observer(NotificationBus.dismiss))
    )
}
