package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** What a list shows when it has nothing in it.
  *
  * An empty region is ambiguous: it could mean "there is nothing here", "your filter matched nothing", or
  * "the request failed and we did not say so". Every one of those wants a different next action from the
  * user, so an empty list always says which it is and, where there is one, offers the action that fixes it.
  *
  * The icon is decoration (`Icon` renders everything `aria-hidden`), so the title and the description carry
  * the whole message.
  */
object EmptyState {

  def apply(
      title: String,
      description: Option[String] = None,
      icon: Option[() => SvgElement] = None,
      action: Option[HtmlElement] = None,
      testId: Option[String] = None
  ): HtmlElement =
    div(
      cls := KernelCss.EmptyState,
      Components.testIdAttr(testId),
      icon.map(build => div(cls := KernelCss.EmptyStateIcon, build())),
      p(cls := KernelCss.EmptyStateTitle, title),
      description.map(body => p(cls := KernelCss.EmptyStateDescription, body)),
      action.map(control => div(cls := KernelCss.EmptyStateAction, control))
    )

  /** The neutral default `DataTable` falls back to when its caller supplies nothing better.
    *
    * A method rather than a value: a DOM node can only be in one place at a time, so two tables sharing one
    * element would fight over it.
    */
  def default: HtmlElement = apply("Nothing to show", description = Some("There is no data here yet."))
}
