package kui.ui.clusters

import com.raquo.laminar.api.L.*

import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState

/** The clusters page.
  *
  * Intermediate. The typed clients and the caches behind this page exist (`ClustersApi`, `ClustersQueries`);
  * the dashboard that renders them is the next task. What is here is the frame and an honest empty state,
  * because the alternative — leaving the M0 ping button in place after deleting the client it called — does
  * not compile.
  */
object ClustersPage {

  /** @param capability
    *   this feature's current state. The dimmed-entry case never reaches here — the shell renders the
    *   fallback panel instead — but a service that dies *while* the page is open does.
    */
  def apply(
      queries: ClustersQueries,
      capability: Signal[FeatureState] = Val(FeatureState.Ready)
  ): HtmlElement = {
    // Named so that the parameters are not unused while the dashboard is being written, and so that the
    // wiring this page will need is already the wiring it has.
    val _ = (queries, capability)

    div(
      cls := ClustersCss.Page,
      dataAttr("testid") := "page-clusters",
      h1(Messages.Title),
      EmptyState(Messages.EmptyTitle, description = Some(Messages.EmptyDescription))
    )
  }
}

/** This feature's class names, as Scala constants, for the same reason `KernelCss` exists: a name typed as a
  * string literal at the point of use can be misspelled or deleted from the stylesheet while the code still
  * writes it, and here the compiler catches both.
  */
object ClustersCss {
  val Page = "kui-clusters"
  val Lead = "kui-clusters__lead"
  val Form = "kui-clusters__form"
  val Error = "kui-clusters__error"
  val Stale = "kui-clusters__stale"
  val TableStale = "kui-clusters__table--stale"
  val Fallback = "kui-clusters__fallback"
}
