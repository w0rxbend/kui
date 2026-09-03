package kui.ui.kernel.component

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

import kui.ui.kernel.css.KernelCss

/** One tab: a stable id, the text on the tab, and a thunk that builds its panel.
  *
  * `body` is a function rather than an element because the panel is built only when the tab is selected. That
  * is not an optimisation detail: a topic page's "Consumers" tab issues requests when it is created, and
  * building all five panels up front would fire five screens' worth of traffic for a user who looks at one.
  */
final case class Tab(id: String, label: String, body: () => HtmlElement)

/** A tab strip with exactly one panel in the document at a time.
  *
  * ## The keyboard contract (WAI-ARIA "tabs" pattern)
  *
  * A tab strip is a single stop in the Tab order, not one stop per tab. Once focus is inside it, the arrow
  * keys move between tabs and `Home`/`End` jump to the ends; pressing Tab leaves the strip entirely and lands
  * in the panel. That is done with a *roving tabindex*: the selected tab has `tabindex="0"` and every other
  * tab has `tabindex="-1"`, so the browser's own Tab order contains exactly one of them.
  *
  * Moving with an arrow key changes the selection as well as the focus. That is the "automatic activation"
  * variant of the pattern, and it is the right one here because switching a KUI tab is cheap and reversible;
  * the manual variant (arrow to move, Enter to activate) exists for tabs whose panels are expensive to open.
  *
  * ## Lazy panels
  *
  * Only the selected panel exists in the DOM. Rendering all of them and hiding the inactive ones with CSS
  * would mean their subscriptions stay live, their requests keep firing, and the whole set appears at once if
  * the stylesheet ever fails to load.
  *
  * ## Degraded input
  *
  * An empty tab list renders an empty strip and no panel. A `selected` id that matches no tab renders no
  * panel rather than silently jumping to the first one — silently changing the caller's state to make a
  * render succeed hides the bug that produced the bad id.
  */
object Tabs {

  def apply(tabs: Signal[List[Tab]], selected: Var[String], testId: Option[String] = None): HtmlElement = {
    val instance = Components.nextId("kui-tabs")

    def tabId(id: String): String = s"$instance-tab-$id"
    def panelId(id: String): String = s"$instance-panel-$id"

    /** The tab a keystroke should move to, given where we are now. `None` leaves things alone. */
    def target(key: String, current: String, available: List[Tab]): Option[String] = {
      val position = available.indexWhere(_.id == current)
      if position < 0 || available.isEmpty then None
      else
        key match {
          // Wrapping is part of the pattern: pressing Right on the last tab goes back to the first.
          case "ArrowRight" | "ArrowDown" => Some(available((position + 1) % available.size).id)
          case "ArrowLeft" | "ArrowUp" => Some(available((position - 1 + available.size) % available.size).id)
          case "Home" => Some(available.head.id)
          case "End" => Some(available.last.id)
          case _ => None
        }
    }

    // Which tab should take DOM focus. Separate from `selected` because focus must move only when
    // the user pressed a key, never when the selection changes for some other reason — stealing
    // focus because a server response switched tabs is deeply unpleasant.
    val focusRequest = new EventBus[String]

    def renderTab(id: String, tab: Signal[Tab]): HtmlElement =
      button(
        tpe := "button",
        idAttr := tabId(id),
        cls := KernelCss.TabsTab,
        cls(KernelCss.TabsSelected) <-- selected.signal.map(_ == id),
        role := "tab",
        aria.controls := panelId(id),
        aria.selected <-- selected.signal.map(_ == id),
        // The roving tabindex. Exactly one tab is in the browser's Tab order at any moment.
        tabIndex <-- selected.signal.map(current => if current == id then 0 else -1),
        dataAttr("tab-id") := id,
        text <-- tab.map(_.label),
        onClick.mapTo(id) --> selected,
        inContext { element =>
          focusRequest.events.filter(_ == id) --> { _ => element.ref.focus() }
        }
      )

    val activeTab: Signal[Option[Tab]] =
      selected.signal
        .combineWith(tabs)
        .map((current, available) => available.find(_.id == current))
        // Without `distinct` on the id, any change to the tab list would tear down and rebuild the
        // open panel, losing its scroll position and re-issuing its requests.
        .distinctBy(_.map(_.id))

    div(
      cls := KernelCss.Tabs,
      Components.testIdAttr(testId),
      div(
        cls := KernelCss.TabsList,
        role := "tablist",
        children <-- tabs.split(_.id)((id, _, tabSignal) => renderTab(id, tabSignal)),
        onKeyDown.compose(_.withCurrentValueOf(selected.signal, tabs)) --> { (event, current, available) =>
          target(event.key, current, available).foreach { next =>
            // The browser scrolls the page on arrow keys unless told otherwise, and inside a tab
            // strip that is never what the user meant.
            event.preventDefault()
            selected.set(next)
            focusRequest.emit(next)
          }
        }
      ),
      child.maybe <-- activeTab.map(
        _.map(tab =>
          div(
            idAttr := panelId(tab.id),
            cls := KernelCss.TabsPanel,
            role := "tabpanel",
            aria.labelledBy := tabId(tab.id),
            // The panel is a Tab stop so that a keyboard user can reach content that holds no
            // focusable element of its own.
            tabIndex := 0,
            tab.body()
          )
        )
      )
    )
  }
}
