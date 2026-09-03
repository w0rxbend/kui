package kui.ui.kernel.component

import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** One step in a breadcrumb trail. `href` is `None` for the page you are on, which has nowhere to go. */
final case class Crumb(label: String, href: Option[String])

/** "Cluster / Topics / orders" — where you are, and how to get back.
  *
  * KUI's URLs nest four or five levels deep (cluster, topic, tab, message), and without a trail the only way
  * back to the topic list is the browser's Back button, which does the wrong thing after a few in-page
  * navigations.
  *
  * ## Accessibility contract
  *
  * A `<nav aria-label="Breadcrumb">` wrapping an ordered list, which is what tells a screen reader this is
  * navigation and what order the steps are in. The last crumb is not a link and carries
  * `aria-current="page"`. The separators are `aria-hidden`, because "slash" read out between every step is
  * noise.
  */
object Breadcrumbs {

  /** `<nav>`. Laminar spells the element `navTag` because `nav` would collide with the attribute namespace;
    * aliased here so the markup below reads like HTML.
    */
  private val navTag = htmlTag("nav")

  def apply(crumbs: Signal[List[Crumb]], testId: Option[String] = None): HtmlElement =
    navTag(
      cls := KernelCss.Breadcrumbs,
      aria.label := "Breadcrumb",
      Components.testIdAttr(testId),
      ol(
        cls := KernelCss.BreadcrumbsList,
        children <-- crumbs.map { steps =>
          steps.zipWithIndex.map { (crumb, position) =>
            li(
              cls := KernelCss.BreadcrumbsItem,
              Option.when(position > 0)(
                span(cls := KernelCss.BreadcrumbsSeparator, aria.hidden := true, "/")
              ),
              crumb.href match {
                case Some(target) => a(href := target, crumb.label)
                // No link, and announced as the current page.
                case None => span(aria.current := "page", crumb.label)
              }
            )
          }
        }
      )
    )
}
