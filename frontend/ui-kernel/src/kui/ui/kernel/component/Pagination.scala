package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** First, previous, next, last; "Page X of Y"; a box to jump to a page; and a page-size selector.
  *
  * ## It renders nothing at all for a single page
  *
  * A disabled pagination bar under a list of three rows is chrome that teaches the user nothing and takes up
  * the space where the next thing should be. Emptiness is the honest rendering of "there is only one page".
  *
  * ## Why the page size is a control and not a preference
  *
  * Everything here writes to the query string, page size included. A remembered page size would mean a link
  * to "page 3" showed its recipient a different set of rows from the ones the sender was looking at, which
  * defeats the point of putting the state in the URL at all.
  *
  * @param onPage
  *   fired with a valid page number. Out-of-range input never reaches it: the "go to page" box refuses rather
  *   than clamping, because silently turning 900 into 12 hides a mistyped number
  */
object Pagination {

  /** `<nav>`. Laminar spells the element `navTag`, because `nav` is the attribute namespace. */
  private val navTag = htmlTag("nav")

  val DefaultSizes: List[Int] = List(25, 50, 100, 500)

  def apply(
      page: Signal[Int],
      pageCount: Signal[Int],
      pageSize: Signal[Int],
      onPage: Int => Unit,
      onPageSize: Int => Unit,
      sizes: List[Int] = DefaultSizes,
      testId: Option[String] = None
  ): HtmlElement = {
    val jumpTo = Var("")
    val sizeId = Components.nextId("kui-page-size")

    def step(
        label: String,
        icon: => SvgElement,
        target: (Int, Int) => Int,
        disabledWhen: (Int, Int) => Boolean,
        name: String
    ) =
      button(
        tpe := "button",
        cls := KernelCss.PaginationButton,
        aria.label := label,
        L.title := label,
        Components.testIdAttr(testId.map(id => s"$id-$name")),
        L.disabled <-- page.combineWith(pageCount).map(disabledWhen(_, _)),
        icon,
        onClick.compose(_.withCurrentValueOf(page, pageCount)) --> { (_, current, count) =>
          val next = target(current, count)
          if next != current then onPage(next)
        }
      )

    div(
      cls := KernelCss.Pagination,
      Components.testIdAttr(testId),
      // The whole bar disappears for a single page; `child.maybe` rather than a CSS class, so that
      // nothing is in the accessibility tree either.
      child.maybe <-- pageCount.map { count =>
        Option.when(count > 1)(
          div(
            cls := KernelCss.PaginationInner,
            navTag(
              cls := KernelCss.PaginationSteps,
              aria.label := "Pagination",
              step("First page", Icon.chevronLeft, (_, _) => 1, (current, _) => current <= 1, "first"),
              step(
                "Previous page",
                Icon.chevronLeft,
                (current, _) => current - 1,
                (current, _) => current <= 1,
                "prev"
              ),
              L.span(
                cls := KernelCss.PaginationLabel,
                // Announced when it changes, so a screen-reader user who pressed Next is told where
                // they landed instead of having to go looking for it.
                aria.live := "polite",
                Components.testIdAttr(testId.map(_ + "-label")),
                text <-- page.combineWith(pageCount).map((current, count) => s"Page $current of $count")
              ),
              step(
                "Next page",
                Icon.chevronRight,
                (current, _) => current + 1,
                (current, count) => current >= count,
                "next"
              ),
              step(
                "Last page",
                Icon.chevronRight,
                (_, count) => count,
                (current, count) => current >= count,
                "last"
              )
            ),
            form(
              cls := KernelCss.PaginationJump,
              L.label(cls := KernelCss.VisuallyHidden, forId := s"$sizeId-jump", "Go to page"),
              input(
                idAttr := s"$sizeId-jump",
                cls := KernelCss.PaginationJumpInput,
                tpe := "number",
                L.minAttr := "1",
                L.maxAttr <-- pageCount.map(_.toString),
                Components.testIdAttr(testId.map(_ + "-jump")),
                controlled(L.value <-- jumpTo.signal, onInput.mapToValue --> jumpTo)
              ),
              button(tpe := "submit", cls := KernelCss.PaginationGo, "Go"),
              onSubmit.preventDefault.compose(_.withCurrentValueOf(jumpTo.signal, pageCount)) --> {
                (_, typed, count) =>
                  typed.trim.toIntOption.filter(wanted => wanted >= 1 && wanted <= count) match {
                    case Some(wanted) =>
                      jumpTo.set("")
                      onPage(wanted)
                    // Out of range, or not a number: the box keeps what was typed and nothing fires.
                    // Clamping would show page 12 to somebody who asked for 900 and believed they got it.
                    case None => ()
                  }
              }
            ),
            div(
              cls := KernelCss.PaginationSize,
              L.label(cls := KernelCss.PaginationSizeLabel, forId := sizeId, "Rows"),
              select(
                idAttr := sizeId,
                cls := KernelCss.PaginationSizeSelect,
                Components.testIdAttr(testId.map(_ + "-size")),
                sizes.map(size => option(L.value := size.toString, size.toString)),
                L.value <-- pageSize.map(_.toString),
                onChange.mapToValue --> { raw => raw.toIntOption.filter(sizes.contains).foreach(onPageSize) }
              )
            )
          )
        )
      }
    )
  }
}
