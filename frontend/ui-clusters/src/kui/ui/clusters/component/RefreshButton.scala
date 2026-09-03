package kui.ui.clusters.component

import com.raquo.laminar.api.L.*

import kui.ui.clusters.{ClustersCss, Messages, RefreshFlow, RefreshStatus}
import kui.ui.kernel.component.*
import kui.ui.kernel.state.FeatureState

/** "Go and look again", and what it says while it is looking.
  *
  * The server re-reads each cluster on its own schedule and the browser never polls, so this is the only
  * control a user has over freshness. It sits beside the timestamp, where the question "how old is this" is
  * already being answered.
  *
  * ## Why the status is text next to the button and not a toast
  *
  * A toast is for an outcome that arrives after attention has moved on. This one arrives while the user is
  * watching the button they just pressed, and putting it in the corner of the screen would move the answer
  * away from the question. The one exception is a rejection, which can arrive after the user has scrolled on
  * — but even that is shown here, because the alternative is a message that disappears before it is read.
  *
  * ## Why the busy button keeps its label
  *
  * A control whose text changes to "Refreshing…" is wider than one that says "Refresh", so pressing it moves
  * everything next to it. The spinner says it is busy; the label says what it does.
  */
object RefreshButton {

  def apply(
      flow: RefreshFlow,
      capability: Signal[FeatureState],
      testId: Option[String] = Some("cluster-refresh")
  ): HtmlElement = {
    val running = flow.status.map {
      case RefreshStatus.Running(_, _) => true
      case _ => false
    }

    div(
      cls := ClustersCss.Refresh,
      ActionPermissionWrapper(
        action = Button(
          label = Val(Messages.Refresh),
          onClick = Observer[Unit](_ => flow.request()),
          variant = ButtonVariant.Secondary,
          disabled = flow.enabled.map(!_),
          loading = running,
          icon = Some(() => Icon.refresh),
          testId = testId.map(id => s"$id-button")
        ),
        // One merged tooltip: the capability's reason and this button's own are the same sentence rather
        // than two competing explanations of why nothing happens when it is clicked.
        capability = capability,
        testId = testId.map(id => s"$id-gate")
      ),
      div(
        cls := ClustersCss.RefreshStatus,
        Components.testIdAttr(testId.map(id => s"$id-status")),
        // Announced, because the answer to a button press arrives seconds later and a user who has looked
        // away needs to be told rather than to keep checking.
        role := "status",
        aria.live := "polite",
        child.maybe <-- flow.status.map(RefreshFlow.describe).map(_.map(span(_)))
      )
    )
  }
}
