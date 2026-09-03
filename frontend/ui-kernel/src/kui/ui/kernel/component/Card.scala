package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** A bounded region of a page: a surface, a border, consistent padding, and three optional slots.
  *
  * Deliberately dumb. It contributes no landmark role and no heading of its own, because a card is a visual
  * grouping and not a semantic one — inventing a `<section>` or an `<h2>` here would put structure into the
  * page's outline that the page's author did not ask for and cannot see. Callers that want a heading pass one
  * into `header` at the level their document actually needs.
  *
  * @param elevated
  *   draws a shadow instead of a border, for content that floats above the page rather than sitting in it.
  */
object Card {

  def apply(
      body: Modifier[HtmlElement],
      header: Option[Modifier[HtmlElement]] = None,
      footer: Option[Modifier[HtmlElement]] = None,
      elevated: Boolean = false,
      testId: Option[String] = None
  ): HtmlElement =
    div(
      cls := KernelCss.Card,
      Option.when(elevated)(cls := KernelCss.CardElevated),
      Components.testIdAttr(testId),
      header.map(content => div(cls := KernelCss.CardHeader, content)),
      div(cls := KernelCss.CardBody, body),
      footer.map(content => div(cls := KernelCss.CardFooter, content))
    )
}
